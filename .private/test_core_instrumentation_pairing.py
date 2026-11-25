"""Host role selection controls; no Android guest, network or wallet setup."""
from contextlib import contextmanager
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch
import core_testnet_driver as driver
from acceptance_policy import validate_core
from core_testnet_driver import (
    PACKAGE, RUNNER, claim_after_readiness, installed_apk_hash, instrumentation_role, run, select_apk_roles,
    verify_instrumentation_pair, wait_for_preclaim_marker,
)
from test_support import patch_environment


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
                     'preclaim_check', 'preclaim_only', 'live_tor_probe', 'tor_mode_preparation'):
            with self.subTest(flag=flag):
                adb = Mock()
                options = {'use_tor': False, flag: True}
                with self.assertRaisesRegex(ValueError, 'direct unfunded path'):
                    run(adb, None, None, None, installed_upgrade=True, **options)
                adb.assert_not_called()

    def test_candidate_only_rejects_upgrade_and_probe_modes_before_adb(self):
        for flag in ('use_tor', 'transport_only', 'repeat_cold_start', 'upgrade_probe',
                     'preclaim_only', 'live_tor_probe', 'installed_upgrade', 'tor_mode_preparation'):
            with self.subTest(flag=flag):
                adb = Mock()
                options = {'use_tor': False, flag: True}
                with self.assertRaisesRegex(ValueError, 'Candidate-only lifecycle requires direct testnet'):
                    run(adb, None, None, None, candidate_only=True, **options)
                adb.assert_not_called()
        with self.assertRaisesRegex(ValueError, 'cannot be bound to an upgrade baseline'):
            run(Mock(), None, None, {'baseline_version_code': 1}, candidate_only=True, use_tor=False)
        with self.assertRaisesRegex(ValueError, 'Exactly four'):
            run(Mock(), None, [Path('candidate.apk'), Path('candidate-androidTest.apk')], {}, use_tor=False)

    def test_candidate_only_requires_ledger_outside_guard(self):
        with tempfile.TemporaryDirectory() as temp:
            guard = Path(temp)
            root = guard/'guest'
            pair = [Path('candidate.apk'), Path('candidate-androidTest.apk')]
            for ledger in ('', str(guard/'claim.json')):
                with self.subTest(ledger=ledger), patch_environment({
                        'CORE_FAUCET_LEDGER': ledger, 'CODEX_GUARD_OUTPUT': str(guard)}):
                    with self.assertRaisesRegex(ValueError, 'durable faucet ledger outside'):
                        run(Mock(), root, pair, {}, candidate_only=True, use_tor=False)

    def test_candidate_only_checks_readiness_then_funds_and_restores_without_upgrade(self):
        events = []
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)/'guard'
            root.mkdir()
            app, test = root/'candidate.apk', root/'candidate-androidTest.apk'
            app.write_bytes(b'app')
            test.write_bytes(b'test')
            app_hash = hashlib.sha256(app.read_bytes()).hexdigest()
            manifest = dict(candidate_apk_sha256='a'*64, instrumentation_apk_sha256='b'*64,
                            build_source_sha='c'*40, candidate_version_code=3234033,
                            input_fingerprint='d'*64, runtime_input_fingerprint='e'*64,
                            build_input_fingerprint='f'*64)

            def adb_reply(command, *args, **kwargs):
                responses = {('id',): b'uid=0', ('run-as', PACKAGE, 'id', '-u'): b'10001',
                             ('run-as', PACKAGE, 'cat', 'files/core-private.json'): b'{"senderAddress":"isolated"}'}
                if command == 'install' or args[:2] == ('pm', 'clear'):
                    events.append((command, args))
                return SimpleNamespace(stdout=responses.get(args, b''))

            def start_stage(command, **kwargs):
                stage = command[command.index('stage')+1]
                events.append(stage)
                receipt = json.dumps(dict(stage=stage, passed=True, network='zcash-testnet',
                                          tor_enabled=False, transport='direct'))
                output = ('INSTRUMENTATION_STATUS: zec_core_receipt='+receipt+'\nOK (1 test)').encode()
                return SimpleNamespace(returncode=0, communicate=lambda **_: (output, b''))

            @contextmanager
            def ready(_root, *, post_by):
                self.assertEqual(post_by, 700)
                events.append('faucet-readiness')

                def fund(address):
                    self.assertEqual(address, 'isolated')
                    events.append('claim')
                    return dict(txid='a'*64, provider='synthetic', claim_requests=1)

                yield dict(passed=True, claim_requests=0), fund

            adb = Mock(side_effect=adb_reply)
            adb.command_base, adb.environment = [], {}
            with patch_environment({'CORE_FAUCET_LEDGER': str(root.parent/'claim.json'),
                                    'CODEX_GUARD_OUTPUT': str(root)}), \
                    patch.object(driver, 'faucet_ready_session', ready), \
                    patch.object(driver, 'installed_apk_hash', return_value=app_hash), \
                    patch.object(driver, 'verify_instrumentation_pair') as verify, \
                    patch.object(driver.time, 'monotonic', return_value=100), \
                    patch.object(driver.subprocess, 'Popen', side_effect=start_stage), \
                    patch.object(driver.subprocess, 'run'):
                run(adb, root, [test, app], manifest, use_tor=False, candidate_only=True)
            self.assertEqual([entry for entry in events if isinstance(entry, str)],
                             ['receiptProbe', 'faucet-readiness', 'recipient', 'restoreProbe', 'sender',
                              'claim', 'fund', 'send', 'restart', 'restore'])
            self.assertEqual(sum(entry[0] == 'install' for entry in events if isinstance(entry, tuple)), 2)
            self.assertEqual(sum(entry[0] == 'shell' for entry in events if isinstance(entry, tuple)), 3)
            self.assertEqual(verify.call_count, 8)
            for call in verify.call_args_list:
                self.assertEqual(call.args[1:], (3234033, hashlib.sha256(test.read_bytes()).hexdigest()))
            report = json.loads((root/'core-testnet-result.json').read_bytes())
            self.assertTrue(report['passed'])
            self.assertEqual(report['evidence_class'], 'candidate-only-testnet-lifecycle')
            self.assertEqual(report['faucet_claims'], 1)
            self.assertEqual(report['preclaim_readiness']['role'], 'candidate')
            self.assertNotIn('baseline_version_code', report)
            self.assertNotIn('baseline', report['instrumentation_pairing'])
            self.assertNotIn('upgrade', report['stages'])
            with self.assertRaisesRegex(ValueError, 'Real testnet app evidence required'):
                validate_core(report, manifest['input_fingerprint'])


if __name__ == '__main__':
    unittest.main()
