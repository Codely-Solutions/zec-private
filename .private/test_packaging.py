"""Packaging boundary regressions use tiny archives, never wallet state."""
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from apk_architecture import check_apk
from compare_runtime import compare


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
        for job in (production, emulator):
            self.assertIn('!inputs.candidate_run', job)
            self.assertIn('ref: ${{ inputs.source_sha || github.sha }}', job)
            self.assertNotIn('secrets.', job)
            dependencies = [line.strip() for line in job.splitlines() if line.startswith('    needs:')]
            self.assertEqual(['needs: admit_hosted'], dependencies)
        self.assertIn("!inputs.hosted || needs.admit_hosted.result == 'success'", production)
        self.assertIn("inputs.build_emulator || (inputs.hosted && needs.admit_hosted.result == 'success')", emulator)
        self.assertIn('name: isolated-ui-repro-apk', emulator)
        self.assertNotIn('isolated-ui-repro-apk', production)
        self.assertIn("!inputs.build_emulator || needs.emulator.result == 'success'", publish)
        self.assertIn("needs.hosted_quotes.result == 'success' || (inputs.quote_run && needs.admit_hosted.outputs.quote_receipt_id != '')", publish)
        self.assertIn("needs.hosted_core.result == 'success' || (inputs.recover_run && needs.admit_hosted.result == 'success')", publish)
        self.assertIn('needs: [unsigned, emulator, promotion, testnet_apk, hosted_core, hosted_quotes, admit_hosted]', publish)
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
