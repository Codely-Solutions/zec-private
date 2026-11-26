import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile
from test_support import patch_environment, public_baseline_metadata

from acceptance_policy import POLICY_VERSION
import hosted_release
from hosted_core import funding_claim_state, network_startup_diagnostics
from hosted_quote_probe import BRIDGE_ARTIFACT, retained_bridge, stage_bridge, stage_quote_apk
from quote_recovery import (
    ARTIFACTS, OPERATOR_ID, REPOSITORY, WORKFLOW, require_transient_quote_failure,
    resolve, resolve_quotes, same_controller_retry, unused_reservation, verify_delta, verify_receipt_delta,
)
from size_workflow_ui_scenario import market_reference_state, quote_footer, quote_footer_needs_scroll, tor_http_failure
from tor_resident_ui_driver import quote_stage_records


BUILD_JOBS = {'admit_hosted': 'success', 'unsigned': 'success', 'emulator': 'success',
              'testnet_apk / testnet': 'success'}
BUILD_ARTIFACTS = tuple(ARTIFACTS.values())[:3]
QUOTE = 'hosted_quotes / quote-cycles'
WALLET = 'hosted_core / wallet'
PREPARE = 'hosted_core / prepare-direct'
RESERVE = 'hosted_core / reserve'
LINKED_QUOTE = 'recovery_quotes / quote-cycles'
SOURCE, OLD, MAIN = 'a' * 40, 'b' * 40, 'c' * 40
VERIFY_RECOVERED_RECEIPTS = hosted_release.verify_recovered_receipts


