"""Packaging boundary regressions use tiny archives, never wallet state."""
import tempfile
import unittest
import zipfile
from pathlib import Path
from apk_architecture import check_apk


class PackagingTests(unittest.TestCase):
    def test_release_rejects_extra_abis_and_missing_engine(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / 'fixture.apk'
            for abis, valid in [(('arm64-v8a',), True),
                                (('arm64-v8a', 'armeabi-v7a'), False),
                                (('x86_64',), False), ((), False)]:
                with zipfile.ZipFile(apk, 'w') as archive:
                    for abi in abis:
                        archive.writestr(f'lib/{abi}/libzcashwalletsdk.so', b'fixture')
                if valid:
                    self.assertEqual(['arm64-v8a'], check_apk(apk)['abis'])
                else:
                    with self.assertRaises(ValueError):
                        check_apk(apk)


if __name__ == '__main__':
    unittest.main()
