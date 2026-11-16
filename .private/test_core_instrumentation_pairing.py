"""Host role selection controls; no Android guest, network or wallet setup."""
from pathlib import Path
import unittest
from types import SimpleNamespace
from unittest.mock import Mock
from core_testnet_driver import (
    PACKAGE, RUNNER, claim_after_readiness, installed_apk_hash, instrumentation_role, run, select_apk_roles,
    verify_instrumentation_pair, wait_for_preclaim_marker,
)


class CoreInstrumentationPairingTest(unittest.TestCase):
    def test_missing_or_failed_readiness_never_calls_faucet(self):
        for marker in (b'{"ready":true,"probe_only":false}', b'{}', b'null', b'incomplete'):
            with self.subTest(marker=marker):
                adb = Mock(return_value=SimpleNamespace(returncode=0, stdout=marker))
                operation = Mock()
                operation.poll.return_value = 1
                faucet = Mock()

                def require_readiness():
                    if not wait_for_preclaim_marker(adb, operation, 1):
                        raise RuntimeError('preclaim_readiness')

                with self.assertRaisesRegex(RuntimeError, 'preclaim_readiness'):
                    claim_after_readiness(require_readiness, faucet)
                faucet.assert_not_called()

    def test_readiness_precedes_the_claim(self):
        steps = []
        claim_after_readiness(lambda: steps.append('app Tor ready'), lambda: steps.append('POST'))
        self.assertEqual(steps, ['app Tor ready', 'POST'])

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

    def test_installed_app_hash_requires_exact_package_path(self):
        path = '/data/app/~~AbC_123==/' + PACKAGE + '-Zyx_456==/base.apk'
        shell = Mock(side_effect=['package:' + path, 'a' * 64 + '  ' + path])
        self.assertEqual(installed_apk_hash(shell, PACKAGE), 'a' * 64)
        shell.assert_called_with('sha256sum', path)
        shell = Mock(return_value='package:' + path.replace(PACKAGE, PACKAGE + '.test'))
        with self.assertRaises(RuntimeError):
            installed_apk_hash(shell, PACKAGE)
        shell.assert_called_once_with('pm', 'path', PACKAGE)

    def test_installed_upgrade_rejects_any_other_mode_before_adb(self):
        for flag in ('use_tor', 'transport_only', 'repeat_cold_start', 'upgrade_probe',
                     'preclaim_check', 'preclaim_only', 'live_tor_probe'):
            with self.subTest(flag=flag):
                adb = Mock()
                options = {'use_tor': False, flag: True}
                with self.assertRaisesRegex(ValueError, 'direct unfunded path'):
                    run(adb, None, None, None, installed_upgrade=True, **options)
                adb.assert_not_called()


if __name__ == '__main__':
    unittest.main()