class QuoteRecoveryTests(unittest.TestCase):
    def setUp(self):
        # These fixtures exercise run/job decisions. Archive and receipt validation uses separate fixtures.
        self.verify_receipts = self.enterContext(patch.object(hosted_release, 'verify_recovered_receipts'))

    def test_only_a_durable_zero_request_cutoff_can_prove_no_post_after_ledger_creation(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            ledger = root / 'funding-claim-ledger.json'
            with patch_environment({'CORE_FAUCET_LEDGER': str(ledger)}):
                self.assertEqual(funding_claim_state(root), 'no-POST')
                for status, count, result in (
                        ('closed-before-post', 0, 'no-POST'),
                        ('closed-before-post', False, 'unknown'),
                        ('closed-before-post', 1, 'unknown'),
                        ('request-starting', 1, 'request-starting'),
                        ('outcome-uncertain', 1, 'outcome-uncertain')):
                    with self.subTest(status=status, count=count):
                        ledger.write_text(json.dumps(dict(provider='argon-testnet', status=status, claim_requests=count)))
                        self.assertEqual(funding_claim_state(root), result)
                ledger.write_text('{"status":"closed-before-post"')
                self.assertEqual(funding_claim_state(root), 'unknown')

    def test_market_result_requires_visible_reference_or_warning(self):
        self.assertIsNone(market_reference_state({'quote details', 'market reference'}))
        self.assertIsNone(market_reference_state({'checking market price…', 'confirm'}))
        self.assertEqual(market_reference_state({'kraken', 'market reference'}), 'compared')
        self.assertEqual(market_reference_state({'17.0% below market reference'}), 'warning')
        self.assertEqual(market_reference_state({'price check unavailable'}), 'unavailable')

    def test_compact_market_row_requires_a_comparison_result(self):
        for result in ('2.50% less value', '1.25% more value', 'in line with market'):
            with self.subTest(result=result):
                self.assertEqual(market_reference_state({'market comparison', result}), 'compared')
        self.assertIsNone(market_reference_state({'market comparison', '(-3.47%)'}))
        self.assertIsNone(market_reference_state({'2.50% less value'}))
        self.assertEqual(market_reference_state({'market comparison', 'price check unavailable'}), 'unavailable')

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

    def test_bridge_handoff_binds_run_role_controller_and_bytes(self):
        payload = b'synthetic bridge bytes'
        for marker, record, error in (
            (MAIN, {}, None),
            (OLD, {}, 'exact controller output'),
            (MAIN, {'workflow_run': {'id': 8, 'head_sha': MAIN}}, 'exact trusted run and role'),
            (MAIN, {'workflow_run': {'id': 7, 'head_sha': OLD}}, 'exact trusted run and role'),
            (MAIN, {'digest': 'sha256:' + '0' * 64}, 'archive bytes differ'),
        ):
            with self.subTest(error=error), tempfile.TemporaryDirectory() as folder:
                buffer = io.BytesIO()
                with zipfile.ZipFile(buffer, 'w') as archive:
                    archive.writestr('ui-bridge.apk', payload)
                    archive.writestr('source-sha.txt', marker)
                data = buffer.getvalue()
                metadata = Mock(gh='gh', repo='repos/' + REPOSITORY)
                metadata.api.return_value = dict(
                    dict(id=54, name=BRIDGE_ARTIFACT, expired=False, size_in_bytes=len(data),
                         digest='sha256:' + hashlib.sha256(data).hexdigest(),
                         workflow_run={'id': 7, 'head_sha': MAIN}), **record)
                root = Path(folder)/'bridge'
                with patch_environment(GITHUB_SHA=MAIN), patch('hosted_release.Metadata', return_value=metadata), \
                        patch('hosted_release.subprocess.run', side_effect=lambda *_, stdout, **__: stdout.write(data)):
                    if error:
                        with self.assertRaisesRegex(ValueError, error):
                            stage_bridge(root, '54', '7')
                    else:
                        stage_bridge(root, '54', '7')
                        self.assertEqual((root/'ui-bridge.apk').read_bytes(), payload)
                        receipt = json.loads((root/'bridge-receipt.json').read_text())
                        self.assertEqual(receipt['apk_sha256'], hashlib.sha256(payload).hexdigest())
                        self.assertEqual((receipt['producer']['id'], receipt['run_id'], receipt['controller']),
                                         (54, 7, MAIN))

    def test_retained_bridge_requires_a_single_live_artifact(self):
        metadata = Mock()
        live = dict(name=BRIDGE_ARTIFACT, id=54, expired=False)
        metadata.pages.return_value = [dict(live, expired=True)]
        self.assertIsNone(retained_bridge('7', MAIN, metadata))
        metadata.pages.return_value.append(live)
        with patch('hosted_release.artifact', return_value={'id': 54}) as lookup:
            self.assertEqual(retained_bridge('7', MAIN, metadata), 54)
            lookup.assert_called_once_with(metadata, 54, BRIDGE_ARTIFACT, '7', MAIN)
        metadata.pages.return_value.append(live)
        with self.assertRaisesRegex(ValueError, 'Ambiguous retained bridge'):
            retained_bridge('7', MAIN, metadata)

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
                patch_environment({'GITHUB_RUN_ID': '8', 'GITHUB_RUN_ATTEMPT': '1'}):
            same_controller_retry(SOURCE, MAIN, '7', metadata)
            same_controller_retry(SOURCE, MAIN, '7', metadata, signing=True)
            with patch_environment({'GITHUB_RUN_ID': '9'}):
                with self.assertRaisesRegex(RuntimeError, 'already exists'):
                    same_controller_retry(SOURCE, MAIN, '7', metadata)
                with self.assertRaisesRegex(ValueError, 'different inputs'):
                    same_controller_retry(SOURCE, MAIN, '7', metadata, signing=True)
            with patch_environment({'GITHUB_RUN_ATTEMPT': '2'}):
                with self.assertRaisesRegex(ValueError, 'initial dispatch'):
                    same_controller_retry(SOURCE, MAIN, '7', metadata)


    def test_superseded_public_baseline_stops_recovery_before_original_artifact_lookup(self):
        metadata = Mock()
        metadata.api.return_value = {'tag_name': 'v3.19.0-private.999'}
        with patch('quote_recovery.require_dispatch'):
            for signing in (False, True):
                with self.subTest(signing=signing):
                    with self.assertRaisesRegex(ValueError, 'verified hosted baseline'):
                        resolve(Path('.'), SOURCE, MAIN, '7', metadata, signing=signing)
        self.assertEqual(metadata.api.call_count, 2)
        self.assertTrue(all(item.args == ('/releases/latest',) for item in metadata.api.call_args_list))

    def test_same_controller_admission_reuses_exact_original_artifact_ids(self):
        states = dict(BUILD_JOBS, publish='skipped', **{WALLET: 'success', QUOTE: 'failure'})
        metadata = self.recovery_metadata(self.recovery_run(7, MAIN, states, ARTIFACTS.values()))
        with patch_environment({'GITHUB_RUN_ATTEMPT': '1'}), \
                patch('quote_recovery.require_dispatch'), patch('quote_recovery.verify_build_candidate'), \
                patch('quote_recovery.require_transient_quote_failure') as failure:
            result = resolve(Path('.'), SOURCE, MAIN, '7', metadata)
            self.assertEqual(result, dict(original_controller=MAIN, same_controller_retry='true',
                                          core_controller=MAIN, core_run=7,
                                          production_id='71', core_id='72', quote_id='73', core_receipt_id='74'))
            self.assertEqual(failure.call_args.args[1]['name'], 'build / ' + QUOTE)

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
        run = self.recovery_run(8, prior, dict(admit_hosted='success', publish='skipped',
                                              **{RESERVE: 'success', WALLET: 'failure'}), ())
        metadata = self.recovery_metadata(run, reservation=reservation)
        safe = b'Hosted lifecycle failed at networkTargets, claim state no-POST'
        preclaim = b'CORE_STAGE sender passed\nHosted lifecycle failed at preclaim_readiness, claim state no-POST'
        budget = b'CORE_STAGE sender passed\nHosted lifecycle failed at funding_time_budget, claim state no-POST'
        saving = b'Hosted lifecycle failed at torModeSave, claim state no-POST'
        reopening = b'CORE_STAGE torModeSave passed\nHosted lifecycle failed at torModeResume, claim state no-POST'
        receipt_probe = b'Hosted lifecycle failed at receiptProbe, claim state no-POST'
        rpc_probe = b'2026-01-01T12:00:00Z Hosted lifecycle failed at rpcProbe, claim state no-POST'
        for log, error in ((safe, None), (preclaim, None), (budget, None), (saving, None), (reopening, None),
                           (receipt_probe, None), (rpc_probe, None),
                           (rpc_probe.replace(b'no-POST', b'uncertain'), 'did not prove'),
                           (rpc_probe + b'\nCORE_STAGE recipient passed', 'did not prove'),
                           (receipt_probe + b'\n' + rpc_probe, 'did not prove'),
                           (b'no-POST', 'did not prove'),
                           (saving.replace(b'no-POST', b'uncertain'), 'did not prove'),
                           (saving + b'\nCORE_STAGE sender passed', 'did not prove'),
                           (reopening + b'\nCORE_STAGE fund passed', 'did not prove'),
                           (saving + b'\n' + reopening, 'did not prove'),
                           (budget.replace(b'funding_time_budget', b'funding_setup'), 'did not prove'),
                           (budget.replace(b'no-POST', b'request-starting'), 'did not prove'),
                           (budget + b'\nHosted lifecycle failed at funding_setup, claim state pending', 'did not prove'),
                           (preclaim.replace(b'no-POST', b'uncertain'), 'did not prove'),
                           (preclaim + b'\nCORE_STAGE fund passed', 'did not prove'),
                           (safe + b'\nCORE_STAGE sender passed', 'did not prove')):
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
        with patch('quote_recovery.git', return_value=prior.encode()), \
                patch('release_inputs.version_code', return_value=42), patch('hosted_claim.verify'), \
                patch('quote_recovery.subprocess.check_output', return_value=preclaim):
            self.assertEqual(unused_reservation(Path('.'), SOURCE, MAIN, '8', prior, '8', metadata), reservation)
        next(job for job in run[1] if job['name'] == 'build / ' + WALLET)['conclusion'] = 'success'
        with patch('quote_recovery.git', return_value=prior.encode()), \
                patch('quote_recovery.verify_delta'), patch('quote_recovery.subprocess.check_output') as log:
            with self.assertRaisesRegex(ValueError, 'required boundary: build / hosted_core / wallet'):
                unused_reservation(Path('.'), SOURCE, MAIN, '7', OLD, '8', metadata)
            log.assert_not_called()


    def test_skipped_wallet_reuses_original_builds_and_passing_quotes(self):
        states = dict(BUILD_JOBS, publish='skipped', hosted_core='skipped', **{QUOTE: 'success'})
        names = (*BUILD_ARTIFACTS, 'hosted-quote-receipt')
        for outcomes, error in (
            (states, None),
            (dict(states, **{RESERVE: 'success'}), 'previous wallet'),
            (dict(states, publish='success'), 'publish'),
        ):
            run = self.recovery_run(7, OLD, outcomes, names, conclusion='success')
            metadata = self.recovery_metadata(run, refs=[])
            with self.subTest(error=error), patch('quote_recovery.require_dispatch'), \
                patch('quote_recovery.verify_delta'), patch('release_inputs.version_code', return_value=42):
                if error:
                    with self.assertRaisesRegex(ValueError, error):
                        resolve(Path('.'), SOURCE, MAIN, '7', metadata)
                else:
                    result = resolve(Path('.'), SOURCE, MAIN, '7', metadata)
                    self.assertEqual(result['run_missing_core'], 'true')
                    self.assertEqual(result['quote_receipt_id'], '74')
                    self.assertEqual(result['quote_run'], 7)
                    self.assertNotIn('core_receipt_id', result)


    def test_funded_recovery_binds_original_jobs_artifacts_and_reservation(self):
        states = dict(BUILD_JOBS, publish='skipped',
                      **{RESERVE: 'success', WALLET: 'failure', PREPARE: 'success', QUOTE: 'success'})
        names = (*BUILD_ARTIFACTS, 'hosted-quote-receipt', 'core-direct-candidate')
        claim = dict(ref='refs/heads/testnet-claims/release-42', object={'sha': 'd' * 40})

        def run_case(outcomes, available, reservations, *, signing=False, quote_run='', **changes):
            run, jobs, artifacts = self.recovery_run(7, OLD, outcomes, available)
            metadata = self.recovery_metadata((dict(run, **changes), jobs, artifacts), refs=reservations)
            return resolve(Path('.'), SOURCE, MAIN, '7', metadata, quote_run, signing=signing)

        with patch('quote_recovery.require_dispatch'), patch('quote_recovery.verify_delta') as delta, \
                patch('funded_reuse.select', return_value={'core': {}, 'origin': {}, 'fixture': {}}) as select, \
                patch('release_inputs.version_code', return_value=42), patch('hosted_claim.verify') as verify:
            expected = dict(original_controller=OLD, reuse_funded='true', production_id='71',
                            core_id='72', quote_id='73', quote_receipt_id='74', quote_controller=OLD, quote_run=7,
                            prepared_id='75')
            self.assertEqual(run_case(states, names, [claim]), expected)
            self.assertEqual(run_case(states, names, [claim], signing=True), expected)
            delta.assert_called_with(Path('.'), SOURCE, OLD, MAIN, funded=True)
            self.assertEqual(select.call_count, 2)
            self.assertEqual(verify.call_args.args[0], dict(source=SOURCE, controller=OLD, version=42, run=7,
                                                            rehearsal=False))

            idle = dict(BUILD_JOBS, **{QUOTE: 'success'})
            recovered = run_case(idle, names[:-1], [], conclusion='cancelled')
            self.assertEqual(recovered, {key: value for key, value in expected.items() if key != 'prepared_id'})
            failed_quote = dict(states, **{QUOTE: 'failure'})
            without_quote = (*BUILD_ARTIFACTS, 'core-direct-candidate')
            recovered = run_case(failed_quote, without_quote, [claim])
            quote_receipt_keys = {'quote_receipt_id', 'quote_controller', 'quote_run'}
            self.assertEqual(recovered, {
                key: '74' if key == 'prepared_id' else value
                for key, value in expected.items() if key not in quote_receipt_keys
            })
            selected_quote = dict(quote_receipt_id='81', quote_run=8, quote_controller='e' * 40)
            with patch('quote_recovery.resolve_quotes', return_value=selected_quote) as reuse_quotes:
                failed_upgrade = dict(failed_quote, **{RESERVE: 'skipped'})
                result = run_case(failed_upgrade, without_quote, [], quote_run='8')
                self.assertEqual(result, dict(expected, **selected_quote) | {'prepared_id': '74'})
                self.assertTrue(reuse_quotes.call_args.kwargs['funded'])

            for outcomes, available, reservations, changes, error in (
                (states, names, [claim], {'status': 'in_progress'}, 'trusted custom run'),
                (dict(idle, **{WALLET: None}), names, [], {'conclusion': 'cancelled'}, 'wallet'),
                (dict(idle, publish='success'), names, [], {'conclusion': 'cancelled'}, 'publish'),
                (dict(idle, **{QUOTE: 'failure'}), names, [], {'conclusion': 'cancelled'},
                 'successful hosted-quote-receipt'),
                (states, names, [], {}, 'original protected funding reservation'),
                (states, names, [claim, dict(claim, ref=claim['ref'] + '-preclaim-recovery')], {},
                 'linked funding reservation'),
                (states, (*names, 'hosted-core-receipt'), [claim], {}, 'previous wallet receipt'),
            ):
                with self.subTest(error=error), self.assertRaisesRegex(ValueError, error):
                    run_case(outcomes, available, reservations, **changes)

            select.return_value = None
            with self.assertRaisesRegex(ValueError, 'qualified funded reuse'):
                run_case(idle, names, [], conclusion='cancelled')

    def test_funded_recovery_delta_rejects_quote_runner_changes(self):
        for path, allowed in ((b'.private/funded_reuse.py\0', True),
                              (b'.private/tor_resident_ui_driver.py\0', False)):
            with self.subTest(path=path), patch('quote_recovery.verify_build_candidate'), \
                    patch('quote_recovery.git', side_effect=[OLD.encode(), path]):
                if allowed:
                    verify_delta(Path('.'), SOURCE, OLD, MAIN, funded=True)
                else:
                    with self.assertRaisesRegex(ValueError, 'outside funded recovery paths'):
                        verify_delta(Path('.'), SOURCE, OLD, MAIN, funded=True)


    def test_passing_quotes_reuse_requires_unchanged_runner_and_no_prior_attestation(self):
        quote_head = 'd' * 40
        run, jobs, receipts = self.recovery_run(8, quote_head, dict(admit_hosted='success', publish='failure',
                                             **{QUOTE: 'success'}), ('hosted-quote-receipt',))
        for delta, artifacts, error in (
            (b'.private/publish.py\0', receipts, None),
            (b'.private/tor_resident_ui_driver.py\0', receipts, 'execution inputs changed'),
            (b'.private/publish.py\0', [*receipts, {'name': 'release-attestation-1'}], 'no publication attestation'),
        ):
            metadata = self.recovery_metadata((run, jobs, artifacts))
            with self.subTest(delta=delta, error=error), patch('quote_recovery.verify_delta'), \
                patch('quote_recovery.git', side_effect=[quote_head.encode(), delta]):
                if error:
                    with self.assertRaisesRegex(ValueError, error):
                        resolve_quotes(Path('.'), SOURCE, MAIN, OLD, '8', metadata)
                else:
                    self.assertEqual(resolve_quotes(Path('.'), SOURCE, MAIN, OLD, '8', metadata),
                                     {'quote_receipt_id': '81', 'quote_controller': quote_head, 'quote_run': 8})

    def test_completed_wallet_or_upgrade_recovery_keeps_independent_apk_and_quote_producers(self):
        repair = 'd' * 40
        original_jobs = {
            'admit_hosted': 'success', 'unsigned': 'success', 'emulator': 'success',
            'testnet_apk / testnet': 'success', 'hosted_core / prepare-direct': 'success',
            'hosted_core / reserve': 'success', 'hosted_core / wallet': 'failure',
            'hosted_quotes / quote-cycles': 'success', 'publish': 'skipped',
        }
        original_names = (*tuple(ARTIFACTS.values())[:3], 'hosted-quote-receipt', 'core-direct-candidate')
        original = self.recovery_run(7, OLD, original_jobs, original_names)
        for funded, keep_prepared in ((False, False), (True, False), (True, True)):
            recovery_jobs = {
                'admit_hosted': 'success', 'unsigned': 'skipped', 'emulator': 'skipped',
                'hosted_core / prepare-direct': 'skipped' if keep_prepared else 'success',
                'hosted_core / reserve': 'skipped' if funded else 'success',
                'hosted_core / wallet': 'success', 'publish': 'failure',
            }
            receipt = 'hosted-upgrade-receipt' if funded else 'hosted-core-receipt'
            names = (receipt,) if keep_prepared else (receipt, 'core-direct-candidate')
            later = self.recovery_run(8, repair, recovery_jobs, names)
            with self.subTest(funded=funded, keep_prepared=keep_prepared), \
                    patch('quote_recovery.require_dispatch') as dispatch, patch('quote_recovery.verify_delta'), \
                    patch('quote_recovery.verify_receipt_delta'), patch('quote_recovery.unused_reservation') as reserve, \
                    patch('funded_reuse.select') as select:
                meta = self.recovery_metadata(original, later)
                result = resolve(Path('.'), SOURCE, MAIN, '7', meta, core_run='8')
                self.assertEqual(result['production_id'], '71')
                self.assertEqual(result['core_id'], '72')
                self.assertEqual(result['quote_run'], 7)
                self.assertEqual(result['quote_receipt_id'], '74')
                self.assertEqual(result['core_run'], 8)
                self.assertEqual(result['core_controller'], repair)
                self.assertEqual(result['core_receipt_id'], '81')
                self.assertEqual(result['prepared_id'], '75' if keep_prepared else '82')
                self.assertEqual(result['prepared_run'], 7 if keep_prepared else 8)
                self.assertEqual(result.get('reuse_funded'), 'true' if funded else None)
                self.assertNotIn('run_missing_core', result)
                self.assertNotIn('same_controller_retry', result)
                reserve.assert_not_called()
                select.assert_not_called()
                self.assertEqual(dispatch.call_args.kwargs['core_run'], '8')
                for change, error in (
                    ('wrong attempt', 'Recovery job run, attempt'),
                    ('wrong wallet job', 'Recovery job run, attempt'),
                    ('expired wallet receipt', 'exact trusted run and role'),
                    ('wrong prepared artifact', 'exact trusted run and role'),
                    ('late preparation archive', 'successful core-direct-candidate'),
                    ('publication attestation', 'no publication attestation'),
                ):
                    changed_run, changed_jobs, changed_artifacts = self.recovery_run(8, repair, recovery_jobs, names)
                    if change == 'wrong attempt':
                        next(job for job in changed_jobs if job['name'].endswith('/ wallet'))['run_attempt'] = 2
                    elif change == 'wrong wallet job':
                        next(job for job in changed_jobs if job['name'].endswith('/ wallet'))['run_id'] = 7
                    elif change == 'expired wallet receipt':
                        changed_artifacts[0]['expired'] = True
                    elif change in ('wrong prepared artifact', 'late preparation archive'):
                        if keep_prepared:
                            continue
                        if change == 'wrong prepared artifact':
                            changed_artifacts[1]['workflow_run']['head_sha'] = OLD
                        else:
                            changed_artifacts[1]['created_at'] = '2026-11-22T08:10:00Z'
                    else:
                        changed_artifacts.append({'name': 'release-attestation-1'})
                    with self.subTest(change=change), self.assertRaisesRegex(ValueError, error):
                        resolve(Path('.'), SOURCE, MAIN, '7',
                                self.recovery_metadata(original, (changed_run, changed_jobs, changed_artifacts)),
                                core_run='8')
                with self.assertRaisesRegex(ValueError, 'failed pre-claim run'):
                    resolve(Path('.'), SOURCE, MAIN, '7', meta, core_run='8', preclaim_run='7')
                with self.assertRaisesRegex(ValueError, 'live artifact for recovery: validated-unsigned-apk'):
                    resolve(Path('.'), SOURCE, MAIN, '8', self.recovery_metadata(later))

    def test_reserved_same_controller_quote_receipt_survives_signer_failure(self):
        original_jobs = {
            'admit_hosted': 'success', 'unsigned': 'success', 'emulator': 'success',
            'testnet_apk / testnet': 'success', 'hosted_core / wallet': 'success',
            'hosted_quotes / quote-cycles': 'failure', 'publish': 'skipped',
        }
        original = self.recovery_run(7, OLD, original_jobs, tuple(ARTIFACTS.values()))
        retry = self.recovery_run(8, OLD, {
            'admit_hosted': 'success', 'reserve_quote_retry': 'success',
            'hosted_quotes / quote-cycles': 'success', 'publish': 'failure',
        }, ('hosted-quote-receipt',))
        for signer, attempt, selector in ((OLD, '1', '8'), (MAIN, '1', '8'), (OLD, '2', '')):
            with self.subTest(signer=signer, attempt=attempt), \
                    patch_environment({'GITHUB_RUN_ATTEMPT': attempt, 'GITHUB_RUN_ID': '8' if not selector else '9'}), \
                    patch('quote_recovery.require_dispatch'), patch('quote_recovery.verify_build_candidate'), \
                    patch('quote_recovery.verify_delta'), patch('quote_recovery.verify_receipt_delta') as delta, \
                    patch('quote_recovery.require_transient_quote_failure') as transient, \
                    patch('quote_recovery.verify_quote_reservation') as reservation:
                meta = self.recovery_metadata(original, retry)
                outputs = resolve(Path('.'), SOURCE, signer, '7', meta, selector, signing=attempt != '1')
                self.assertEqual(outputs['quote_run'], 8)
                self.assertEqual(outputs['quote_receipt_id'], '81')
                self.assertNotIn('same_controller_retry', outputs)
                self.assertNotIn('run_missing_core', outputs)
                transient.assert_called_once()
                reservation.assert_called_once_with(SOURCE, OLD, '7', '8', meta)
                delta.assert_called_once_with(Path('.'), OLD, signer, 'Quote')
                if not selector:
                    self.assertIn('/actions/runs/8/attempts/1/jobs', [call.args[0] for call in meta.pages.call_args_list])
                reservation.side_effect = ValueError('Protected reservation belongs to different inputs')
                with self.assertRaisesRegex(ValueError, 'different inputs'):
                    resolve(Path('.'), SOURCE, signer, '7', meta, selector, signing=attempt != '1')
                reservation.side_effect = None
                transient.side_effect = ValueError('Original quote job did not prove a transient failure')
                with self.assertRaisesRegex(ValueError, 'transient failure'):
                    resolve(Path('.'), SOURCE, signer, '7', meta, selector, signing=attempt != '1')

    def test_cancelled_original_reuses_passing_acceptance_without_funded_eligibility(self):
        states = {
            'admit_hosted': 'success', 'unsigned': 'success', 'emulator': 'success',
            'testnet_apk / testnet': 'success', 'hosted_core / wallet': 'success',
            'hosted_core / reserve': 'success', 'hosted_core / prepare-direct': 'success',
            'hosted_quotes / quote-cycles': 'success', 'publish': 'cancelled',
        }
        names = (*ARTIFACTS.values(), 'hosted-quote-receipt', 'core-direct-candidate')
        for outcome, artifact_names, error in (
            (states, names, None),
            ({key: value for key, value in states.items() if key != 'publish'}, names, None),
            (dict(states, publish=None), names, 'build / publish'),
            (dict(states, **{QUOTE: 'cancelled'}), (*names[:4], names[-1]), None),
            ({key: value for key, value in states.items() if key != QUOTE}, (*names[:4], names[-1]), None),
            (states, (*names[:4], names[-1]), 'live artifact for recovery: hosted-quote-receipt'),
            (states, (*names, 'release-attestation-1'), 'retained publication attestation'),
        ):
            original = self.recovery_run(7, OLD, outcome, artifact_names, conclusion='cancelled')
            with self.subTest(error=error), patch('quote_recovery.require_dispatch'), \
                    patch('quote_recovery.verify_delta'), patch('funded_reuse.select', return_value=None) as select:
                meta = self.recovery_metadata(original)
                if error:
                    with self.assertRaisesRegex(ValueError, error):
                        resolve(Path('.'), SOURCE, MAIN, '7', meta)
                else:
                    outputs = resolve(Path('.'), SOURCE, MAIN, '7', meta)
                    self.assertEqual(outputs['core_receipt_id'], '74')
                    self.assertEqual(outputs.get('quote_receipt_id'), '75' if outcome.get(QUOTE) == 'success' else None)
                    self.assertNotIn('run_missing_core', outputs)
                    self.assertNotIn('reuse_funded', outputs)
                select.assert_not_called()

    def test_linked_quote_survives_preparation_failure_and_later_wallet_signer_failure(self):
        quote_controller, wallet_controller = 'd' * 40, 'e' * 40
        reservation = 'f' * 40
        original = self.recovery_run(7, OLD, dict(BUILD_JOBS, publish='skipped', **{
            QUOTE: 'failure', WALLET: 'failure', PREPARE: 'success', RESERVE: 'success',
        }), BUILD_ARTIFACTS)
        failed_rerun = self.rerun(original, dict(unsigned='failure', publish='skipped', **{WALLET: 'skipped'}))
        reserve = next(job for job in original[1] if job['name'] == 'build / ' + RESERVE)
        failed_rerun[1].append(dict(reserve, id=reserve['id'] + 40, run_attempt=2))
        quote = self.recovery_run(8, quote_controller, dict(admit_hosted='success', publish='skipped', **{
            LINKED_QUOTE: 'success', PREPARE: 'failure', WALLET: 'skipped', RESERVE: 'skipped',
        }), ('hosted-quote-receipt',))
        wallet = self.recovery_run(9, wallet_controller, dict(admit_hosted='success', publish='failure', **{
            WALLET: 'success', PREPARE: 'success', RESERVE: 'success',
        }), ('hosted-core-receipt', 'core-direct-candidate'))
        metadata = self.recovery_metadata(original, failed_rerun, quote, wallet, reservation=reservation)
        preparation_fix = b'.private/prepare_direct_core.py\0'
        with patch('quote_recovery.require_dispatch'), patch('quote_recovery.verify_delta'), \
                patch('release_inputs.version_code', return_value=42), \
                patch('hosted_claim.verify') as verify, \
                patch('quote_recovery.subprocess.check_output',
                      return_value=b'Hosted lifecycle failed at networkTargets, claim state no-POST') as no_post:
            with patch('quote_recovery.git', side_effect=[OLD.encode(), quote_controller.encode(), preparation_fix]):
                result = resolve(Path('.'), SOURCE, wallet_controller, '7', metadata, quote_run='8', preclaim_run='7')
            self.assertEqual(result['quote_receipt_id'], '81')
            self.assertEqual(result['previous_reservation'], reservation)
            self.assertEqual(result['run_missing_core'], 'true')
            verify.assert_called_once_with(
                dict(source=SOURCE, controller=OLD, version=42, run=7, rehearsal=False), reservation, metadata)
            original_wallet = next(job for job in original[1] if job['name'] == 'build / ' + WALLET)
            self.assertTrue(no_post.call_args.args[0][-1].endswith(f'/actions/jobs/{original_wallet["id"]}/logs'))
            verify.reset_mock()
            no_post.reset_mock()
            with patch('quote_recovery.git', side_effect=[wallet_controller.encode(), b'.private/publish.py\0',
                                                           quote_controller.encode(), preparation_fix]):
                result = resolve(Path('.'), SOURCE, MAIN, '7', metadata, quote_run='8', core_run='9')
            self.assertEqual((result['quote_receipt_id'], result['core_receipt_id']), ('81', '91'))
            self.assertEqual((result['quote_controller'], result['core_controller']),
                             (quote_controller, wallet_controller))
            self.assertNotIn('run_missing_core', result)
            verify.assert_not_called()
            no_post.assert_not_called()

    def test_passing_wallet_survives_failed_sibling_quote_with_claim_preserved(self):
        wallet_controller = 'd' * 40
        original = self.recovery_run(7, OLD, dict(BUILD_JOBS, publish='skipped', **{
            QUOTE: 'failure', WALLET: 'skipped', PREPARE: 'failure', RESERVE: 'skipped',
        }), BUILD_ARTIFACTS)
        wallet = self.recovery_run(8, wallet_controller, dict(admit_hosted='success', publish='skipped', **{
            QUOTE: 'failure', WALLET: 'success', PREPARE: 'success', RESERVE: 'success',
        }), ('hosted-core-receipt', 'core-direct-candidate'))
        reservation = {'ref': 'refs/heads/testnet-claims/release-42'}
        metadata = self.recovery_metadata(original, wallet, refs=[reservation])
        with patch('quote_recovery.require_dispatch'), patch('quote_recovery.verify_delta'), \
                patch('funded_reuse.select', return_value=None), patch('release_inputs.version_code', return_value=42), \
                patch('quote_recovery.git', side_effect=[wallet_controller.encode(), b'.private/hosted_quote_probe.py\0']):
            result = resolve(Path('.'), SOURCE, MAIN, '7', metadata, core_run='8')
            self.assertEqual(result['core_receipt_id'], '81')
            self.assertNotIn('quote_receipt_id', result)
            self.assertNotIn('run_missing_core', result)
            with self.assertRaisesRegex(ValueError, 'already has a protected funding reservation'):
                resolve(Path('.'), SOURCE, MAIN, '7', metadata)

    def test_preclaim_proof_requires_no_later_funding_execution_or_publication(self):
        original = self.recovery_run(7, OLD, dict(BUILD_JOBS, publish='skipped',
                                                 **{WALLET: 'failure', RESERVE: 'success'}), BUILD_ARTIFACTS)
        reservation = 'f' * 40
        reserve = next(job for job in original[1] if job['name'] == 'build / ' + RESERVE)
        copied = dict(reserve, id=reserve['id'] + 40, run_attempt=2)
        for states, extras, archives, error in (
            ({'unsigned': 'failure'}, [], [], None),
            ({'unsigned': 'failure', RESERVE: 'skipped'}, [], [], None),
            ({WALLET: 'success'}, [copied], [], 'later attempt executed the prior wallet'),
            ({WALLET: 'failure'}, [], [], 'later attempt executed the prior wallet'),
            ({RESERVE: 'success'}, [], [], 'later attempt executed a new funding reservation'),
            ({RESERVE: 'failure'}, [], [], 'later attempt executed a new funding reservation'),
            ({}, [dict(copied, runner_id=999)], [], 'later attempt executed a new funding reservation'),
            ({}, [dict(copied, id=reserve['id'])], [], 'later attempt executed a new funding reservation'),
            ({'publish': 'success'}, [], [], 'required boundary: build / publish'),
            ({}, [], [{'name': 'release-attestation-2'}], 'no publication attestation'),
            ({}, [], [{'name': 'hosted-core-receipt'}], 'without a wallet receipt'),
        ):
            later, jobs, artifacts = self.rerun(original, states)
            metadata = self.recovery_metadata(original, (later, jobs + extras, artifacts + archives),
                                              reservation=reservation)
            with self.subTest(error=error), patch('quote_recovery.git', return_value=OLD.encode()), \
                    patch('release_inputs.version_code', return_value=42), patch('hosted_claim.verify') as verify, \
                    patch('quote_recovery.subprocess.check_output',
                          return_value=b'Hosted lifecycle failed at networkTargets, claim state no-POST') as no_post:
                if error:
                    with self.assertRaisesRegex(ValueError, error):
                        unused_reservation(Path('.'), SOURCE, MAIN, '7', OLD, '7', metadata)
                    no_post.assert_not_called()
                    verify.assert_not_called()
                else:
                    self.assertEqual(unused_reservation(Path('.'), SOURCE, MAIN, '7', OLD, '7', metadata),
                                     reservation)
                    no_post.assert_called_once()
                    verify.assert_called_once()

    def test_passing_quote_survives_failed_sibling_installed_upgrade(self):
        quote_controller = 'd' * 40
        original = self.recovery_run(7, OLD, dict(BUILD_JOBS, publish='skipped', **{
            QUOTE: 'failure', WALLET: 'failure', PREPARE: 'success', RESERVE: 'skipped',
        }), (*BUILD_ARTIFACTS, 'core-direct-candidate'))
        quote = self.recovery_run(8, quote_controller, dict(admit_hosted='success', publish='skipped', **{
            QUOTE: 'success', WALLET: 'failure', PREPARE: 'skipped', RESERVE: 'skipped',
        }), ('hosted-quote-receipt',))
        metadata = self.recovery_metadata(original, quote, refs=[])
        with patch('quote_recovery.require_dispatch'), patch('quote_recovery.verify_delta'), \
                patch('funded_reuse.select', return_value={'core': {}}), \
                patch('release_inputs.version_code', return_value=42), \
                patch('quote_recovery.git', side_effect=[quote_controller.encode(), b'.private/publish.py\0']):
            result = resolve(Path('.'), SOURCE, MAIN, '7', metadata, quote_run='8')
            self.assertEqual(result['quote_receipt_id'], '81')
            self.assertEqual(result['reuse_funded'], 'true')
            self.assertEqual(result['prepared_id'], '74')
            self.assertNotIn('core_receipt_id', result)
            self.assertNotIn('run_missing_core', result)

    def test_failed_later_original_attempt_keeps_original_build_and_receipts(self):
        original = self.recovery_run(7, OLD, dict(BUILD_JOBS, publish='failure', **{
            WALLET: 'success', PREPARE: 'success', RESERVE: 'success', QUOTE: 'success',
        }), (*ARTIFACTS.values(), 'hosted-quote-receipt', 'core-direct-candidate'))
        case = self.rerun(original, dict(BUILD_JOBS, unsigned='failure', publish='skipped'))
        metadata = self.recovery_metadata(original, case)
        with patch('quote_recovery.require_dispatch'), patch('quote_recovery.verify_build_candidate'), \
                patch('quote_recovery.verify_delta'), patch('funded_reuse.select') as funded:
            for controller in (OLD, MAIN):
                result = resolve(Path('.'), SOURCE, controller, '7', metadata)
                self.assertEqual((result['production_id'], result['core_receipt_id'], result['quote_receipt_id']),
                                 ('71', '74', '75'))
                self.assertNotIn('run_missing_core', result)
            funded.assert_not_called()
            self.assertIn('/actions/runs/7/attempts/1/jobs', [call.args[0] for call in metadata.pages.call_args_list])
            published = self.rerun(original, {'publish': 'success'})
            with self.assertRaisesRegex(ValueError, 'build / publish'):
                resolve(Path('.'), SOURCE, MAIN, '7', self.recovery_metadata(original, published))
            attested = (*case[:2], [*case[2], {'name': 'release-attestation-2'}])
            with self.assertRaisesRegex(ValueError, 'retained publication attestation'):
                resolve(Path('.'), SOURCE, MAIN, '7', self.recovery_metadata(original, attested))

    def test_later_original_build_attempt_cannot_authorize_fresh_wallet_acceptance(self):
        original = self.recovery_run(7, OLD, dict(BUILD_JOBS, unsigned='failure', publish='skipped',
                                                  hosted_core='skipped'), ())
        later = self.rerun(original, dict(BUILD_JOBS, publish='skipped', hosted_core='skipped'))
        _, _, artifacts = self.recovery_run(7, OLD, {}, BUILD_ARTIFACTS)
        for artifact in artifacts:
            artifact['created_at'] = '2026-11-22T08:10:00Z'
        metadata = self.recovery_metadata(original, (*later[:2], artifacts), refs=[])
        with patch('quote_recovery.require_dispatch'), patch('quote_recovery.verify_delta'), \
                patch('release_inputs.version_code', return_value=42):
            with self.assertRaisesRegex(ValueError, 'Fresh wallet acceptance requires the original build attempt'):
                resolve(Path('.'), SOURCE, MAIN, '7', metadata)
        self.verify_receipts.assert_not_called()

    def test_quote_archive_must_be_uploaded_by_exact_eligible_execution(self):
        quote_controller = 'd' * 40
        build = self.recovery_run(7, OLD, BUILD_JOBS, BUILD_ARTIFACTS)
        first = self.recovery_run(8, quote_controller, dict(admit_hosted='success', publish='failure',
                                  **{QUOTE: 'success'}), ('hosted-quote-receipt',))
        artifact = first[2][0]

        def identity(row):
            return {key: row[key] for key in ('id', 'digest', 'size_in_bytes')}

        receipt = dict(schema=POLICY_VERSION, evidence_class='real-app-production-quotes', passed=True,
                       build_source_sha=SOURCE, candidate_version_code=42, input_fingerprint='fixture-inputs',
                       executed_apk_sha256='b' * 64, quote_requests=3, quote_reviews=3, dismissals=3,
                       tor_enabled=False, funds_transferred=False, personal_wallet_access=False,
                       recipient_network_mismatch_blocked=True, corrected_without_leaving_form=True,
                       independent_comparisons=1, production_artifact=identity(build[2][0]),
                       producer=identity(build[2][2]), workflow_run_id=8, artifact_run_id=7,
                       quote_controller_sha=quote_controller)
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, 'w') as bundle:
            bundle.writestr('hosted-quotes.json', json.dumps(receipt))
        archive = buffer.getvalue()
        artifact.update(size_in_bytes=len(archive), digest='sha256:' + hashlib.sha256(archive).hexdigest())
        second = self.rerun(first, dict(admit_hosted='success', publish='failure'))
        quote_job = next(job for job in first[1] if job['name'].endswith('/ quote-cycles'))
        copied = dict(quote_job, id=quote_job['id'] + 40, run_attempt=2)
        cases = (
            ('original plus copied success', '07:10', copied, None, None),
            ('later upload with copied success', '08:10', copied, None, 'successful hosted-quote-receipt'),
            ('copied success mismatch', '07:10', dict(copied, runner_id=999), None, 'Copied hosted-quote-receipt'),
            ('missing job timestamps', '07:10', copied, {'completed_at': None}, 'Missing GitHub'),
            ('missing upload timestamp', None, copied, None, 'Missing GitHub'),
            ('another successful producer in upload window', '07:10', copied, {}, 'successful hosted-quote-receipt'),
            ('later successful execution', '08:10',
             dict(copied, started_at='2026-11-22T08:01:00Z', completed_at='2026-11-22T08:20:00Z'), None, None),
        )
        with patch('quote_recovery.verify_delta'), patch('quote_recovery.verify_receipt_delta'), \
                patch.object(hosted_release, 'version_code', return_value=42), \
                patch.object(hosted_release, 'input_fingerprint', return_value='fixture-inputs'), \
                patch.object(hosted_release.subprocess, 'check_output', return_value=archive) as download:
            for label, uploaded, later_job, first_changes, error in cases:
                with self.subTest(label=label):
                    asset = dict(artifact, created_at=f'2026-11-22T{uploaded}:00Z' if uploaded else None)
                    jobs = [dict(job, **first_changes) if job is quote_job and first_changes else job
                            for job in first[1]]
                    if first_changes == {}:
                        jobs.append(dict(quote_job, id=quote_job['id'] + 20, name='build / ' + LINKED_QUOTE))
                    metadata = self.recovery_metadata(build, (first[0], jobs, [asset]),
                                                      (second[0], [*second[1], later_job], [asset]))
                    download.reset_mock()
                    if error:
                        with self.assertRaisesRegex(ValueError, error):
                            resolve_quotes(Path('.'), SOURCE, MAIN, OLD, '8', metadata, original_run='7')
                        download.assert_not_called()
                    else:
                        selected = resolve_quotes(Path('.'), SOURCE, MAIN, OLD, '8', metadata, original_run='7')
                        outputs = dict(original_controller=OLD, production_id='71', quote_id='73', **selected)
                        VERIFY_RECOVERED_RECEIPTS(Path('.'), SOURCE, MAIN, '7', outputs, metadata,
                                                 original_prepared=None, original_reserve=None)
                        self.assertEqual(outputs['quote_receipt_id'], '81')
                        self.assertIn('quote_receipt_sha256', outputs)
                        download.assert_called_once()

    @staticmethod
    def rerun(first, states):
        run = dict(first[0], run_attempt=2, run_started_at='2026-11-22T08:00:00Z')
        jobs = [
            dict(job, run_attempt=2, id=job['id'] + 50, started_at='2026-11-22T08:01:00Z',
                 completed_at='2026-11-22T08:20:00Z')
            for job in QuoteRecoveryTests.recovery_run(run['id'], run['head_sha'], states, ())[1]
        ]
        return run, jobs, first[2]

    @staticmethod
    def recovery_run(run_id, controller, states, names, *, conclusion='failure'):
        run = dict(id=run_id, path=WORKFLOW, event='workflow_dispatch', head_branch='main',
                   head_repository={'full_name': REPOSITORY}, actor={'id': OPERATOR_ID},
                   head_sha=controller, status='completed', conclusion=conclusion, run_attempt=1,
                   run_started_at='2026-11-22T07:00:00Z')
        jobs = [
            dict(id=run_id * 100 + index, name='build / ' + name.removeprefix('build / '),
                 conclusion=result, status='completed', run_id=run_id, head_sha=controller, run_attempt=1,
                 started_at='2026-11-22T07:01:00Z', completed_at='2026-11-22T07:20:00Z',
                 runner_id=index, steps=[{'name': 'Fixture execution'}])
            for index, (name, result) in enumerate(states.items(), 1)
        ]
        artifacts = [dict(id=run_id * 10 + index, name=name, expired=False, size_in_bytes=100,
                          digest='sha256:' + '0' * 64, workflow_run=dict(id=run_id, head_sha=controller),
                          created_at='2026-11-22T07:10:00Z')
                     for index, name in enumerate(names, 1)]
        return run, jobs, artifacts

    @staticmethod
    def recovery_metadata(*runs, refs=None, reservation=None):
        meta = Mock(gh='gh-offline-fixture', repo='repos/fixture')
        here = Path(__file__).parent
        fixture = json.loads((here / 'hosted-fixture.json').read_text())
        record = json.loads((here / 'release-evidence' / (fixture['release'] + '.json')).read_text())
        endpoints = public_baseline_metadata(fixture, record['apk_sha256'])
        pages = {}
        for run, jobs, artifacts in runs:
            path = f'/actions/runs/{run["id"]}'
            endpoints.update({path: run, path + f'/attempts/{run["run_attempt"]}': run})
            endpoints.update({f'/actions/artifacts/{row["id"]}': row for row in artifacts if 'id' in row})
            pages.update({path + f'/attempts/{run["run_attempt"]}/jobs': jobs, path + '/artifacts': artifacts})
        def api(path):
            if refs is not None and path.startswith('/git/matching-refs/heads/testnet-claims/'):
                return refs
            if reservation is not None and path.startswith('/git/ref/heads/testnet-claims/'):
                return {'object': {'sha': reservation}}
            return endpoints[path]

        meta.api.side_effect = api
        meta.pages.side_effect = lambda path, key: pages[path]
        return meta

    def test_signer_reaches_recovery_admission_before_reading_any_key(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / '.private').mkdir()
            (root / '.private/release-policy.json').write_text('{"publication_enabled": true}')
            (root / 'artifact').mkdir()
            (root / 'artifact/proof.json').write_text('{}')
            env = dict(GITHUB_REPOSITORY=REPOSITORY, SOURCE_SHA=SOURCE, CONTROLLER_SHA=MAIN,
                       RECOVER_RUN='7', HOSTED_ACCEPTANCE='true')
            with patch_environment(env):
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
            with patch_environment({'GITHUB_RUN_ID': '8'}), patch.object(hosted_release, 'ROOT', root), \
                    patch.object(hosted_release, 'version_code', return_value=42), \
                    patch.object(hosted_release, 'artifact', return_value=original_apk):
                with self.assertRaisesRegex(ValueError, 'different execution inputs'):
                    hosted_release.read_receipts(root, proof={'workflow_run_id': '7', 'versionCode': 42},
                                                 source=SOURCE, controller=MAIN, production_id='5', core_id='6',
                                                 quote_id='9', recovery={'run': 7, 'original_controller': OLD})

    def test_recovery_rejects_product_and_wallet_harness_changes(self):
        for changed in (b'gradle.properties\0', b'.private/core_testnet_driver.py\0'):
            with patch('quote_recovery.verify_build_candidate'), \
                    patch('acceptance_policy.needs_live_tor_probe', return_value=True), \
                    patch('quote_recovery.git', side_effect=[OLD.encode(), changed]):
                with self.assertRaisesRegex(ValueError, 'outside quote recovery paths'):
                    verify_delta(Path('.'), SOURCE, OLD, MAIN)

    def test_direct_recovery_accepts_controller_only_instrumentation_preparation(self):
        with patch('quote_recovery.verify_build_candidate'), \
                patch('acceptance_policy.needs_live_tor_probe', return_value=False), \
                patch('quote_recovery.git', side_effect=[OLD.encode(), b'.private/prepare_direct_core.py\0']):
            verify_delta(Path('.'), SOURCE, OLD, MAIN)


    def test_recovery_uses_the_last_verified_stage(self):
        producers = dict(BUILD_JOBS, **{QUOTE: 'success'})
        cases = (
            ('signer', 'success', 'success', 'success', 'failure', 'hosted-core-receipt', '', False, {}),
            ('no POST', 'failure', 'success', 'success', 'skipped', None, '7', False,
             {'run_missing_core': 'true', 'previous_reservation': 'e' * 40}),
            ('prepare', 'skipped', 'skipped', 'failure', 'skipped', None, '', False,
             {'run_missing_core': 'true'}),
            ('unfunded retry', 'failure', 'skipped', 'success', 'skipped', None, '', True,
             {'reuse_funded': 'true', 'prepared_id': '75'}),
            ('unfunded receipt', 'success', 'skipped', 'success', 'failure', 'hosted-upgrade-receipt', '', True,
             {'reuse_funded': 'true', 'prepared_id': '75', 'core_receipt_id': '76'}),
        )
        for name, wallet, reserve, prepare, publish, receipt, preclaim, funded, expected in cases:
            with self.subTest(stage=name):
                states = dict(producers, publish=publish, **{WALLET: wallet, RESERVE: reserve, PREPARE: prepare})
                names = [*BUILD_ARTIFACTS, 'hosted-quote-receipt', 'core-direct-candidate', receipt]
                original = self.recovery_run(7, OLD, states, filter(None, names))
                metadata = self.recovery_metadata(original, refs=[])
                with patch('quote_recovery.require_dispatch'), patch('quote_recovery.verify_delta'), \
                        patch('quote_recovery.unused_reservation', return_value='e' * 40), \
                        patch('release_inputs.version_code', return_value=42), \
                        patch('funded_reuse.select', return_value={'core': {}} if funded else None):
                    result = resolve(Path('.'), SOURCE, MAIN, '7', metadata, preclaim_run=preclaim)
                self.assertEqual(result['quote_receipt_id'], '74')
                self.assertEqual(result['quote_run'], 7)
                for key, value in expected.items():
                    self.assertEqual(result[key], value)
                if receipt is None:
                    self.assertNotIn('core_receipt_id', result)


    def test_unpublished_maintenance_requires_proven_producer_and_current_cadence(self):
        states = dict(BUILD_JOBS, publish='failure', **{WALLET: 'success', QUOTE: 'success'})
        for event, marker, prior_attestation, admitted, error in (
            ('schedule', {}, False, False, None),
            ('schedule', {}, False, True, None),
            ('push', {}, False, True, 'cannot recover routine'),
            ('pull_request', {}, False, True, 'trusted custom run or maintenance run'),
            ('push', {'kind': 'security'}, False, True, None),
            ('schedule', {}, True, True, 'retained publication attestation'),
        ):
            with self.subTest(event=event, marker=marker, admitted=admitted, error=error):
                names = [*ARTIFACTS.values(), 'hosted-quote-receipt']
                if prior_attestation:
                    names.append('release-attestation-1')
                run, jobs, artifacts = self.recovery_run(7, OLD, states, names)
                run.update(path='.github/workflows/sync-upstream.yml', event=event)
                if admitted:
                    jobs = [dict(job, name='discover / ' + job['name']) for job in jobs]
                metadata = self.recovery_metadata((run, jobs, artifacts))
                with patch('quote_recovery.require_dispatch') as dispatch, patch('quote_recovery.verify_delta'), \
                        patch('quote_recovery.changed_marker', side_effect=({}, marker)), \
                        patch('maintenance_cadence.admit_cadence') as cadence:
                    if error:
                        with self.assertRaisesRegex(ValueError, error):
                            resolve(Path('.'), SOURCE, MAIN, '7', metadata)
                    else:
                        result = resolve(Path('.'), SOURCE, MAIN, '7', metadata)
                        self.assertEqual(result['core_receipt_id'], '74')
                        self.assertEqual(result['quote_receipt_id'], '75')
                        cadence.assert_called_once()
                    dispatch.assert_called_once()


    def test_recovery_requires_authorized_exact_run_and_completed_original_wallet(self):
        states = dict(BUILD_JOBS, publish='skipped', **{WALLET: 'success', QUOTE: 'failure'})
        with tempfile.TemporaryDirectory() as temp:
            event = Path(temp) / 'event.json'
            env = dict(GITHUB_REPOSITORY=REPOSITORY, GITHUB_REF='refs/heads/main',
                       GITHUB_EVENT_NAME='workflow_dispatch', GITHUB_RUN_ID='8', GITHUB_EVENT_PATH=str(event))
            cases = [('7', states, None), ('9', states, 'does not authorize'),
                     ('7', dict(states, **{WALLET: 'failure'}), 'wallet'),
                     ('7', dict(states, publish='success'), 'publish')]
            for authorized, outcomes, error in cases:
                with self.subTest(authorized=authorized, error=error), patch_environment(env), \
                        patch('quote_recovery.verify_delta'), patch('funded_reuse.select', return_value=None):
                    event.write_text(json.dumps({'inputs': {'source_sha': SOURCE, 'operation': 'recover',
                                                           'recover_run': authorized}}))
                    original = self.recovery_run(7, OLD, outcomes, ARTIFACTS.values())
                    current = self.recovery_run(8, MAIN, {}, ())
                    metadata = self.recovery_metadata(original, current)
                    api = metadata.api.side_effect
                    metadata.api.side_effect = lambda path: {'object': {'sha': MAIN}} if path.startswith('/git/ref/') else api(path)
                    if error:
                        with self.assertRaisesRegex(ValueError, error):
                            resolve(Path('.'), SOURCE, MAIN, '7', metadata)
                    else:
                        result = resolve(Path('.'), SOURCE, MAIN, '7', metadata)
                        self.assertEqual(result['original_controller'], OLD)
                        self.assertEqual(result['core_receipt_id'], '74')


if __name__ == '__main__':
    unittest.main()
