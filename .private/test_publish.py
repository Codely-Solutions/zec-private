"""Release display metadata checks; no signing, build or publication."""
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('private_publish',Path(__file__).with_name('publish.py'))
publish=importlib.util.module_from_spec(spec)
with patch.dict(os.environ,{'GITHUB_REPOSITORY':'example/zec-private'}):
    spec.loader.exec_module(publish)
    from integrate_release import state_commit


class PublishMetadataTest(unittest.TestCase):
    def test_attestation_requires_exact_bytes(self):
        with tempfile.TemporaryDirectory() as scratch:
            path = Path(scratch) / 'release-attestation.json'
            data = b'{"release_tag":"v3.19.0-private.21"}'
            digest = hashlib.sha256(data).hexdigest()
            path.write_bytes(data)
            self.assertEqual(publish.read_attestation(path, digest), json.loads(data))
            path.write_bytes(data + b'\n')
            with self.assertRaisesRegex(RuntimeError, 'digest or size mismatch'):
                publish.read_attestation(path, digest)

    def test_state_commit_changes_only_release_metadata_without_checking_out_candidate(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch) / 'repo'
            root.mkdir()

            def git(*args):
                return subprocess.check_output(['git', *args], cwd=root, text=True).strip()

            git('init', '-q')
            git('config', 'user.name', 'Test')
            git('config', 'user.email', 'test@example.invalid')
            git('commit', '--allow-empty', '-qm', 'controller')
            controller = git('rev-parse', 'HEAD')
            (root / 'candidate.txt').write_text('test payload')
            git('add', 'candidate.txt')
            git('commit', '-qm', 'candidate')
            candidate = git('rev-parse', 'HEAD')
            git('checkout', '-q', '--detach', controller)
            tag = 'v3.19.0-private.21'
            path = f'.private/release-evidence/{tag}.json'
            proof = dict(release_tag=tag, upstream={'tag': '3.19.0-3235'})
            commit = state_commit(candidate, proof, Path(scratch), root)
            self.assertEqual(git('rev-parse', 'HEAD'), controller)
            self.assertEqual(git('status', '--porcelain'), '')
            self.assertEqual(git('rev-parse', commit + '^'), candidate)
            self.assertEqual(git('diff', '--name-only', candidate, commit).splitlines(),
                             [path, '.private/upstream.json'])
            self.assertEqual(json.loads(git('show', f'{commit}:{path}')), proof)
            with self.assertRaisesRegex(RuntimeError, 'existing release evidence'):
                state_commit(commit, proof, Path(scratch), root)

    def test_artifact_release_identity_is_bound_to_the_source(self):
        source = 'a' * 40
        metadata = '.private/candidate.json'
        upstream = {'tag': '3.19.1-3235', 'sha': 'b' * 40, 'versionCode': 3235021}
        proof = {'upstream': upstream, 'release_tag': 'v3.19.1-private.21',
                 'versionCode': 3235021, 'versionName': '3.19.1'}
        objects = {
            ('git', 'show', f'{source}:gradle.properties'):
                'ZCASH_VERSION_CODE=3235021\nZCASH_VERSION_NAME=3.19.1',
            ('git', 'show', f'{source}:.private/release-revision.json'): '{"private_revision": 21}',
            ('git', 'ls-tree', '--name-only', source, '--', metadata): metadata,
            ('git', 'show', f'{source}:{metadata}'): json.dumps(upstream),
        }
        with patch.object(publish, 'run', side_effect=lambda *args: objects[args]):
            publish.verify_release_identity(proof, source)
            for field, value in (('versionCode', 3235022), ('versionName', '3.20.0'),
                                 ('release_tag', 'v3.19.1-private.22'),
                                 ('upstream', dict(upstream, sha='c' * 40))):
                with self.subTest(field=field), self.assertRaisesRegex(
                        RuntimeError, 'differs from immutable source'):
                    publish.verify_release_identity(dict(proof, **{field: value}), source)

    def test_signing_requires_current_main_controller(self):
        controller = 'a' * 40
        for ref, local, remote, allowed in (
            ('refs/heads/main', controller, controller, True),
            ('refs/heads/candidate', controller, controller, False),
            ('refs/tags/main', controller, controller, False),
            ('refs/heads/main', 'b' * 40, controller, False),
            ('refs/heads/main', controller, 'b' * 40, False),
        ):
            with self.subTest(ref=ref, local=local, remote=remote), \
                    patch.dict(os.environ, {'GITHUB_REF': ref}), \
                    patch.object(publish, 'run', side_effect=(local, remote)):
                if allowed:
                    publish.require_current_controller(controller)
                else:
                    with self.assertRaises(RuntimeError):
                        publish.require_current_controller(controller)

    def test_title_uses_private_revision(self):
        self.assertEqual('ZEC Private — Release 18',publish.release_title('v2.3.4-private.18'))
        self.assertEqual('ZEC Private — Release 120',publish.release_title('v9.10.11-private.120'))

    def test_title_rejects_invalid_tags(self):
        for tag in (None,18,'v2.3.4','v2.3.4-private.18-extra','v2.3.4-private.18\n',
                    'v2.3.4-private.-1','v2.3.4-private.１８','v2.3.4-private.18; command'):
            with self.subTest(tag=tag),self.assertRaisesRegex(RuntimeError,'Invalid private release tag'):
                publish.release_title(tag)

    def test_notes_keep_technical_links_and_version_code(self):
        proof={'release_tag':'v2.3.4-private.18','versionCode':123456,'upstream':{'tag':'v2.3.4'}}
        with patch.object(Path,'read_text',return_value=json.dumps({proof['release_tag']:['Correct quote validation.']})):
            notes=publish.release_notes(proof)
        self.assertTrue(notes.startswith('ZEC Private — Release 18\n\n- Correct quote validation.\n'))
        self.assertIn('Android versionCode: 123456',notes)
        self.assertIn('/blob/v2.3.4-private.18/docs/WALLET-GUIDE.md',notes)
        self.assertIn('/.private/release-evidence/v2.3.4-private.18.json',notes)
        self.assertIn('zec-private-latest.apk',notes)

    def test_notes_preserve_change_limits(self):
        proof={'release_tag':'v2.3.4-private.18','versionCode':123456,'upstream':{'tag':'v2.3.4'}}
        for changes in (['Change']*7,['x'*301],[''],[1],{}):
            with self.subTest(changes=changes):
                with patch.object(Path,'read_text',return_value=json.dumps({proof['release_tag']:changes})):
                    with self.assertRaisesRegex(RuntimeError,'Release changes must be a list'):
                        publish.release_notes(proof)


if __name__=='__main__': unittest.main()
