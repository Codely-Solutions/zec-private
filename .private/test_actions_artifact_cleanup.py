"""Identity, reference, run-state and dry-run cleanup safeguards."""
import unittest
from pathlib import Path
from unittest.mock import patch
import actions_artifact_cleanup as cleanup

CANDIDATE = dict(id=10, name='validated-unsigned-apk', size_in_bytes=123,
                 digest='sha256:' + 'a' * 64, run_id=20, head_sha='b' * 40, reason='accepted replacement')


class CleanupTests(unittest.TestCase):
    def setUp(self):
        self.live = {key: CANDIDATE[key] for key in ('id', 'name', 'size_in_bytes', 'digest')}
        self.live['workflow_run'] = dict(id=20, head_sha='b' * 40)
        self.run = dict(id=20, head_sha='b' * 40, status='completed')

    def test_exact_identity_and_completed_run_required(self):
        cleanup.validate_candidate(CANDIDATE, self.live, self.run)
        for field in ('id', 'name', 'size_in_bytes', 'digest'):
            with self.subTest(field=field), self.assertRaises(ValueError):
                cleanup.validate_candidate(CANDIDATE, dict(self.live, **{field: 'changed'}), self.run)
        with self.assertRaisesRegex(ValueError, 'active'):
            cleanup.validate_candidate(CANDIDATE, self.live, dict(self.run, status='in_progress'))

    def test_nested_current_references_protect_every_identity(self):
        for value in (10, '20', 'b' * 40, 'sha256:' + 'a' * 64):
            self.assertTrue(cleanup.referenced({'nested': [{'input': value}]}, CANDIDATE))
        self.assertFalse(cleanup.referenced({'id': 999}, CANDIDATE))

    def test_default_dry_run_never_deletes(self):
        calls = []
        def api(path, method='GET'):
            calls.append((path, method))
            return self.live if '/artifacts/' in path else self.run
        manifest = dict(repository='owner/repo', references=['.private/current.json'], candidates=[CANDIDATE])
        with patch.object(cleanup.subprocess, 'check_output', return_value='https://github.com/owner/repo.git'), \
             patch.object(cleanup, 'reference_snapshot', return_value={'current': ('hash', {})}), \
             patch.object(cleanup, 'live_references', return_value=('head', {'current': {}})), \
             patch.object(cleanup, 'require_idle'):
            receipt = cleanup.cleanup(manifest, Path('.'), api=api)
        self.assertEqual(receipt['items'][0]['result'], 'would-delete')
        self.assertEqual(receipt['deleted_bytes'], 0)
        self.assertFalse(any(method == 'DELETE' for _, method in calls))

    def test_active_consumer_blocks_cleanup(self):
        with self.assertRaisesRegex(ValueError, 'active'):
            cleanup.require_idle(lambda _: {'workflow_runs': [dict(status='queued')]}, 'owner/repo')

    def test_reference_race_preserves_candidate_before_delete(self):
        calls = []
        def api(path, method='GET'):
            calls.append(method)
            return self.live if '/artifacts/' in path else self.run
        manifest = dict(repository='owner/repo', references=['.private/current.json'], candidates=[CANDIDATE])
        with patch.object(cleanup.subprocess, 'check_output', return_value='https://github.com/owner/repo.git'), \
             patch.object(cleanup, 'reference_snapshot', return_value={'current': ('hash', {})}), \
             patch.object(cleanup, 'live_references', side_effect=[('old', {}), ('new', {})]), \
             patch.object(cleanup, 'require_idle'):
            receipt = cleanup.cleanup(manifest, Path('.'), execute=True, api=api)
        self.assertEqual(receipt['items'][0]['result'], 'preserved')
        self.assertIn('changed', receipt['items'][0]['reason'])
        self.assertNotIn('DELETE', calls)

    def test_exact_delete_receipt_records_only_confirmed_size(self):
        calls = []
        def api(path, method='GET'):
            calls.append((path, method))
            return self.live if '/artifacts/' in path else self.run
        manifest = dict(repository='owner/repo', references=['.private/current.json'], candidates=[CANDIDATE])
        with patch.object(cleanup.subprocess, 'check_output', return_value='https://github.com/owner/repo.git'), \
             patch.object(cleanup, 'reference_snapshot', return_value={'current': ('hash', {})}), \
             patch.object(cleanup, 'live_references', return_value=('head', {})), \
             patch.object(cleanup, 'require_idle'):
            receipt = cleanup.cleanup(manifest, Path('.'), execute=True, api=api)
        self.assertEqual(receipt['deleted_bytes'], 123)
        self.assertEqual([path for path, method in calls if method == 'DELETE'],
                         ['repos/owner/repo/actions/artifacts/10'])


if __name__ == '__main__':
    unittest.main()
