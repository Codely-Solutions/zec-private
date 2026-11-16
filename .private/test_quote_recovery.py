import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile

import hosted_release
from hosted_core import network_startup_diagnostics
from hosted_quote_probe import stage_quote_apk
from quote_recovery import (
    ARTIFACTS, OPERATOR_ID, REPOSITORY, WORKFLOW, require_transient_quote_failure,
    resolve, resolve_quotes, same_controller_retry, unused_reservation, verify_delta,
)
from size_workflow_ui_scenario import quote_footer, quote_footer_needs_scroll, tor_http_failure
from tor_resident_ui_driver import quote_stage_records


SOURCE, OLD, MAIN = 'a' * 40, 'b' * 40, 'c' * 40


class QuoteRecoveryTests(unittest.TestCase):
    def test_loading_balance_does_not_hide_the_quote_footer_from_the_scenario(self):
        labels = {'from', 'to', 'loading'}
        self.assertTrue(quote_footer_needs_scroll(labels, quote_footer([
            {'id': 'balance', 'text': 'Loading'},
        ])))
        footer = {'id': 'SWAP_REVIEW_BUTTON', 'text': 'Loading'}
        self.assertFalse(quote_footer_needs_scroll(labels, quote_footer([footer])))

    def test_startup_diagnostics_report_locations_and_codes_without_messages(self):
        result = network_startup_diagnostics({
            'pre_wallet_instrumentation': 'java.lang.RuntimeException: transport error: deadline has elapsed\n'
                                          '  at cash.z.ecc.android.sdk.Test.get(Test.kt:40)\n'
                                          'CORE_TARGETS private target list',
            'native_errors': [{'probe_stage': 'latest_height', 'sdk_code': 3200, 'body': 'private payload'}],
        })
        self.assertEqual(result, dict(exception_classes=['java.lang.RuntimeException'],
                                      frames=['cash.z.ecc.android.sdk.Test.get(Test.kt:40)'],
                                      resolved_targets=True, causes=['timeout'],
                                      native=[dict(probe_stage='latest_height', sdk_code=3200)]))

    def test_published_quote_probe_uses_hosted_build_run_after_recovery(self):
        buffer = io.BytesIO()
        binary = b'fake quote APK'
        with zipfile.ZipFile(buffer, 'w') as archive:
            archive.writestr('app.apk', binary)
            archive.writestr('source-sha.txt', SOURCE)
        data = buffer.getvalue()
        producer = dict(id=54, size_in_bytes=len(data), digest='sha256:' + hashlib.sha256(data).hexdigest())
        quote = dict(passed=True, funds_transferred=False, personal_wallet_access=False, build_source_sha=SOURCE,
                     workflow_run_id=9, artifact_run_id=7, producer=producer,
                     original_apk_sha256=hashlib.sha256(binary).hexdigest())
        evidence = dict(source_sha=SOURCE, production_quotes=quote, artifact_run_id='7', versionCode=42)
        core = dict(producer_controller_sha=OLD)
        with tempfile.TemporaryDirectory() as folder, \
                patch('hosted_quote_probe.recorded_pair', return_value=(evidence, core)), \
                patch('hosted_quote_probe.Metadata'), \
                patch('hosted_release.artifact', return_value=producer) as lookup, \
                patch('hosted_quote_probe.subprocess.run', side_effect=lambda *_, stdout, **__: stdout.write(data)):
            root = Path(folder)
            stage_quote_apk(root, 'v3.19.0-private.26')
            self.assertEqual(lookup.call_args.args[1:], (54, 'isolated-ui-repro-apk', 7, OLD))
            self.assertEqual((root / 'quotes.apk').read_bytes(), binary)
            quote['artifact_run_id'] = 9
            with self.assertRaisesRegex(ValueError, 'different build'):
                stage_quote_apk(root, 'v3.19.0-private.26')

    def test_transient_failure_requires_one_exact_trusted_marker(self):
        metadata = Mock(gh='gh', repo='repos/' + REPOSITORY)
        marker = b'2026-11-15T12:00:00.000Z HOSTED_QUOTE_FAILURE transient tor_http\n'
        for log in (b'PHASE1_SCENARIO_FAILED: HOSTED_QUOTE_FAILURE transient tor_http\n',
                    marker + marker, marker.replace(b'tor_http', b'unknown')):
            with patch('quote_recovery.subprocess.check_output', return_value=log):
                with self.assertRaisesRegex(ValueError, 'did not prove'):
                    require_transient_quote_failure(metadata, {'id': 9})
        with patch('quote_recovery.subprocess.check_output', return_value=marker):
            require_transient_quote_failure(metadata, {'id': 9})

    def test_same_controller_reservation_is_atomic_and_signer_checks_the_run(self):
        metadata = Mock()
        commit = 'e' * 40
        ref = 'refs/heads/testnet-claims/quote-recovery-7'
        commits = {}
        refs = {}

        def api(path):
            if path == '/git/ref/heads/main':
                return {'object': {'sha': MAIN}}
            if path == '/git/commits/' + MAIN:
                return {'tree': {'sha': 'f' * 40}}
            if path.startswith('/rules/branches/'):
                return [{'type': 'update'}, {'type': 'deletion'}]
            if path == '/git/commits/' + commit:
                return commits[commit]
            return {'object': {'sha': refs[ref]}}

        def post(_metadata, path, data):
            if path == '/git/commits':
                if not commits:
                    commits[commit] = data
                return {'sha': commit}
            if data['ref'] in refs:
                raise RuntimeError('Reference already exists')
            refs[data['ref']] = data['sha']
            return {'ref': data['ref'], 'object': {'sha': data['sha']}}

        metadata.api.side_effect = api
        with patch('hosted_claim.post_ref', side_effect=post), \
                patch.dict(os.environ, {'GITHUB_RUN_ID': '8', 'GITHUB_RUN_ATTEMPT': '1'}):
            same_controller_retry(SOURCE, MAIN, '7', metadata)
            same_controller_retry(SOURCE, MAIN, '7', metadata, signing=True)
            with patch.dict(os.environ, {'GITHUB_RUN_ID': '9'}):
                with self.assertRaisesRegex(RuntimeError, 'already exists'):
                    same_controller_retry(SOURCE, MAIN, '7', metadata)
                with self.assertRaisesRegex(ValueError, 'different inputs'):
                    same_controller_retry(SOURCE, MAIN, '7', metadata, signing=True)
            with patch.dict(os.environ, {'GITHUB_RUN_ATTEMPT': '2'}):
                with self.assertRaisesRegex(ValueError, 'initial dispatch'):
                    same_controller_retry(SOURCE, MAIN, '7', metadata)

    def test_same_controller_admission_reuses_exact_original_artifact_ids(self):
        original = dict(id=7, path=WORKFLOW, event='workflow_dispatch', head_branch='main',
                        head_sha=MAIN, head_repository={'full_name': REPOSITORY}, actor={'id': OPERATOR_ID},
                        status='completed', conclusion='failure', run_attempt=1)
        states = {'build / admit_hosted': 'success', 'build / unsigned': 'success', 'build / emulator': 'success',
                  'build / testnet_apk / testnet': 'success', 'build / hosted_core / wallet': 'success',
                  'build / hosted_quotes / quote-cycles': 'failure', 'build / publish': 'skipped'}
        metadata = Mock()
        metadata.api.return_value = original
        metadata.pages.side_effect = [
            [dict(id=i, name=name, conclusion=state, run_id=7, head_sha=MAIN)
             for i, (name, state) in enumerate(states.items())],
            [dict(id=i, name=name) for i, name in enumerate(ARTIFACTS.values(), 1)],
        ]
        with patch.dict(os.environ, {'GITHUB_RUN_ATTEMPT': '1'}), \
                patch('quote_recovery.require_dispatch'), patch('quote_recovery.verify_custom_child'), \
                patch('quote_recovery.require_transient_quote_failure') as failure, \
                patch.object(hosted_release, 'artifact', side_effect=lambda _, i, *args: {'id': i}) as artifact:
            result = resolve(Path('.'), SOURCE, MAIN, '7', metadata)
            self.assertEqual(result, dict(original_controller=MAIN, same_controller_retry='true',
                                          production_id='1', core_id='2', quote_id='3', core_receipt_id='4'))
            self.assertEqual({(call.args[3], call.args[4]) for call in artifact.call_args_list}, {('7', MAIN)})
            self.assertEqual(failure.call_args.args[1]['name'], 'build / hosted_quotes / quote-cycles')

    def test_quote_retry_uses_only_the_current_request_without_an_http_response(self):
        def record(time, stage, error, status):
            return (f'1794751000.{time}  1457  2181 I SwapQuoteStage: stage={stage} '
                    f'elapsedMs=12302 exception={error} status={status}\n')

        stale = record('111', 'HTTP_REQUEST', 'RuntimeException', 'none')
        transport_error = record('222', 'HTTP_REQUEST', 'RuntimeException', 'none')
        rejection = record('333', 'HTTP_REQUEST', 'ClientRequestException', '400')
        bad_data = record('444', 'RESPONSE_DECODE', 'RuntimeException', 'none')
        adb = Mock()

        def collect(log):
            adb.return_value.stdout = log.encode()
            return quote_stage_records(adb)

        before = set(collect(stale))
        self.assertTrue(tor_http_failure(before, collect(stale + transport_error)))
        self.assertFalse(tor_http_failure(before, collect(stale + bad_data)))
        self.assertFalse(tor_http_failure(before, collect(stale + transport_error + rejection)))
        self.assertEqual(list(collect(stale + 'unrelated log\n').values()),
                         ['stage=HTTP_REQUEST elapsedMs=12302 exception=RuntimeException status=none'])

    def test_linked_reservation_requires_positive_pre_wallet_failure_evidence(self):
        prior = 'd' * 40
        reservation = 'e' * 40
        metadata = Mock(gh='gh', repo='repos/' + REPOSITORY)
        run = dict(id=8, path=WORKFLOW, event='workflow_dispatch', head_branch='main', head_sha=prior,
                   head_repository={'full_name': REPOSITORY}, actor={'id': OPERATOR_ID},
                   status='completed', conclusion='failure', run_attempt=1)
        jobs = [dict(id=i, name=name, conclusion=result, run_id=8, head_sha=prior)
                for i, (name, result) in enumerate((('build / admit_hosted', 'success'),
                    ('build / hosted_core / reserve', 'success'), ('build / hosted_core / wallet', 'failure'),
                    ('build / publish', 'skipped')), 1)]
        metadata.pages.return_value = jobs
        safe = b'Hosted lifecycle failed at networkTargets, claim state no-POST'
        preclaim = b'CORE_STAGE sender passed\nHosted lifecycle failed at preclaim_readiness, claim state no-POST'
        for log, error in ((safe, None), (preclaim, None), (b'no-POST', 'did not prove'),
                           (preclaim.replace(b'no-POST', b'uncertain'), 'did not prove'),
                           (preclaim + b'\nCORE_STAGE fund passed', 'did not prove'),
                           (safe + b'\nCORE_STAGE sender passed', 'did not prove')):
            metadata.api.side_effect = [run, {'object': {'sha': reservation}}]
            with self.subTest(error=error), patch('quote_recovery.git', return_value=prior.encode()), \
                    patch('quote_recovery.verify_delta'), patch('release_inputs.version_code', return_value=42), \
                    patch('hosted_claim.verify') as verify, patch('subprocess.check_output', return_value=log):
                if error:
                    with self.assertRaisesRegex(ValueError, error):
                        unused_reservation(Path('.'), SOURCE, MAIN, '7', OLD, '8', metadata)
                    verify.assert_not_called()
                else:
                    self.assertEqual(unused_reservation(Path('.'), SOURCE, MAIN, '7', OLD, '8', metadata), reservation)
                    self.assertEqual(verify.call_args.args[0]['run'], 8)
        metadata.api.side_effect = [run, {'object': {'sha': reservation}}]
        with patch('quote_recovery.git', return_value=prior.encode()), \
                patch('release_inputs.version_code', return_value=42), patch('hosted_claim.verify'), \
                patch('quote_recovery.subprocess.check_output', return_value=preclaim):
            self.assertEqual(unused_reservation(Path('.'), SOURCE, MAIN, '8', prior, '8', metadata), reservation)

    def test_skipped_wallet_reuses_original_builds_and_passing_quotes(self):
        run = dict(id=7, path=WORKFLOW, event='workflow_dispatch', head_branch='main', head_sha=OLD,
                   head_repository={'full_name': REPOSITORY}, actor={'id': OPERATOR_ID},
                   status='completed', conclusion='success', run_attempt=1)
        states = {'build / admit_hosted': 'success', 'build / unsigned': 'success', 'build / emulator': 'success',
                  'build / testnet_apk / testnet': 'success', 'build / hosted_core': 'skipped',
                  'build / hosted_quotes / quote-cycles': 'success', 'build / publish': 'skipped'}
        names = ('validated-unsigned-apk', 'core-testnet-candidate', 'isolated-ui-repro-apk', 'hosted-quote-receipt')
        artifacts = [dict(id=i, name=name) for i, name in enumerate(names, 1)]
        for outcomes, error in ((states, None),
                                (dict(states, **{'build / hosted_core / reserve': 'success'}), 'previous wallet'),
                                (dict(states, **{'build / publish': 'success'}), 'publish')):
            metadata = Mock()
            metadata.api.side_effect = [run, []]
            metadata.pages.side_effect = [
                [dict(name=name, conclusion=result, run_id=7, head_sha=OLD) for name, result in outcomes.items()],
                artifacts]
            with self.subTest(error=error), patch('quote_recovery.require_dispatch'), \
                    patch('quote_recovery.verify_delta'), \
                    patch('release_inputs.version_code', return_value=42), \
                    patch.object(hosted_release, 'artifact', side_effect=lambda _, i, *args: {'id': i}):
                if error:
                    with self.assertRaisesRegex(ValueError, error):
                        resolve(Path('.'), SOURCE, MAIN, '7', metadata)
                else:
                    result = resolve(Path('.'), SOURCE, MAIN, '7', metadata)
                    self.assertEqual(result['run_missing_core'], 'true')
                    self.assertEqual(result['quote_receipt_id'], '4')
                    self.assertEqual(result['quote_run'], 7)
                    self.assertNotIn('core_receipt_id', result)

    def test_passing_quotes_reuse_requires_unchanged_runner_and_no_prior_attestation(self):
        quote_head = 'd' * 40
        run = dict(id=8, path=WORKFLOW, event='workflow_dispatch', head_branch='main', head_sha=quote_head,
                   head_repository={'full_name': REPOSITORY}, actor={'id': OPERATOR_ID},
                   status='completed', conclusion='failure', run_attempt=1)
        jobs = [dict(name=name, conclusion=state, head_sha=quote_head, run_id=8) for name, state in (
            ('review', 'success'), ('build / admit_hosted', 'success'),
            ('build / hosted_quotes / quote-cycles', 'success'), ('build / publish', 'failure'))]
        receipt = {'id': 22, 'name': 'hosted-quote-receipt'}
        for delta, artifacts, error in (
            (b'.private/publish.py\0', [receipt], None),
            (b'.private/tor_resident_ui_driver.py\0', [receipt], 'execution inputs changed'),
            (b'.private/publish.py\0', [receipt, {'name': 'release-attestation-1'}], 'no publication attestation'),
        ):
            metadata = Mock()
            metadata.api.return_value = run
            metadata.pages.side_effect = [jobs, artifacts]
            with self.subTest(delta=delta, error=error), patch('quote_recovery.verify_delta'), \
                    patch('quote_recovery.git', side_effect=[quote_head.encode(), delta]), \
                    patch.object(hosted_release, 'artifact', return_value=receipt):
                if error:
                    with self.assertRaisesRegex(ValueError, error):
                        resolve_quotes(Path('.'), SOURCE, MAIN, OLD, '8', metadata)
                else:
                    self.assertEqual(resolve_quotes(Path('.'), SOURCE, MAIN, OLD, '8', metadata),
                                     {'quote_receipt_id': '22', 'quote_controller': quote_head, 'quote_run': 8})

    def test_signer_reaches_recovery_admission_before_reading_any_key(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / '.private').mkdir()
            (root / '.private/release-policy.json').write_text('{"publication_enabled": true}')
            (root / 'artifact').mkdir()
            (root / 'artifact/proof.json').write_text('{}')
            env = dict(GITHUB_REPOSITORY=REPOSITORY, SOURCE_SHA=SOURCE, CONTROLLER_SHA=MAIN,
                       RECOVER_RUN='7', HOSTED_ACCEPTANCE='true')
            with patch.dict(os.environ, env):
                import publish

                with patch.object(publish, 'ROOT', root), patch.object(publish, 'require_current_controller'), \
                        patch('quote_recovery.resolve', side_effect=ValueError('Recovery admission reached')):
                    with self.assertRaisesRegex(ValueError, 'Recovery admission reached'):
                        publish.main()

    def test_recovery_rejects_quote_receipt_from_a_different_controller(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / '.private').mkdir()
            (root / '.private/hosted-fixture.json').write_text('{}')
            original_apk = {'id': 5}
            shared = dict(build_source_sha=SOURCE, candidate_version_code=42, production_artifact=original_apk)
            (root / 'hosted-core.json').write_text(json.dumps(dict(shared, workflow_run_id=7)))
            (root / 'hosted-quotes.json').write_text(json.dumps(dict(
                shared, workflow_run_id=8, artifact_run_id=7, quote_controller_sha=OLD)))
            with patch.dict(os.environ, {'GITHUB_RUN_ID': '8'}), patch.object(hosted_release, 'ROOT', root), \
                    patch.object(hosted_release, 'version_code', return_value=42), \
                    patch.object(hosted_release, 'artifact', return_value=original_apk):
                with self.assertRaisesRegex(ValueError, 'different execution inputs'):
                    hosted_release.read_receipts(root, proof={'workflow_run_id': '7', 'versionCode': 42},
                                                 source=SOURCE, controller=MAIN, production_id='5', core_id='6',
                                                 quote_id='9', recovery={'run': 7, 'original_controller': OLD})

    def test_recovery_rejects_product_and_wallet_harness_changes(self):
        for changed in (b'gradle.properties\0', b'.private/core_testnet_driver.py\0'):
            with patch('quote_recovery.verify_custom_child'), \
                    patch('quote_recovery.git', side_effect=[OLD.encode(), changed]):
                with self.assertRaisesRegex(ValueError, 'outside quote recovery paths'):
                    verify_delta(Path('.'), SOURCE, OLD, MAIN)

    def test_recovery_requires_authorized_exact_run_and_completed_original_wallet(self):
        original = dict(id=7, path=WORKFLOW, event='workflow_dispatch', head_branch='main',
                        head_sha=OLD, head_repository={'full_name': REPOSITORY}, actor={'id': OPERATOR_ID},
                        status='completed', conclusion='failure', run_attempt=2)
        current = dict(original, id=8, head_sha=MAIN)
        states = {'build / admit_hosted': 'success', 'build / unsigned': 'success', 'build / emulator': 'success',
                  'build / testnet_apk / testnet': 'success', 'build / hosted_core / wallet': 'success',
                  'build / hosted_quotes / quote-cycles': 'failure', 'build / publish': 'skipped'}
        artifacts = [dict(id=i, name=name) for i, name in enumerate(ARTIFACTS.values(), 1)]
        with tempfile.TemporaryDirectory() as temp:
            event = Path(temp) / 'event.json'
            env = dict(GITHUB_REPOSITORY=REPOSITORY, GITHUB_REF='refs/heads/main',
                       GITHUB_EVENT_NAME='workflow_dispatch', GITHUB_RUN_ID='8', GITHUB_EVENT_PATH=str(event))
            cases = [('7', states, None), ('9', states, 'does not authorize'),
                     ('7', dict(states, **{'build / hosted_core / wallet': 'failure'}), 'wallet'),
                     ('7', dict(states, **{'build / publish': 'failure'}), 'publish')]
            for authorized, outcomes, error in cases:
                with self.subTest(authorized=authorized, error=error), patch.dict(os.environ, env), \
                        patch('quote_recovery.verify_delta'), \
                        patch.object(hosted_release, 'artifact', side_effect=lambda _, i, *args: {'id': i}):
                    event.write_text(json.dumps({'inputs': {'source_sha': SOURCE, 'operation': 'recover',
                                                           'recover_run': authorized}}))
                    metadata = Mock()
                    metadata.api.side_effect = [current, {'object': {'sha': MAIN}}, original]
                    jobs = [dict(name=name, conclusion=state, run_id=7, head_sha=OLD)
                            for name, state in outcomes.items()]
                    metadata.pages.side_effect = [jobs, artifacts]
                    if error:
                        with self.assertRaisesRegex(ValueError, error):
                            resolve(Path('.'), SOURCE, MAIN, '7', metadata)
                    else:
                        result = resolve(Path('.'), SOURCE, MAIN, '7', metadata)
                        self.assertEqual(result['original_controller'], OLD)
                        self.assertEqual(result['core_receipt_id'], '4')


if __name__ == '__main__':
    unittest.main()
