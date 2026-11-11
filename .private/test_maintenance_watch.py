import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from maintenance_watch import WRAPPER_PROPERTIES, classify_paths, trusted_proposal, verified_wrapper, wrapper_patch


class MaintenanceWatchTest(unittest.TestCase):
    def test_wrapper_patch_requires_exact_official_coordinates_and_settings(self):
        base = ('distributionUrl=https\\://services.gradle.org/distributions/gradle-9.9.1-bin.zip\n'
                'distributionSha256Sum=' + 'a'*64 + '\nnetworkTimeout=10000\n')
        proposed = base.replace('9.9.1', '9.9.2').replace('a'*64, 'b'*64)
        versions = []

        def checksum(version):
            versions.append(version)
            return 'b'*64

        self.assertEqual(wrapper_patch(base, proposed, checksum), '9.9.2')
        self.assertEqual(versions, ['9.9.2'])
        for untrusted in (proposed.replace('services.gradle.org', 'example.invalid'),
                          proposed.replace('9.9.2', '9.10.1'),
                          proposed.replace('10000', '20000'),
                          proposed + ' distributionUrl=http://example.invalid\n',
                          proposed.replace('b'*64, 'c'*64)):
            with self.subTest(untrusted=untrusted), self.assertRaises(ValueError):
                wrapper_patch(base, untrusted, checksum)

    def test_controller_file_cannot_enter_the_dependency_queue(self):
        files = [{'filename': 'gradle.properties', 'status': 'modified'}]
        self.assertIn('needs provenance', classify_paths(files, 1))
        self.assertEqual('incomplete diff', classify_paths(files, 2))
        files[0].update(status='renamed', previous_filename='.private/publish.py')
        self.assertIn('controller change', classify_paths(files, 1))
        files[0].update(filename='.github/workflows/build-release.yml', previous_filename='gradle.properties')
        self.assertIn('controller change', classify_paths(files, 1))

    def test_repository_and_bot_identity_are_required(self):
        pr = {'state': 'open', 'draft': False, 'user': {'login': 'dependabot[bot]', 'type': 'Bot'},
              'head': {'repo': {'id': 7}, 'ref': 'dependabot/gradle/update', 'sha': 'a' * 40},
              'base': {'repo': {'id': 7}, 'ref': 'main'}}
        self.assertTrue(trusted_proposal(pr, 7))
        self.assertFalse(trusted_proposal(dict(pr, user={'login': 'dependabot[bot]', 'type': 'User'}), 7))
        self.assertFalse(trusted_proposal(dict(pr, head=dict(pr['head'], repo={'id': 8})), 7))

    def test_verified_wrapper_rechecks_the_proposal_after_resolving_the_checksum(self):
        base, head = 'a'*40, 'b'*40
        pr = {'state': 'open', 'draft': False, 'changed_files': 1,
              'user': {'login': 'dependabot[bot]', 'type': 'Bot'},
              'head': {'repo': {'id': 7}, 'ref': 'dependabot/gradle/update', 'sha': head},
              'base': {'repo': {'id': 7}, 'ref': 'main', 'sha': base}}
        files = [{'filename': WRAPPER_PROPERTIES, 'status': 'modified'}]
        with patch('maintenance_watch.wrapper_source', return_value='exact properties'), \
                patch('maintenance_watch.wrapper_patch', return_value='9.9.2'):
            with patch('maintenance_watch.api', side_effect=[pr, files, pr, {'sha': base}]):
                self.assertEqual(verified_wrapper('repos/fork/wallet', 1, base, head, 7),
                                 ('9.9.2', 'exact properties'))
            with patch('maintenance_watch.api', side_effect=[pr, files, dict(pr, state='closed')]):
                with self.assertRaisesRegex(ValueError, 'changed during verification'):
                    verified_wrapper('repos/fork/wallet', 1, base, head, 7)
            with patch('maintenance_watch.api', side_effect=[pr, [{'filename': '.private/publish.py',
                                                                  'status': 'modified'}]]):
                with self.assertRaisesRegex(ValueError, 'Wrapper-only'):
                    verified_wrapper('repos/fork/wallet', 1, base, head, 7)

    def test_prepare_builds_a_reusable_branch_from_trusted_coordinates(self):
        import prepare_maintenance as prepare

        with tempfile.TemporaryDirectory() as temp:
            scratch = Path(temp)
            root = scratch/'controller'
            root.mkdir()

            def git(*args):
                return subprocess.check_output(['git', *args], cwd=root, text=True, timeout=20).strip()

            git('init', '-q')
            (root/'.private').mkdir()
            (root/WRAPPER_PROPERTIES).parent.mkdir(parents=True)
            (root/WRAPPER_PROPERTIES).write_text('old')
            (root/'gradle.properties').write_text('ZCASH_VERSION_CODE=3234021\nZCASH_VERSION_NAME=3.19.0\n')
            (root/'.private/release-revision.json').write_text('{"private_revision":21}')
            (root/'.private/upstream.json').write_text('{"tag":"3.19.0-3234","versionCode":3234021}')
            (root/'.private/fork.patch').touch()
            (root/'.private/source-state.json').touch()
            git('add', '.')
            git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'controller')
            base, head = git('rev-parse', 'HEAD'), 'b'*40
            environment = dict(GITHUB_ACTIONS='true', GITHUB_REF='refs/heads/main',
                               GITHUB_REPOSITORY=prepare.REPOSITORY)
            remote = {}

            def remote_git(*args):
                if args[:2] == ('ls-remote', 'origin'):
                    return remote.get(args[2], '')
                if args[:2] == ('fetch', '--no-tags'):
                    return ''
                if args[:2] == ('push', 'origin'):
                    sha, ref = args[2].split(':')
                    self.assertNotIn(ref, remote)
                    remote[ref] = sha
                    return ''
                return git(*args)

            with patch.dict(os.environ, environment), patch.object(prepare, 'ROOT', root), \
                    patch.object(prepare, 'git', side_effect=remote_git), \
                    patch.object(prepare.Metadata, 'api', side_effect=lambda path, **_: (
                        {'id': 7} if not path else [[{'tag_name': 'v3.19.0-private.21'}]])), \
                    patch.object(prepare, 'verified_wrapper', return_value=('9.9.2', 'verified wrapper')), \
                    patch.object(prepare, 'api', side_effect=lambda path: (
                        {'state': 'open', 'head': {'sha': head}} if '/pulls/' in path else {'sha': base})), \
                    patch.object(prepare, 'refresh_source'), patch('builtins.print') as output:
                original_run = subprocess.run

                def run(command, *args, **kwargs):
                    if command[:3] == ['gh', 'auth', 'setup-git']:
                        return subprocess.CompletedProcess(command, 0)
                    return original_run(command, *args, **kwargs)

                with patch.object(subprocess, 'run', side_effect=run):
                    prepare.prepare('2', head, base)
                    first = json.loads(output.call_args.args[0])
                    git('reset', '-q', '--hard', base)
                    prepare.prepare('2', head, base)
                    self.assertEqual(first, json.loads(output.call_args.args[0]))
            sha = first['source_sha']
            self.assertEqual(base, git('rev-parse', sha+'^'))
            self.assertEqual('verified wrapper', git('show', f'{sha}:{WRAPPER_PROPERTIES}'))
            self.assertEqual(3234022, first['version_code'])


if __name__ == '__main__':
    unittest.main()
