"""Packaging boundary regressions use tiny archives, never wallet state."""
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import Mock
from apk_architecture import check_apk
from compare_runtime import compare
from signing_wiring import environment_wiring


class PackagingTests(unittest.TestCase):
    def test_reviewed_runtime_upgrades_do_not_allow_rollback_or_unreviewed_drift(self):
        baseline = json.loads((Path(__file__).resolve().parent / 'phase1-runtime-baseline.json').read_text())
        components = list(baseline['components'])
        for key, update in baseline['reviewedVersionUpdates'].items():
            components.remove(key + ':' + update['from'])
            components.append(key + ':' + update['to'])
        self.assertTrue(compare({'components': components})['retainedVersionsUnchanged'])
        for version in ('33.5.0-android', '99.0.0-android'):
            invalid = [c for c in components if not c.startswith('com.google.guava:guava:')]
            invalid.append('com.google.guava:guava:' + version)
            with self.subTest(version=version), self.assertRaises(RuntimeError):
                compare({'components': invalid})

    def test_every_reviewed_runtime_upgrade_rejects_its_original_version_and_unknown_drift(self):
        baseline = json.loads((Path(__file__).resolve().parent / 'phase1-runtime-baseline.json').read_text())
        versions = {c.rsplit(':', 1)[0]: c.rsplit(':', 1)[1]
                    for c in baseline['components'] if c.count(':') == 2}
        for key, update in baseline['reviewedVersionUpdates'].items():
            self.assertEqual(versions[key], update['from'])
            self.assertNotEqual(update['from'], update['to'])
            versions[key] = update['to']
        for key, update in baseline['reviewedVersionUpdates'].items():
            for invalid_version in (update['from'], update['to'] + '-unreviewed'):
                candidate = dict(versions)
                candidate[key] = invalid_version
                with self.subTest(component=key, version=invalid_version), self.assertRaises(RuntimeError):
                    compare({'components': [k + ':' + v for k, v in candidate.items()]})

    def test_candidate_variants_are_independent_and_promotion_builds_neither(self):
        root = Path(__file__).resolve().parents[1]
        workflow = (root / '.github/workflows/build-release.yml').read_text()
        production = workflow.split('  unsigned:\n', 1)[1].split('  emulator:\n', 1)[0]
        emulator = workflow.split('  emulator:\n', 1)[1].split('  testnet_apk:\n', 1)[0]
        publish = workflow.split('  publish:\n', 1)[1].split('  reconcile:\n', 1)[0]
        testnet = workflow.split('  testnet_apk:\n', 1)[1].split('  hosted_core:\n', 1)[0]
        preflight = workflow.split('  preflight:\n', 1)[1].split('  signing_smoke:\n', 1)[0]
        for job in (production, emulator, testnet):
            self.assertIn('!inputs.candidate_run', job)
            self.assertNotIn('secrets.', job)
            dependencies = [line.strip() for line in job.splitlines() if line.startswith('    needs:')]
            self.assertEqual(['needs: [preflight, signing_smoke]'], dependencies)
            self.assertIn("needs.preflight.result == 'success'", job)
            self.assertIn("needs.signing_smoke.result == 'success'", job)
            self.assertNotIn('.private/preflight.py', job)
        for job in (production, emulator):
            self.assertIn('ref: ${{ inputs.source_sha || github.sha }}', job)
        self.assertIn('.private/preflight.py', preflight)
        self.assertIn('--dependency-verification strict', preflight)
        self.assertIn('help detektAll', preflight)
        self.assertIn('!inputs.preflight_only', publish)
        self.assertIn('name: isolated-ui-repro-apk', emulator)
        self.assertNotIn('isolated-ui-repro-apk', production)
        self.assertIn("!inputs.build_emulator || needs.emulator.result == 'success'", publish)
        self.assertIn("needs.recovery_quotes.result == 'success' || needs.admit_hosted.outputs.quote_receipt_id != ''", publish)
        self.assertIn("needs.hosted_core.result == 'success' || needs.admit_hosted.outputs.core_receipt_id != ''", publish)
        hosted_core = workflow.split('  hosted_core:\n', 1)[1].split('  hosted_quotes:\n', 1)[0]
        self.assertIn('!cancelled()', hosted_core)
        self.assertIn("needs.signing_smoke.result == 'skipped' && needs.preflight.outputs.signing_changed != 'true'", hosted_core)
        self.assertIn("needs.unsigned.result == 'success' && needs.testnet_apk.result == 'success'", hosted_core)
        self.assertIn('needs: [preflight, signing_smoke, unsigned, emulator, promotion, testnet_apk, hosted_core, hosted_quotes, recovery_quotes, admit_hosted]', publish)
        self.assertIn('environment: production-signing', publish)
        for caller in ('custom-release.yml', 'sync-upstream.yml'):
            caller_workflow = (root / '.github/workflows' / caller).read_text()
            for secret in ('RELEASE_KEYSTORE_BASE64', 'RELEASE_STORE_PASSWORD', 'RELEASE_KEY_PASSWORD',
                           'MAINTENANCE_APP_PRIVATE_KEY'):
                self.assertIn(secret + ': ${{ secrets.' + secret + ' }}', caller_workflow)
        self.assertIn("needs.emulator.result == 'failure'", workflow.split('  failed:\n', 1)[1])
        release = (root / '.private/release_checks.sh').read_text()
        debug = (root / '.private/emulator_checks.sh').read_text()
        self.assertNotIn('FossDebug', release)
        self.assertNotIn('BUILD_EMULATOR', release)
        self.assertEqual(1, release.count('./gradlew '))
        self.assertNotIn('detektAll', release)
        self.assertEqual(1, debug.count('./gradlew '))
        self.assertIn(':app:assembleZcashmainnetFossRelease', release)
        self.assertIn(':ui-lib:testZcashmainnetFossReleaseUnitTest', release)
        self.assertIn(':wallet-foundation-lib:test', release)
        self.assertIn(':feature-ledger:testZcashmainnetFossReleaseUnitTest', release)
        self.assertIn(':app:assembleZcashmainnetFossDebug', debug)
        self.assertNotIn('FossRelease', debug)
        self.assertIn('source-sha.txt', debug)
        self.assertIn('SHA256SUMS', debug)
        for script in (release, debug):
            self.assertIn('-I .private/task-timing.gradle', script)
        self.assertIn('artifact/task-timing.jsonl', production)
        self.assertIn('artifact/task-timing.jsonl', emulator)

    def test_signing_policy_rejects_tags_and_wider_branch_access(self):
        metadata = Mock()
        metadata.api.return_value = {'id': 5, 'protection_rules': [], 'deployment_branch_policy': {
            'protected_branches': False, 'custom_branch_policies': True}}
        main = {'name': 'main', 'type': 'branch'}
        for policies in ([main, {'name': 'main', 'type': 'tag'}], [{'name': '*', 'type': 'branch'}], []):
            with self.subTest(policies=policies), self.assertRaisesRegex(RuntimeError, 'main branch only'):
                metadata.pages.return_value = policies
                environment_wiring(metadata)
        metadata.pages.return_value = [main]
        self.assertEqual([main], environment_wiring(metadata)['branches'])

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
