"""Fixture transport preserves digest/source validation for both storage paths."""
import hashlib,io,json,tempfile,unittest,zipfile
from pathlib import Path
from unittest.mock import patch
from tor_differential_driver import fetch_baseline

class FixtureDownloadTests(unittest.TestCase):
    def archive(self):
        data=io.BytesIO()
        with zipfile.ZipFile(data,'w') as z:
            z.writestr('baseline.apk',b'synthetic unsigned test bytes')
            z.writestr('source-sha.txt','a'*40+'\n')
        return data.getvalue()

    def test_maintenance_asset_is_authenticated_and_source_bound(self):
        raw=self.archive();manifest=dict(release_asset_id=123,size_in_bytes=len(raw),digest='sha256:'+hashlib.sha256(raw).hexdigest(),source_sha='a'*40)
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'apks').mkdir()
            def download(command,**kwargs):
                self.assertIn('Accept: application/octet-stream',command)
                self.assertTrue(command[-1].endswith('/releases/assets/123'))
                kwargs['stdout'].write(raw)
            with patch('tor_differential_driver.subprocess.run',side_effect=download):
                self.assertEqual(fetch_baseline(root,manifest).read_bytes(),b'synthetic unsigned test bytes')
            manifest['digest']='sha256:'+'0'*64
            with patch('tor_differential_driver.subprocess.run',side_effect=download),self.assertRaises(AssertionError):
                fetch_baseline(root,manifest)

    def test_action_archive_still_rejects_wrong_source(self):
        raw=self.archive();manifest=dict(id=456,size_in_bytes=len(raw),digest='sha256:'+hashlib.sha256(raw).hexdigest(),source_sha='b'*40)
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'apks').mkdir()
            def download(command,**kwargs):
                self.assertTrue(command[-1].endswith('/actions/artifacts/456/zip'))
                kwargs['stdout'].write(raw)
            with patch('tor_differential_driver.subprocess.run',side_effect=download),self.assertRaisesRegex(AssertionError,'source'):
                fetch_baseline(root,manifest)

    def test_pair_archive_selects_app_or_exact_source_matched_instrumentation(self):
        data=io.BytesIO()
        app=b'old app';test=b'version matched test'
        with zipfile.ZipFile(data,'w') as z:
            z.writestr('app.apk',app)
            z.writestr('app-androidTest.apk',test)
            z.writestr('source-sha.txt','a'*40+'\n')
        raw=data.getvalue()
        manifest=dict(id=456,size_in_bytes=len(raw),digest='sha256:'+hashlib.sha256(raw).hexdigest(),
                      source_sha='a'*40,apk_sha256=hashlib.sha256(test).hexdigest())
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'apks').mkdir()
            def download(command,**kwargs):kwargs['stdout'].write(raw)
            with patch('tor_differential_driver.subprocess.run',side_effect=download):
                self.assertEqual(app,fetch_baseline(root,{k:v for k,v in manifest.items() if k!='apk_sha256'}).read_bytes())
                self.assertEqual(test,fetch_baseline(root,manifest,'baseline-androidTest.apk',instrumentation=True).read_bytes())
                for change in ({'source_sha':'b'*40},{'apk_sha256':'0'*64}):
                    with self.subTest(change=change),self.assertRaises(AssertionError):
                        fetch_baseline(root,dict(manifest,**change),'baseline-androidTest.apk',instrumentation=True)
            self.assertFalse((root/'baseline.zip').exists())

    def test_instrumentation_requires_source_digest_and_exactly_one_test_apk(self):
        raw=self.archive();manifest=dict(id=456,size_in_bytes=len(raw),digest='sha256:'+hashlib.sha256(raw).hexdigest(),
                                         source_sha='a'*40,apk_sha256='1'*64)
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'apks').mkdir()
            for missing in ('source_sha','apk_sha256'):
                value={k:v for k,v in manifest.items() if k!=missing}
                with self.subTest(missing=missing),patch('tor_differential_driver.subprocess.run') as download:
                    with self.assertRaises(ValueError):fetch_baseline(root,value,instrumentation=True)
                    download.assert_not_called()
            def download(command,**kwargs):kwargs['stdout'].write(raw)
            with patch('tor_differential_driver.subprocess.run',side_effect=download),self.assertRaisesRegex(AssertionError,'selected'):
                fetch_baseline(root,manifest,instrumentation=True)
            self.assertFalse((root/'baseline.zip').exists())

    def test_preservation_requires_both_original_executed_apk_hashes(self):
        from preserve_upgrade_fixture import fixture_apks
        app=b'original app';test=b'original instrumentation'
        core=dict(build_source_sha='a'*40,candidate_apk_sha256=hashlib.sha256(app).hexdigest(),
                  instrumentation_apk_sha256=hashlib.sha256(test).hexdigest())
        def contents(extra=False,include_test=True):
            data=io.BytesIO()
            with zipfile.ZipFile(data,'w') as z:
                z.writestr('nested/app.apk',app)
                if include_test:z.writestr('nested/app-androidTest.apk',test)
                if extra:z.writestr('other-androidTest.apk',test)
                z.writestr('source-sha.txt','a'*40+'\n')
            return zipfile.ZipFile(io.BytesIO(data.getvalue()))
        with contents() as archive:
            self.assertEqual({'app.apk':app,'app-androidTest.apk':test},fixture_apks(archive,core))
        for change in ({'build_source_sha':'b'*40},{'candidate_apk_sha256':'0'*64},
                       {'instrumentation_apk_sha256':'0'*64}):
            with self.subTest(change=change),contents() as archive,self.assertRaises(ValueError):
                fixture_apks(archive,dict(core,**change))
        for options in ({'include_test':False},{'extra':True}):
            with self.subTest(options=options),contents(**options) as archive,self.assertRaises(ValueError):
                fixture_apks(archive,core)

    def test_local_preservation_verifies_the_public_record_before_reading_or_publishing_a_fixture(self):
        from preserve_upgrade_fixture import preserve
        from test_support import patch_environment, public_baseline_metadata

        tag = 'v3.19.0-private.30'
        selected = dict(release=tag, source_sha='a' * 40, version_code=330)
        proof = dict(release_tag=tag, apk_sha256='b' * 64, source_sha=selected['source_sha'], versionCode=330)
        public = public_baseline_metadata(selected, proof['apk_sha256'])
        release = public['/releases/latest']
        release['assets'][1]['digest'] = 'sha256:' + '0' * 64
        with tempfile.TemporaryDirectory() as root, patch_environment({'CODEX_GUARD_OUTPUT': root}), \
                patch('hosted_fixture.recorded_pair', return_value=(proof, {})), \
                patch('preserve_upgrade_fixture.api', return_value=release), \
                patch('preserve_upgrade_fixture.gh') as gh:
            with self.assertRaisesRegex(ValueError, 'release assets differ'):
                preserve(tag)
            self.assertEqual([], list(Path(root).iterdir()))
            gh.assert_not_called()

    def test_local_preservation_can_read_the_exact_immutable_registry_pair(self):
        from hosted_fixture import REGISTRY
        from preserve_upgrade_fixture import digest, retained_hosted_pair, write_seedless_pair

        tag, source = 'v3.19.0-private.30', 'a' * 40
        payloads = {'wallet.apk': b'app', 'wallet-androidTest.apk': b'test'}
        core = dict(build_source_sha=source, candidate_version_code=330,
                    candidate_apk_sha256=hashlib.sha256(payloads['wallet.apk']).hexdigest(),
                    instrumentation_apk_sha256=hashlib.sha256(payloads['wallet-androidTest.apk']).hexdigest())
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / '.private').mkdir()
            archive = root / 'fixture.zip'
            write_seedless_pair(archive, payloads, source)
            reference = REGISTRY + '@sha256:' + 'b' * 64
            manifest = dict(release=tag, source_sha=source, version_code=330, reference=reference,
                            size_in_bytes=archive.stat().st_size, digest=digest(archive),
                            candidate_apk_sha256=core['candidate_apk_sha256'],
                            instrumentation_apk_sha256=core['instrumentation_apk_sha256'])
            (root / '.private/hosted-fixture.json').write_text(json.dumps(manifest))
            self.assertEqual((payloads, reference), retained_hosted_pair(tag, core, archive, root))
            with self.assertRaisesRegex(ValueError, 'exact recorded pair'):
                retained_hosted_pair(tag, dict(core, candidate_version_code=331), archive, root)
            archive.write_bytes(bytes([0]) + archive.read_bytes()[1:])
            with self.assertRaisesRegex(ValueError, 'exact recorded pair'):
                retained_hosted_pair(tag, core, archive, root)

    def test_hosted_pair_is_deterministic_and_rejects_unexpected_contents(self):
        from hosted_fixture import verify_pair
        from preserve_upgrade_fixture import write_seedless_pair

        app = b'accepted app'
        test = b'accepted instrumentation'
        payloads = {'app.apk': app, 'app-androidTest.apk': test}
        core = dict(build_source_sha='a'*40,
                    candidate_apk_sha256=hashlib.sha256(app).hexdigest(),
                    instrumentation_apk_sha256=hashlib.sha256(test).hexdigest())
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            first, second = root/'first.zip', root/'second.zip'
            write_seedless_pair(first, payloads, core['build_source_sha'])
            write_seedless_pair(second, dict(reversed(list(payloads.items()))), core['build_source_sha'])
            self.assertEqual(first.read_bytes(), second.read_bytes())
            self.assertEqual(payloads, verify_pair(first, core, prepared=True))
            with self.assertRaisesRegex(ValueError, 'APK mismatch'):
                verify_pair(first, dict(core, instrumentation_apk_sha256='0'*64), prepared=True)
            with zipfile.ZipFile(first, 'a') as archive:
                archive.writestr('wallet-data.json', '{}')
            with self.assertRaisesRegex(ValueError, 'Unexpected fixture entries'):
                verify_pair(first, core, prepared=True)

    def test_scheduled_fixture_recovery_selects_only_an_unpreserved_recorded_pair(self):
        from hosted_fixture import pending_pair
        from release_metadata import Metadata

        tag = 'v3.19.0-private.22'
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            records = root / '.private/release-evidence'
            records.mkdir(parents=True)
            selected = root / '.private/hosted-fixture.json'
            selected.write_text(json.dumps(dict(release='v3.19.0-private.21', version_code=21)))
            with patch('hosted_fixture.Metadata.api', return_value={'tag_name': tag}):
                metadata = Metadata()
                self.assertEqual(pending_pair(metadata, root), '')
                record = records / (tag + '.json')
                record.write_text(json.dumps(dict(versionCode=22, core=dict(candidate_version_code=21))))
                self.assertEqual(pending_pair(metadata, root), '')
                record.write_text(json.dumps(dict(release_tag=tag, versionCode=22, core=dict(
                    candidate_version_code=22, passed=True, real_funds_used=False, personal_wallet_access=False))))
                self.assertEqual(pending_pair(metadata, root), tag)
                selected.write_text(json.dumps(dict(release=tag, version_code=22)))
                self.assertEqual(pending_pair(metadata, root), '')

    def test_hosted_binding_record_refuses_replacement_and_downgrade(self):
        from hosted_fixture import REGISTRY, record

        apk = 'a'*64
        core = dict(build_source_sha='b'*40, candidate_version_code=22,
                    candidate_apk_sha256=apk, instrumentation_apk_sha256='c'*64)
        binding = dict(release='v3.19.0-private.22', source_sha=core['build_source_sha'],
                       version_code=22, candidate_apk_sha256=apk,
                       instrumentation_apk_sha256=core['instrumentation_apk_sha256'],
                       reference=REGISTRY+'@sha256:'+'d'*64, digest='sha256:'+'e'*64, size_in_bytes=100)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root/'.private').mkdir()
            selected = root/'.private/hosted-fixture.json'
            selected.write_text('{"version_code":21}')
            from test_support import public_baseline_metadata

            public = public_baseline_metadata(binding, apk)
            proof = dict(release_tag=binding['release'], apk_sha256=apk,
                         versionCode=binding['version_code'], source_sha=binding['source_sha'])
            with patch('hosted_fixture.recorded_pair', return_value=(proof, core)), \
                    patch('hosted_fixture.Metadata.api', side_effect=public.__getitem__):
                from release_metadata import Metadata

                metadata = Metadata()
                public['/releases/latest']['body'] += ' '
                with self.assertRaisesRegex(ValueError, 'version differs'):
                    record(binding, metadata, root)
                self.assertEqual({'version_code': 21}, json.loads(selected.read_text()))
                public['/releases/latest']['body'] = public['/releases/latest']['body'].rstrip()
                self.assertTrue(record(binding, metadata, root))
                self.assertEqual(binding, json.loads(selected.read_text()))
                self.assertFalse(record(binding, metadata, root))
                with self.assertRaisesRegex(ValueError, 'replace or downgrade'):
                    record(dict(binding, digest='sha256:'+'f'*64), metadata, root)

if __name__=='__main__':unittest.main()
