"""Host role selection controls; no Android guest, network or wallet setup."""
from pathlib import Path
import unittest
from types import SimpleNamespace
from core_testnet_driver import PACKAGE, RUNNER, instrumentation_role, select_apk_roles, verify_instrumentation_pair


class CoreInstrumentationPairingTest(unittest.TestCase):
    def test_baseline_and_candidate_roles_are_explicit(self):
        baseline = Path('baseline-ui.apk')
        old_test = Path('baseline-androidTest.apk')
        candidate = Path('candidate.apk')
        new_test = Path('candidate-androidTest.apk')
        self.assertEqual((baseline, old_test, candidate, new_test),
                         select_apk_roles([new_test, baseline, candidate, old_test]))
        for stage in ('networkTargets', 'recipient', 'sender', 'fund'):
            self.assertEqual('baseline', instrumentation_role(stage))
        for stage in ('upgrade', 'transport', 'transportProbe', 'send', 'restart', 'restore'):
            self.assertEqual('candidate', instrumentation_role(stage))

    def test_missing_or_ambiguous_test_and_unknown_stage_fail_closed(self):
        for names in (('baseline-ui.apk', 'candidate.apk', 'current-androidTest.apk'),
                      ('baseline-ui.apk', 'baseline-androidTest.apk', 'one-androidTest.apk', 'two-androidTest.apk')):
            with self.assertRaises((ValueError, KeyError)):
                select_apk_roles([Path(name) for name in names])
        with self.assertRaises(ValueError): instrumentation_role('unreviewedStage')

    def test_installed_version_test_hash_and_runner_are_all_required(self):
        installed_path = '/data/app/~~AbC_123==/' + PACKAGE + '.test-Zyx_456==/base.apk'
        responses = {
            ('dumpsys', 'package', PACKAGE): 'versionCode=3234017',
            ('pm', 'path', PACKAGE + '.test'): 'package:' + installed_path,
            ('sha256sum', installed_path): 'a'*64 + '  ' + installed_path,
            ('pm', 'list', 'instrumentation', PACKAGE): 'instrumentation:' + RUNNER + ' (target=' + PACKAGE + ')',
        }
        def adb(command, *args, **kwargs):
            self.assertEqual('shell', command)
            return SimpleNamespace(stdout=responses[args].encode())
        self.assertTrue(verify_instrumentation_pair(adb, 3234017, 'a'*64)['installed_hash_verified'])
        for key, wrong in ((('dumpsys', 'package', PACKAGE), 'versionCode=3234016'),
                           (('sha256sum', installed_path), 'b'*64 + '  ' + installed_path),
                           (('pm', 'path', PACKAGE + '.test'), 'package:' + installed_path + '\npackage:/data/app/other/split.apk'),
                           (('pm', 'path', PACKAGE + '.test'), 'package:/data/local/tmp/test/base.apk'),
                           (('pm', 'path', PACKAGE + '.test'), 'package:/data/app/../' + PACKAGE + '.test/base.apk'),
                           (('pm', 'path', PACKAGE + '.test'), 'package:/data/app/safe/../base.apk'),
                           (('pm', 'path', PACKAGE + '.test'), 'package:' + installed_path + ';id'),
                           (('pm', 'list', 'instrumentation', PACKAGE), 'instrumentation:wrong (target=other)')):
            original = responses[key]
            try:
                responses[key] = wrong
                with self.assertRaises(RuntimeError): verify_instrumentation_pair(adb, 3234017, 'a'*64)
            finally:
                responses[key] = original


if __name__ == '__main__':
    unittest.main()
