import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import hosted_release
from quote_recovery import ARTIFACTS, OPERATOR_ID, REPOSITORY, WORKFLOW, resolve, verify_delta


SOURCE, OLD, MAIN = 'a' * 40, 'b' * 40, 'c' * 40


class QuoteRecoveryTests(unittest.TestCase):
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
        states = {'review': 'success', 'build / unsigned': 'success', 'build / emulator': 'success',
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
