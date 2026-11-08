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

if __name__=='__main__':unittest.main()
