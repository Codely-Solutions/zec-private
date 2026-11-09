import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile
from prepared_apks import reuse, write_receipt, export_prepared
from strip_emulator_apk import verify_prepared, signing_identity
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
