import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import maintenance_cadence as cadence


TAG = 'v3.19.0-private.21'
SHA = 'a' * 40
DIGEST = 'b' * 64
ADVISORY = 'GHSA-1234-5678-9abc'


def utc(date):
    return dt.datetime.fromisoformat(date + '+00:00')


def release(date):
    return dict(tag_name=TAG, draft=False, prerelease=False, published_at=date,
                assets=[dict(name='zec-private-latest.apk', digest='sha256:' + DIGEST)])


class CadenceTest(unittest.TestCase):
    def test_window_rollover_missed_poll_and_one_routine_publication(self):
        metadata = Mock()
        metadata.api.return_value = [[]]
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.assertIsNone(cadence.routine_window(root, metadata, utc('2026-12-01T08:59:59')))
            now = utc('2027-01-14T12:00:00')
            self.assertEqual(cadence.routine_window(root, metadata, now), utc('2027-01-01T09:00:00'))
            self.assertEqual(cadence.window(utc('2027-01-01T08:00:00')), cadence.FIRST_WINDOW)
            metadata.api.return_value = [[release('2027-01-12T10:00:00Z')]]
            self.assertIsNone(cadence.routine_window(root, metadata, now))
            evidence = root / '.private/release-evidence' / (TAG + '.json')
            evidence.parent.mkdir(parents=True)
            evidence.write_text(json.dumps(dict(apk_sha256=DIGEST, maintenance={'kind': 'security'})))
            self.assertEqual(cadence.routine_window(root, metadata, now), utc('2027-01-01T09:00:00'))
            metadata.api.return_value[0][0]['assets'][0]['digest'] = 'sha256:' + 'c' * 64
            self.assertIsNone(cadence.routine_window(root, metadata, now))

    def test_discovery_defers_then_batches_eligible_inputs_at_the_cutoff(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            state = root / '.private/upstream.json'
            state.parent.mkdir()
            state.write_text('{"tag":"3.19.0-3234"}')
            old = dict(tag_name='3.19.1-3235', published_at='2026-11-30T10:00:00Z')
            new = dict(tag_name='3.19.2-3236', published_at='2026-12-02T10:00:00Z')
            with patch.dict(os.environ, {'PROPOSAL': '7', 'PROPOSAL_HEAD': SHA, 'GITHUB_SHA': SHA,
                                         'GITHUB_EVENT_NAME': 'schedule'}), \
                    patch('sync.ROOT', root), patch('sync.discover', side_effect=lambda cutoff=None: old if cutoff else new), \
                    patch('maintenance_cadence.security_review', return_value=None), patch('sync.main') as upstream, \
                    patch('prepare_maintenance.prepare') as wrapper, patch('release_metadata.Metadata'), \
                    patch('maintenance_watch.verified_wrapper', return_value=('9.9.2', 'verified properties')), \
                    patch('maintenance_cadence.routine_window', return_value=None) as window:
                cadence.main()
                upstream.assert_not_called()
                wrapper.assert_not_called()
                window.return_value = cadence.FIRST_WINDOW
                cadence.main()
                upstream.assert_called_once_with(old, ('9.9.2', 'verified properties', SHA))
                wrapper.assert_not_called()

    def test_routine_admission_rejects_a_post_cutoff_candidate(self):
        upstream = {'published_at': '2026-12-02T09:00:00Z'}
        with patch.dict(os.environ, {'GITHUB_EVENT_NAME': 'schedule'}), \
                patch('maintenance_cadence.changed_marker', side_effect=[{}, {}, upstream]), \
                patch('maintenance_cadence.routine_window', return_value=cadence.FIRST_WINDOW):
            with self.assertRaisesRegex(ValueError, 'after the monthly cutoff'):
                cadence.admit_cadence(Path('.'), SHA, SHA, Mock(), Mock())

    def test_review_push_cannot_admit_routine_maintenance(self):
        with patch.dict(os.environ, {'GITHUB_EVENT_NAME': 'push'}), \
                patch('maintenance_cadence.changed_marker', side_effect=[{}, {'kind': 'gradle-wrapper-patch'}]), \
                patch('maintenance_cadence.routine_window') as window:
            with self.assertRaisesRegex(ValueError, 'cannot admit routine'):
                cadence.admit_cadence(Path('.'), SHA, SHA, Mock(), Mock())
            window.assert_not_called()

    def test_security_review_binds_advisory_shipped_apk_and_exact_patch(self):
        metadata = Mock()
        metadata.api.return_value = release('2026-11-10T10:00:00Z')
        patch_bytes = b'reviewed patch'
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            evidence = root / '.private/release-evidence' / (TAG + '.json')
            evidence.parent.mkdir(parents=True)
            evidence.write_text(json.dumps(dict(source_sha=SHA, apk_sha256=DIGEST)))
            review = dict(advisory=ADVISORY, advisory_repository='publisher/component', affected_release=TAG,
                          affected_source=SHA, scope='wallet', exposure='Reviewed call path', fix='Supported fix',
                          advisory_updated_at='2026-11-10T10:00:00Z',
                          patch_sha256=hashlib.sha256(patch_bytes).hexdigest())
            (root / cadence.REVIEW).write_text(json.dumps(review))
            (root / cadence.PATCH).write_bytes(patch_bytes)
            advisory = dict(ghsa_id=ADVISORY, state='published', withdrawn_at=None,
                            updated_at=review['advisory_updated_at'])
            api = Mock(return_value=advisory)
            self.assertEqual(cadence.security_review(root, metadata, api), review)
            for change in ({'state': 'withdrawn'}, {'updated_at': 'changed'}):
                api.return_value = dict(advisory, **change)
                with self.assertRaisesRegex(ValueError, 'exact current published'):
                    cadence.security_review(root, metadata, api)
            api.return_value = advisory
            (root / cadence.PATCH).write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError, 'patch digest'):
                cadence.security_review(root, metadata, api)

    def test_security_bypasses_calendar_only_with_live_matching_review(self):
        marker = {'kind': 'security', 'advisory': ADVISORY}
        with patch('maintenance_cadence.changed_marker', side_effect=[{}, marker, {}, marker]), \
                patch('maintenance_cadence.security_review', return_value=marker) as review, \
                patch('maintenance_cadence.routine_window') as window:
            cadence.admit_cadence(Path('.'), SHA, SHA, Mock(), Mock())
            window.assert_not_called()
            review.return_value = None
            with self.assertRaisesRegex(ValueError, 'no longer eligible'):
                cadence.admit_cadence(Path('.'), SHA, SHA, Mock(), Mock())

    def test_disposable_security_candidate_rejects_an_unrelated_change(self):
        from acceptance_policy import verify_promotion_revision
        import prepare_maintenance

        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)

            def git(*args):
                return cadence.git(root, *args)

            def write(path, contents):
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(contents)

            def commit(message):
                git('add', '-A')
                git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                    'commit', '-qm', message)
                return git('rev-parse', 'HEAD').decode().strip()

            git('init', '-q')
            write('gradle.properties', b'ZCASH_VERSION_CODE=3234021\nSDK=affected\n')
            write('Wallet.kt', b'old\n')
            shipped = commit('Shipped wallet')
            write('gradle.properties', b'ZCASH_VERSION_CODE=3234021\nSDK=fixed\n')
            security_patch = git('diff', '--binary', '--', 'gradle.properties')
            git('checkout', '--', 'gradle.properties')
            marker = dict(kind='security', advisory=ADVISORY, affected_release=TAG,
                          patch_sha256=hashlib.sha256(security_patch).hexdigest())
            write(cadence.REVIEW, json.dumps(dict(marker, affected_source=shipped)).encode())
            write(cadence.PATCH, security_patch)
            write('.private/upstream.json', json.dumps(dict(sha=shipped, versionCode=3234021, tag='3.19.0-3234')).encode())
            write('.private/release-revision.json', b'{"private_revision":21}')
            write('.private/excluded-features.json', b'{"forbidden_projects":[]}')
            write('.private/check.py', b'# Fixture has no Android product checks.\n')
            controller = commit('Review exact fix')
            pushed = []

            def stage_git(*args):
                if args[0] in ('ls-remote', 'push'):
                    if args[0] == 'push':
                        pushed.append(args[-1])
                    return ''
                return git(*args).decode().strip()

            metadata = Mock()
            metadata.api.side_effect = [[[]], [{'ref': 'refs/heads/testnet-claims/release-3234022'}]]
            with patch.dict(os.environ, {'RUNNER_TEMP': str(root.parent)}), \
                    patch('prepare_maintenance.ROOT', root), patch('refresh_source.ROOT', root), patch('sync.ROOT', root), \
                    patch('prepare_maintenance.git', side_effect=stage_git), \
                    patch('prepare_maintenance.api', return_value={'sha': controller}):
                source = prepare_maintenance.prepare_security(marker, controller, metadata)
                self.assertEqual(len(pushed), 1)
                self.assertIn(b'ZCASH_VERSION_CODE=3234023', git('show', f'{source}:gradle.properties'))
                self.assertEqual(cadence.verify_security_child(source, controller, root), marker)
                self.assertFalse(verify_promotion_revision(source, controller, root))
                write('Wallet.kt', b'unrelated\n')
                modified = commit('Unreviewed change')
                with self.assertRaisesRegex(ValueError, 'unreviewed changes'):
                    cadence.verify_security_child(modified, controller, root)


if __name__ == '__main__':
    unittest.main()
