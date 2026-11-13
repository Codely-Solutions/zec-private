import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import hosted_release
from quote_recovery import ARTIFACTS, OPERATOR_ID, REPOSITORY, WORKFLOW, resolve, resolve_quotes, verify_delta


SOURCE, OLD, MAIN = 'a' * 40, 'b' * 40, 'c' * 40


class QuoteRecoveryTests(unittest.TestCase):
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
