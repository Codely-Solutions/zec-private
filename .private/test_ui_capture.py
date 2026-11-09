from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from ui_capture import capture_scenes, validate_request

class CaptureTest(unittest.TestCase):
    def test_capture_only_cannot_replace_acceptance(self):
        for manifest in (dict(probeKind='core-testnet',captureOnly=True,captureScenes=[{}]),
                         dict(probeKind='ui',captureOnly=True,residentUiBridge={},captureScenes=[]),
                         dict(probeKind='ui',captureOnly=True,residentUiBridge={'id':1},captureScenes=[{}])):
            with self.assertRaises(ValueError): validate_request(manifest)
        validate_request(dict(probeKind='ui',captureOnly=True,captureScenes=[{}]))

    def fake(self, fail=False):
        def adb(*args, **kwargs):
            data=b''
            if args[:3]==('shell','am','start'):
                return SimpleNamespace(returncode=int(fail),stdout=b'Error: launch' if fail else b'Status: ok',stderr=b'')
            if args[:2]==('shell','cat'):
                data=b'<hierarchy><node package="com.personal.zecprivate.foss.debug" text="SWAP More" bounds="[0,0][100,100]" /></hierarchy>'
            if args[:2]==('exec-out','screencap'): data=b'\x89PNG\r\n\x1a\nfixture'
            return SimpleNamespace(returncode=0,stdout=data,stderr=b'')
        return adb
    def test_multiple_scenes_and_immutable_evidence(self):
        with tempfile.TemporaryDirectory() as d:
            scenes=[dict(screen='recipient-swap-mismatch',theme=t,expected='SWAP') for t in ('light','dark')]
            self.assertEqual(2,len(capture_scenes(self.fake(),scenes,d,settle=lambda _:None)))
            with self.assertRaises(ValueError): capture_scenes(self.fake(),scenes,d,settle=lambda _:None)
    def test_existing_scene_interactions(self):
        with tempfile.TemporaryDirectory() as d:
            scenes=[dict(screen='review',theme='light',expected='SWAP',expand_more=True,tap_text='More',swipe=True)]
            self.assertEqual(1,len(capture_scenes(self.fake(),scenes,d,settle=lambda _:None)))

    def test_launch_failure_is_recorded_without_later_scenes(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(RuntimeError):
                capture_scenes(self.fake(True),[dict(screen='first',theme='oled')],d,settle=lambda _:None)
            self.assertTrue((Path(d)/'first-oled-launch.txt').exists())
            self.assertFalse((Path(d)/'captures.jsonl').exists())
    def test_bad_name_and_missing_target_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(ValueError): capture_scenes(self.fake(),[dict(screen='x',theme='light',name='../x')],d)
            with self.assertRaises(RuntimeError): capture_scenes(self.fake(),[dict(screen='x',theme='dark',expected='Missing')],d,settle=lambda _:None)

if __name__=='__main__':unittest.main()
