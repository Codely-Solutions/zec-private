"""Packaging boundary regressions use tiny archives, never wallet state."""
import hashlib
import json
import runpy
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from contextlib import chdir
from unittest.mock import Mock, patch
from apk_architecture import check_apk
from compare_runtime import compare
from signing_wiring import PATHS, environment_wiring, fingerprint, main as select_signing_smoke
from test_maintenance_watch import job_activates, workflow_job
from test_support import patch_environment


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
        publish = workflow.split('  publish:\n', 1)[1].split('  resume_draft:\n', 1)[0]
        testnet = workflow.split('  testnet_apk:\n', 1)[1].split('  hosted_core:\n', 1)[0]
        preflight = workflow.split('  preflight:\n', 1)[1].split('  signing_smoke:\n', 1)[0]
        for job in (production, emulator, testnet):
            self.assertIn('!inputs.candidate_run', job)
            self.assertNotIn('secrets.', job)
            dependencies = [line.strip() for line in job.splitlines() if line.startswith('    needs:')]
            self.assertEqual(['needs: [admit_hosted, preflight, signing_smoke, quote_bridge]' if job is production else
                              'needs: [preflight, signing_smoke, quote_bridge]'], dependencies)
            self.assertIn("needs.preflight.result == 'success'", job)
            self.assertIn("!inputs.hosted || inputs.preflight_only || needs.quote_bridge.result == 'success'", job)
            self.assertIn("needs.signing_smoke.result == 'success'", job)
            self.assertNotIn('.private/preflight.py', job)
        for job in (production, emulator):
            self.assertIn('ref: ${{ inputs.source_sha || github.sha }}', job)
        self.assertIn("Unfunded NEAR API contracts over Tor without credentials\n"
                      "        if: ${{ !inputs.preflight_only }}", production)
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
        self.assertIn('needs: [preflight, signing_smoke, unsigned, emulator, promotion, testnet_apk, hosted_core, hosted_quotes, recovery_quotes, admit_hosted, admit]', publish)
        self.assertIn('environment: production-signing', publish)
        for caller in ('custom-release.yml', 'sync-upstream.yml'):
            caller_workflow = (root / '.github/workflows' / caller).read_text()
            for secret in ('RELEASE_KEYSTORE_BASE64', 'RELEASE_STORE_PASSWORD', 'RELEASE_KEY_PASSWORD',
                           'MAINTENANCE_APP_PRIVATE_KEY'):
                self.assertIn(secret + ': ${{ secrets.' + secret + ' }}', caller_workflow)
        self.assertTrue(job_activates(workflow_job('build-release.yml', 'failed'), {'emulator': 'failure'}))
        release = (root / '.private/release_checks.sh').read_text()
        debug = (root / '.private/emulator_checks.sh').read_text()
        self.assertNotIn('FossDebug', release)
        self.assertNotIn('BUILD_EMULATOR', release)
        self.assertEqual(1, release.count('./gradlew '))
        self.assertNotIn('detektAll', release)
        self.assertEqual(1, debug.count('./gradlew '))
        self.assertIn(':app:assembleZcashmainnetFossRelease', release)
        self.assertIn(':ui-lib:testZcashmainnetFossReleaseUnitTest', release)
        self.assertIn(':ui-design-lib:testReleaseUnitTest', release)
        self.assertNotIn('--tests', release)
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

    def test_promotion_rechecks_retained_unsigned_artifact_at_admission(self):
        source, controller = 'a'*40, 'b'*40
        first = dict(id=42, path='.github/workflows/build-release.yml', head_branch='main',
                     head_sha=source, event='workflow_dispatch', status='completed', conclusion='success',
                     run_attempt=1, run_started_at='2026-11-23T08:00:00Z')
        run = dict(first, run_attempt=2, run_started_at='2026-11-23T09:00:00Z')
        job = dict(id=9, name='unsigned', status='completed', conclusion='success', run_id=42, head_sha=source,
                   run_attempt=1, runner_id=17, steps=[{'name': 'upload'}],
                   started_at='2026-11-23T08:05:00Z', completed_at='2026-11-23T08:10:00Z')
        copy = dict(job, id=10, run_attempt=2)
        artifact = dict(id=58, name='validated-unsigned-apk', size_in_bytes=128, digest='sha256:'+'f'*64,
                        workflow_run=dict(id=42, head_sha=source), created_at='2026-11-23T08:08:00Z')
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            output = root / 'output'
            private = root / '.private'
            private.mkdir()
            receipts = {'core-acceptance.json': b'core', 'quote-acceptance.json': b'quote'}
            for name, data in receipts.items():
                (private / name).write_bytes(data)
            assembly = dict(status='accepted-awaiting-signing', source_sha=source, candidate_run=42,
                            production_unsigned_artifact={key: artifact[key] for key in (
                                'id', 'size_in_bytes', 'digest')},
                            receipt_sha256={name: hashlib.sha256(data).hexdigest()
                                            for name, data in receipts.items()},
                            production_unsigned_producer=dict(
                                workflow='build-release.yml', event='workflow_dispatch', head_branch='main',
                                head_sha=source, run_id=42, run_attempt=1, status='completed',
                                conclusion='success', producer_job_id=9, producer_job_name='unsigned',
                                producer_job_conclusion='success', artifact_name='validated-unsigned-apk'))
            (private / 'core-validation-summary.json').write_text(json.dumps(assembly))
            environment = dict(CANDIDATE_RUN='42', GITHUB_REPOSITORY='example/wallet', GITHUB_SHA=controller,
                               GITHUB_OUTPUT=str(output))
            with patch_environment(environment), patch('acceptance_policy.ROOT', root), \
                    patch('acceptance_policy.verify_promotion_revision') as verify:
                for expired, jobs, error in ((True, [job, copy], 'Artifact digest/run/source binding missing'),
                                             (False, [copy], 'Expected one successful unsigned producer'),
                                             (False, [job, copy], None)):
                    pages = [run, run, [{'artifacts': [dict(artifact, expired=expired)]}], [{'jobs': jobs}], first]
                    with self.subTest(expired=expired, jobs=jobs), patch('release_metadata.Metadata.api', side_effect=pages):
                        if error:
                            with self.assertRaisesRegex(ValueError, error):
                                runpy.run_module('promotion')
                            verify.assert_not_called()
                            self.assertFalse(output.exists())
                        else:
                            runpy.run_module('promotion')
                            verify.assert_called_once_with(source, controller)
                            self.assertEqual(output.read_text(), f'source_sha={source}\n')
                output.unlink()
                (private / 'quote-acceptance.json').write_bytes(b'changed')
                retained = [run, run, [{'artifacts': [dict(artifact, expired=False)]}], [{'jobs': [job, copy]}], first]
                with patch('release_metadata.Metadata.api', side_effect=retained):
                    with self.assertRaisesRegex(ValueError, 'Receipts differ|receipts differ'):
                        runpy.run_module('promotion')
                self.assertFalse(output.exists())

    def test_early_bridge_has_no_release_authority_and_quotes_receive_explicit_ids(self):
        root = Path(__file__).resolve().parents[1]
        workflow = (root/'.github/workflows/build-release.yml').read_text()
        bridge = workflow.split('  quote_bridge:\n', 1)[1].split('  preflight:\n', 1)[0]
        self.assertIn('needs: admit_hosted', bridge)
        self.assertIn('!inputs.preflight_only', bridge)
        self.assertIn("needs.admit_hosted.outputs.quote_receipt_id == ''", bridge)
        self.assertNotIn('secrets:', bridge)
        for job in ('hosted_quotes', 'recovery_quotes'):
            part = workflow.split('  '+job+':\n', 1)[1].split('    with:\n', 1)
            self.assertIn("needs.quote_bridge.result == 'success'", part[0])
            self.assertIn('bridge_id: ${{ needs.quote_bridge.outputs.artifact_id }}', part[1])
            self.assertIn('bridge_run_id: ${{ needs.quote_bridge.outputs.artifact_run_id }}', part[1])
        probe = (root/'.github/workflows/hosted-acceptance-probe.yml').read_text()
        self.assertIn('stage_bridge(target,', probe)
        self.assertNotIn('assembleDebug', probe)

    def test_recovery_routes_receipts_from_independent_runs_without_new_acceptance(self):
        root = Path(__file__).resolve().parents[1]
        workflow = (root / '.github/workflows/build-release.yml').read_text()
        custom = (root / '.github/workflows/custom-release.yml').read_text()
        self.assertIn('core_run: ${{ inputs.core_run }}', custom)
        self.assertIn('core_receipt_run: ${{ steps.admission.outputs.core_run }}', workflow)
        self.assertIn('RECOVER_CORE_RUN: ${{ inputs.core_run }}', workflow)
        self.assertIn('run-id: ${{ needs.admit_hosted.outputs.core_receipt_run ||', workflow)
        self.assertIn('run-id: ${{ needs.admit_hosted.outputs.quote_receipt_run || github.run_id }}', workflow)
        for job, receipt in (('hosted_core', 'core'), ('hosted_quotes', 'quote'), ('recovery_quotes', 'quote'),
                             ('reserve_quote_retry', 'quote')):
            with self.subTest(job=job):
                section = workflow.split(f'  {job}:\n', 1)[1]
                condition = next(line for line in section.splitlines() if line.strip().startswith('if:'))
                self.assertIn(f"needs.admit_hosted.outputs.{receipt}_receipt_id == ''", condition)

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

    def test_signing_workflow_edits_invalidate_the_reusable_smoke_result(self):
        workflows = [
            '.github/workflows/build-release.yml',
            '.github/workflows/custom-release.yml',
            '.github/workflows/sync-upstream.yml',
            '.github/workflows/sync-upstream-main.yml',
            '.github/workflows/upgrade-acceptance.yml',
            '.github/workflows/verify-signing-environment.yml',
        ]
        unrelated = '.github/workflows/automation-test.yml'
        wiring = dict(environment=5, rules=[], branches=[dict(type='branch', name='main')])
        smoke = workflow_job('build-release.yml', 'signing_smoke')

        def git(*args):
            return subprocess.check_output(['git', *args], stderr=subprocess.STDOUT, timeout=15)

        with tempfile.TemporaryDirectory() as temporary, chdir(temporary):
            git('init', '-q')
            for name in set(PATHS) | set(workflows) | {unrelated}:
                path = Path(name)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'fixture\n')
            git('add', '.github', '.private')
            git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                'commit', '-qm', 'Baseline wiring')
            Path('.private/signing-smoke.json').write_text(json.dumps(dict(fingerprint=fingerprint(wiring), run_id=7)))
            baseline = git('rev-parse', 'HEAD').decode().strip()

            for name in [None, unrelated, *workflows]:
                with self.subTest(path=name):
                    if name is not None:
                        git('reset', '--hard', baseline)
                        Path(name).write_bytes(b'changed fixture\n')
                        git('add', name)
                        git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                            'commit', '-qm', 'Change one path')
                    output = Path('smoke-output')
                    output.unlink(missing_ok=True)
                    with patch_environment(dict(GITHUB_OUTPUT=str(output))), \
                            patch('signing_wiring.environment_wiring', return_value=wiring), \
                            patch('signing_wiring.Metadata'), patch('builtins.print'):
                        select_signing_smoke()
                    key, changed = output.read_text(encoding='utf-8').strip().split('=')
                    self.assertEqual(key, 'changed')
                    expected = name in workflows
                    self.assertEqual(changed, str(expected).lower())
                    self.assertEqual(
                        job_activates(smoke, dict(preflight='success', admit='success'),
                                      **{'needs.preflight.outputs.signing_changed': changed}),
                        expected,
                    )

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
