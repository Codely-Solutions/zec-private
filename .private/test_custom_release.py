import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import custom_release
from maintenance_cadence import admit_cadence


SOURCE, MAIN = 'a' * 40, 'b' * 40


class CustomReleaseTests(unittest.TestCase):
    def test_dispatch_binds_actor_workflow_operation_source_and_current_main(self):
        run = dict(id=7, path=custom_release.WORKFLOW, event='workflow_dispatch', head_branch='main',
                   head_sha=MAIN, head_repository={'full_name': custom_release.REPOSITORY},
                   actor={'id': custom_release.OPERATOR_ID})
        cases = (
            ('publish', SOURCE, {}, MAIN, None),
            ('check', SOURCE, {}, MAIN, 'does not authorize'),
            ('preflight', SOURCE, {}, MAIN, 'does not authorize'),
            ('publish', 'c' * 40, {}, MAIN, 'does not authorize'),
            ('publish', SOURCE, {'actor': {'id': 1}}, MAIN, 'authorized custom release operator'),
            ('publish', SOURCE, {'path': '.github/workflows/sync-upstream.yml'}, MAIN, 'authorized custom release operator'),
            ('publish', SOURCE, {'head_branch': 'feature'}, MAIN, 'authorized custom release operator'),
            ('publish', SOURCE, {}, 'c' * 40, 'Protected main advanced'),
        )
        with tempfile.TemporaryDirectory() as directory:
            event = Path(directory) / 'event.json'
            environment = dict(GITHUB_REPOSITORY=custom_release.REPOSITORY, GITHUB_REF='refs/heads/main',
                               GITHUB_EVENT_NAME='workflow_dispatch', GITHUB_RUN_ID='7', GITHUB_EVENT_PATH=str(event))
            with patch.dict(os.environ, environment), patch('maintenance_cadence.changed_marker',
                                                            return_value={'kind': 'custom', 'controller': MAIN}):
                for operation, selected, run_fields, current, rejection in cases:
                    with self.subTest(operation=operation, source=selected, run=run_fields, main=current):
                        event.write_text(json.dumps({'inputs': {'source_sha': selected, 'operation': operation}}))
                        metadata = Mock()
                        metadata.api.side_effect = [dict(run, **run_fields), {'object': {'sha': current}}]
                        if rejection:
                            with self.assertRaisesRegex(ValueError, rejection):
                                admit_cadence(Path(directory), SOURCE, MAIN, metadata, Mock())
                            if operation == 'preflight':
                                metadata.api.side_effect = [run, {'object': {'sha': MAIN}}]
                                admit_cadence(Path(directory), SOURCE, MAIN, metadata, Mock(), preflight_only=True)
                        else:
                            admit_cadence(Path(directory), SOURCE, MAIN, metadata, Mock())

    def test_review_rejects_candidate_changes_to_signing_controls(self):
        with patch('custom_release.git', side_effect=[f'{SOURCE} {MAIN}'.encode(), b'.github/workflows/build-release.yml\0']), \
                patch('custom_release.changed_marker', return_value={'kind': 'custom', 'controller': MAIN}):
            with self.assertRaisesRegex(ValueError, 'controller-owned files'):
                custom_release.verify_custom_child(SOURCE, MAIN, Path('.'))


if __name__ == '__main__':
    unittest.main()
