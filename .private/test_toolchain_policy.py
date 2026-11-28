"""Keep automated update proposals bounded and separate from signing."""
from pathlib import Path
import unittest
import hashlib
import tempfile
from unittest.mock import Mock, patch

import gradle_resolution as resolution

ROOT=Path(__file__).resolve().parents[1]

class ToolchainPolicyTests(unittest.TestCase):
    def test_release_staging_keeps_native_runtime_pins_and_strict_exact_scope(self):
        release = (ROOT/'.private/release_checks.sh').read_text()
        stage = (ROOT/'.private/stage_robolectric_release.sh').read_text()
        resolver = (ROOT/'.private/robolectric-ci.gradle').read_text()
        verification = resolution.ET.parse(ROOT/'gradle/verification-metadata.xml')
        component = verification.find(".//{*}component[@group='org.robolectric']"
                                      "[@name='nativeruntime-dist-compat'][@version='1.0.19']")
        self.assertEqual(len(component.findall('.//{*}sha256')), 2)
        for digest in component.findall('.//{*}sha256'):
            self.assertIn(digest.get('value'), stage)
        self.assertLess(release.index('stage_robolectric_release.sh'), release.index('./gradlew'))
        self.assertIn('--dependency-verification strict', release)
        self.assertIn('repositories.exclusiveContent', resolver)
        self.assertIn("includeVersion('org.robolectric', 'nativeruntime-dist-compat', '1.0.19')", resolver)
        self.assertIn("systemProperty 'robolectric.offline', 'true'", resolver)

    def test_proposals_exclude_majors_and_runtime_dependency_sweep(self):
        config=(ROOT/'.github/dependabot.yml').read_text()
        self.assertIn('dependency-name: gradle-wrapper',config)
        self.assertEqual(config.count('version-update:semver-major'),2)
        self.assertNotIn('dependency-name: com.',config)
        self.assertIn('open-pull-requests-limit: 1',config)

    def test_pull_request_job_has_no_signing_or_write_credentials(self):
        workflow=(ROOT/'.github/workflows/toolchain-review.yml').read_text()
        self.assertIn('pull_request:',workflow)
        self.assertNotIn('pull_request_target:',workflow)
        self.assertNotIn('secrets.',workflow)
        self.assertNotIn('contents: write',workflow)
        self.assertNotIn('release create',workflow)
        self.assertIn('cache-read-only: true',workflow)
        self.assertIn('persist-credentials: false',workflow)

class GradleResolutionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.metadata = Path(self.temp.name) / 'verification-metadata.xml'
        self.pom = b'pinned public fixture'
        self.digest = hashlib.sha256(self.pom).hexdigest()
        artifacts = ('kotlin-stdlib', 'kotlin-gradle-plugin-api', 'kotlin-gradle-plugins-bom')
        self.metadata.write_text('<verification-metadata><components>' + ''.join(
            f'<component group="org.jetbrains.kotlin" name="{name}" version="2.4.21">'
            f'<artifact name="{name}-2.4.21.pom"><sha256 value="{self.digest}"/></artifact></component>'
            for name in artifacts) + '</components></verification-metadata>')
        self.command = ['./gradlew', '-I', 'existing.gradle', '--dependency-verification', 'strict',
                        '--no-daemon', ':app:assembleZcashmainnetFossDebug']

    def failure(self, coordinates=('org.jetbrains.kotlin:kotlin-stdlib:2.4.21',)):
        return ("A problem occurred configuring project ':build-conventions-secant'.\n"
                "> Could not resolve all artifacts for configuration 'classpath'.\n" +
                '\n'.join('   > Could not find ' + coordinate + '.' for coordinate in coordinates))

    def execute(self, log, *, second=0, verify=True, command=None):
        runner = Mock(side_effect=[(7, log), (second, '')])
        probe = Mock(return_value=verify)
        code = resolution.run_with_recovery(command or self.command, metadata=self.metadata,
                                            runner=runner, verify=probe)
        return code, runner, probe

    def test_pinned_misses_refresh_exact_command_once_and_propagate_second_status(self):
        log = self.failure(('org.jetbrains.kotlin:kotlin-stdlib:2.4.21',
                            'org.jetbrains.kotlin:kotlin-gradle-plugins-bom:2.4.21'))
        code, runner, probe = self.execute(log, second=19)
        self.assertEqual(code, 19)
        self.assertEqual(runner.call_count, 2)
        self.assertEqual(runner.call_args.args[0], [self.command[0], '--refresh-dependencies', *self.command[1:]])
        self.assertEqual(probe.call_count, 2)
        for call in probe.call_args_list:
            self.assertEqual(call.args[1], ('pom', {self.digest}))
            self.assertLessEqual(call.args[2], 10)

    def test_ordinary_success_has_no_network_or_retry(self):
        runner, probe = Mock(return_value=(0, 'BUILD SUCCESSFUL')), Mock()
        self.assertEqual(resolution.run_with_recovery(self.command, runner=runner, verify=probe), 0)
        self.assertEqual(runner.call_count, 1)
        probe.assert_not_called()

    def test_real_configuration_names_and_ansi_are_accepted(self):
        for log in (self.failure(), self.failure().replace("'classpath'", "':classpath'"),
                    '\x1b[31m' + self.failure() + '\x1b[0m'):
            with self.subTest(log=log):
                self.assertEqual(self.execute(log)[0], 0)

    def test_checksum_lock_task_unknown_and_unpinned_failures_stay_failed(self):
        variants = [self.failure() + '\n' + error for error in (
            'Dependency verification failed', 'checksum mismatch', 'lock state mismatch',
            'Execution failed for task :app:compileKotlin', '> Task :app:lint FAILED',
            'Could not GET https://elsewhere.invalid/module', 'Read timed out')]
        variants += [self.failure(('com.other:module:1.0.0',)),
                     self.failure(('org.jetbrains.kotlin:kotlin-stdlib:2.4.22',)),
                     self.failure(('org.jetbrains.kotlin:kotlin-stdlib:2.4.21-RC1',)),
                     self.failure(('org.jetbrains.kotlin:kotlin-stdlib:2.4.21', 'com.other:module:1.0.0')),
                     'HTTP timeout', self.failure().replace('A problem occurred configuring ', 'Task failed ')]
        for log in variants:
            with self.subTest(log=log):
                code, runner, probe = self.execute(log)
                self.assertEqual(code, 7)
                self.assertEqual(runner.call_count, 1)
                probe.assert_not_called()

    def test_unavailable_pom_and_already_refreshed_or_offline_commands_stay_failed(self):
        code, runner, _ = self.execute(self.failure(), verify=False)
        self.assertEqual((code, runner.call_count), (7, 1))
        for flag in ('--refresh-dependencies', '--offline', '--write-locks', '--write-verification-metadata'):
            code, runner, probe = self.execute(self.failure(), command=[*self.command, flag])
            self.assertEqual((code, runner.call_count), (7, 1))
            probe.assert_not_called()

    def test_invalid_metadata_keeps_original_failure(self):
        self.metadata.write_text('<broken')
        code, runner, probe = self.execute(self.failure())
        self.assertEqual((code, runner.call_count), (7, 1))
        probe.assert_not_called()

    def test_live_pom_probe_requires_the_exact_https_url_and_pinned_bytes(self):
        coordinate = ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.21')
        url = resolution.CENTRAL + 'org/jetbrains/kotlin/kotlin-stdlib/2.4.21/kotlin-stdlib-2.4.21.pom'
        for data, destination, expected in ((self.pom, url, True), (b'changed', url, False),
                                             (self.pom, 'https://elsewhere.invalid/file', False)):
            response = Mock(status=200, read=Mock(return_value=data), geturl=Mock(return_value=destination))
            context = Mock(__enter__=Mock(return_value=response), __exit__=Mock(return_value=False))
            with patch.object(resolution, 'urlopen', return_value=context) as request:
                self.assertEqual(resolution.verify_metadata(coordinate, ('pom', {self.digest}), 2), expected)
                self.assertEqual(request.call_args.args, (url,))
                self.assertLessEqual(request.call_args.kwargs['timeout'], 2)
        with patch.object(resolution, 'urlopen', side_effect=OSError('unavailable')):
            self.assertFalse(resolution.verify_metadata(coordinate, ('pom', {self.digest}), 2))

    def test_actual_incident_coordinates_use_the_committed_pom_or_module_pins(self):
        log = self.failure(('org.jetbrains.kotlin:kotlin-stdlib:2.4.21',
                            'org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.4.21',
                            'org.jetbrains.kotlin:kotlin-gradle-plugins-bom:2.4.21'))
        pins = resolution.recoverable(log, ROOT/'gradle/verification-metadata.xml')
        self.assertEqual(len(pins), 3)
        self.assertEqual(pins[('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.21')][0], 'module')
        self.assertEqual(pins[('org.jetbrains.kotlin', 'kotlin-gradle-plugin-api', '2.4.21')][0], 'module')
        self.assertEqual(pins[('org.jetbrains.kotlin', 'kotlin-gradle-plugins-bom', '2.4.21')][0], 'pom')

    def test_plugin_scopes_route_kotlin_to_central_and_keep_portal_markers(self):
        for name in ('settings.gradle.kts', 'buildSrc/settings.gradle.kts',
                     'build-conventions-secant/settings.gradle.kts',
                     '.private/ui-bridge/settings.gradle.kts'):
            text = (ROOT/name).read_text().split('dependencyResolutionManagement')[0]
            with self.subTest(name=name):
                self.assertIn('pluginManagement', text)
                self.assertLess(text.index('mavenCentral'), text.index('gradlePluginPortal'))
                self.assertIn('excludeGroup("org.jetbrains.kotlin")', text)
                self.assertNotIn('includeGroupByRegex("org\\\\.jetbrains\\\\.kotlin', text)


if __name__=='__main__':unittest.main()
