import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile
from prepared_apks import reuse, write_receipt, export_prepared
from strip_emulator_apk import verify_prepared, signing_identity
from tor_differential_driver import fetch_baseline
from ui_step import active_root, identity


class ArtifactTests(unittest.TestCase):
    def test_windows_certificate_output(self):
        from types import SimpleNamespace
        output = b'Signer #1 certificate DN: CN=Isolated Unfunded Emulator Test\r\nSigner #1 certificate SHA-256 digest: '+b'b'*64+b'\r\n'
        with patch('strip_emulator_apk.subprocess.run', return_value=SimpleNamespace(stdout=output)):
            self.assertEqual('b'*64, signing_identity(Path('fixture.apk'), Path('sdk')))

    def test_reuse_and_bindings(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            apk = root/'candidate.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('classes.dex', b'executable')
            digest = hashlib.sha256(b'executable').hexdigest()
            prep = dict(abi='x86_64', signing='ephemeral test key; not release key',
                originalSha256='a'*64, testedSha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
                testSignerSha256='b'*64, executionEntriesUnchanged=True, preservedExecutionEntries=1,
                executionEntriesDigest=hashlib.sha256(json.dumps({'classes.dex':digest},sort_keys=True).encode()).hexdigest())
            manifest = dict(build_source_sha='d'*40, probeKind='ui', configuration={'variant':'debug'})
            receipt = root/'prepared.json'
            receipt_hash = write_receipt(receipt, manifest, manifest, [apk], {apk.name:prep})
            with patch('strip_emulator_apk.signing_identity', return_value='b'*64):
                destination = root/'session'; destination.mkdir()
                paths, preparations, execution = reuse(receipt, manifest, destination, root, receipt_hash)
                self.assertEqual(paths[0].read_bytes(), apk.read_bytes())
                exported, digest = export_prepared(root/'retained', manifest, manifest, [apk], {apk.name:prep}, root)
                self.assertEqual(hashlib.sha256(exported.read_bytes()).hexdigest(), digest)
                self.assertEqual(Path(json.loads(exported.read_text())['apks'][0]['path']).parent, root/'retained')
                with self.assertRaises(FileExistsError): write_receipt(receipt, manifest, manifest, [apk], {apk.name:prep})
                with patch('prepared_apks.shutil.copyfile') as copy:
                    with self.assertRaises(ValueError): reuse(receipt, manifest, root/'rejected', root, '0'*64)
                    copy.assert_not_called()
                    self.assertFalse((root/'rejected').exists())
                tampered = root/'tampered.json'
                data = json.loads(receipt.read_text()); data['apks'][0]['role']='instrumentation'
                tampered.write_text(json.dumps(data))
                with self.assertRaises(ValueError): reuse(tampered, manifest, root/'rejected', root, hashlib.sha256(tampered.read_bytes()).hexdigest())
                apk.write_bytes(apk.read_bytes()+b'tamper')
                with self.assertRaises(ValueError): reuse(receipt, manifest, root/'rejected', root, receipt_hash)
                self.assertFalse((root/'rejected').exists())
                apk.write_bytes(paths[0].read_bytes())
                for key, value in [('abi','arm64-v8a'), ('testSignerSha256','c'*64), ('testedSha256','c'*64)]:
                    bad = dict(prep, **{key:value})
                    with self.assertRaises(ValueError): verify_prepared(apk, bad, root)
            for change in [dict(build_source_sha='other'), dict(configuration={'variant':'release'})]:
                with self.assertRaises(ValueError): reuse(receipt, dict(manifest, **change), root, root, receipt_hash)
            data = json.loads(receipt.read_text()); data['executionManifest']['package']='other'
            receipt.write_text(json.dumps(data))
            with self.assertRaises(ValueError): reuse(receipt, manifest, root, root, receipt_hash)

    def test_release_acceptance_rejects_locally_prepared_apks_before_reading(self):
        manifest = dict(stable_candidate_run=27, build_source_sha='d'*40, probeKind='ui')
        with patch('prepared_apks.Path.read_bytes') as read:
            with self.assertRaisesRegex(ValueError, 'authenticated producer archives'):
                reuse('unused.json', manifest, 'unused', 'unused', 'e'*64)
            read.assert_not_called()

    def test_original_archive_checks_digest_and_source_before_staging(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'apks').mkdir()
            data = io.BytesIO()
            with zipfile.ZipFile(data, 'w') as source:
                source.writestr('source-sha.txt', 'd' * 40)
                source.writestr('bridge.apk', b'original APK bytes')
            archive = data.getvalue()
            descriptor = dict(id=4, size_in_bytes=len(archive), source_sha='d' * 40,
                              digest='sha256:' + hashlib.sha256(archive).hexdigest())

            def download(command, *, stdout, **kwargs):
                stdout.write(archive)

            with patch('tor_differential_driver.subprocess.run', side_effect=download):
                for change, reason in ((dict(digest='sha256:' + '0' * 64), 'digest mismatch'),
                                       (dict(source_sha='e' * 40), 'source identity mismatch')):
                    with self.assertRaisesRegex(ValueError, reason):
                        fetch_baseline(root, dict(descriptor, **change), 'ui-bridge.apk')
                    self.assertFalse(list((root / 'apks').iterdir()))
                staged = fetch_baseline(root, descriptor, 'ui-bridge.apk')
                self.assertEqual(staged.read_bytes(), b'original APK bytes')
                self.assertFalse((root / 'baseline.zip').exists())

    def test_cached_pair_checks_both_original_apk_pins_before_copying(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            names = ('candidate.apk', 'candidate-androidTest.apk', 'baseline-ui.apk', 'baseline-androidTest.apk')
            paths = [root / name for name in names]
            for path in paths:
                path.write_bytes(path.name.encode())
            preparations = {path.name: dict(originalSha256='a' * 64,
                                           testedSha256=hashlib.sha256(path.read_bytes()).hexdigest())
                            for path in paths}
            for role in ('upgradeBaseline', 'upgradeBaselineInstrumentation'):
                expected = dict(build_source_sha='d' * 40, probeKind='core-testnet',
                                upgradeBaseline={'apk_sha256': 'a' * 64},
                                upgradeBaselineInstrumentation={'apk_sha256': 'a' * 64})
                wrong = dict(expected, **{role: {'apk_sha256': 'b' * 64}})
                receipt = root / (role + '.json')
                execution = dict(wrong, baseline_apk_sha256='a' * 64, baseline_instrumentation_apk_sha256='a' * 64)
                digest = write_receipt(receipt, wrong, execution, paths, preparations)
                with patch('prepared_apks.verify_prepared'), patch('prepared_apks.shutil.copyfile') as copy:
                    with self.assertRaisesRegex(ValueError, 'differs from its selected descriptor'):
                        reuse(receipt, wrong, root / 'rejected', root, digest)
                    copy.assert_not_called()
                destination = root / role
                destination.mkdir()
                valid = destination / 'valid.json'
                digest = write_receipt(valid, expected, expected, paths, preparations)
                with patch('prepared_apks.verify_prepared'):
                    reused, _, _ = reuse(valid, expected, destination, root, digest)
                self.assertEqual([path.read_bytes() for path in paths], [path.read_bytes() for path in reused])

    def test_active_identity_and_ledger(self):
        with tempfile.TemporaryDirectory() as temporary:
            state = Path(temporary).resolve(); root = state/'job-id'; root.mkdir()
            active = dict(job='id', owner='current-task', root=str(root), identity=identity(root))
            (state/'active.json').write_text(json.dumps(active))
            (state/'state.json').write_text(json.dumps({'roots':[dict(path=str(root),owner='current-task',identity=identity(root))]}))
            self.assertEqual(active_root(state, 'current-task')[0], root)
            with self.assertRaises(ValueError): active_root(state, 'wrong-task')
            with self.assertRaises(ValueError): active_root(state)
            (state/'state.json').write_text('{"roots":[]}')
            with self.assertRaises(ValueError): active_root(state, 'current-task')


if __name__ == '__main__': unittest.main()
