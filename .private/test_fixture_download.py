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

if __name__=='__main__':unittest.main()
