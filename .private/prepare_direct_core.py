"""Prepare direct-mode instrumentation against the two exact testnet app sources."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from tempfile import TemporaryDirectory

from acceptance_policy import CORE_TEST_SOURCE, ROOT, git
from hosted_core import candidate_pair, sha
from hosted_release import stage


TOOLING_REPOSITORIES = '''
beforeSettings { settings ->
    if (settings.settingsDir.name == 'build-conventions-secant') {
        settings.pluginManagement.repositories {
            mavenCentral { content { includeGroup('org.jetbrains.kotlin') } }
            gradlePluginPortal()
        }
    }
}
'''


def compile_instrumentation(revision, parent):
    if not re.fullmatch('[a-f0-9]{40}', revision):
        raise ValueError('Exact testnet build source required')
    with TemporaryDirectory(dir=parent) as scratch:
        checkout = Path(scratch) / 'source'
        repositories = Path(scratch) / 'instrumentation-tooling.gradle'
        repositories.write_text(TOOLING_REPOSITORIES)
        git('worktree', 'add', '--detach', str(checkout), revision)
        try:
            shutil.copyfile(ROOT / CORE_TEST_SOURCE, checkout / CORE_TEST_SOURCE)
            environment = {key: value for key, value in os.environ.items()
                           if key not in {'GH_TOKEN', 'GITHUB_TOKEN', 'REGISTRY_TOKEN'}}
            subprocess.run(['bash', './gradlew', '-I', str(repositories), '-I', '.private/tor-ui-abi.gradle', '-I',
                            '.private/core-wallet/include.gradle', '--no-daemon', '--no-watch-fs',
                            '--max-workers=2', '-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g',
                            '-Pkotlin.compiler.execution.strategy=in-process',
                            ':app:assembleZcashtestnetFossDebugAndroidTest'], cwd=checkout, env=environment,
                           check=True, timeout=1000)
            artifacts = list((checkout / 'app/build/outputs/apk/androidTest/zcashtestnetFoss/debug').glob('*.apk'))
            if len(artifacts) != 1:
                raise ValueError('Expected one version-matched instrumentation APK')
            return artifacts[0].read_bytes()
        finally:
            git('worktree', 'remove', '--force', str(checkout))


def prepare(work, fixture, output, source, artifact_id, build_run, build_controller):
    original_dir = work / 'original'
    original = stage(original_dir, artifact_id, 'core-testnet-candidate', run=build_run, controller=build_controller)
    app, test = sorted(candidate_pair(original_dir, source), key=lambda path: 'androidTest' in path.name)
    baseline = json.loads((ROOT / '.private/hosted-fixture.json').read_bytes())
    if (json.loads((fixture / 'fixture-binding.json').read_bytes()) != baseline
            or sha(fixture / 'baseline-ui.apk') != baseline['candidate_apk_sha256']
            or sha(fixture / 'baseline-androidTest.apk') != baseline['instrumentation_apk_sha256']):
        raise ValueError('Direct preparation needs the selected exact baseline pair')
    current_test = (ROOT / CORE_TEST_SOURCE).read_bytes()
    controller = git('rev-parse', 'HEAD').decode().strip()
    output.mkdir()
    shutil.copyfile(app, output / app.name)
    candidate_test = (test.read_bytes() if git('show', source + ':' + CORE_TEST_SOURCE) == current_test
                      else compile_instrumentation(source, work))
    (output / test.name).write_bytes(candidate_test)
    (output / 'source-sha.txt').write_text(source + '\n')
    (output / 'SHA256SUMS').write_text(''.join(f'{sha(path)}  ./{path.name}\n' for path in sorted(output.glob('*.apk'))))
    released_core = json.loads((ROOT / '.private/release-evidence' / (baseline['release'] + '.json')).read_bytes())['core']
    if (released_core.get('direct_preparation') and released_core.get('instrumentation_apk_sha256')
            == baseline['instrumentation_apk_sha256']):
        baseline_test = (fixture / 'baseline-androidTest.apk').read_bytes()
    elif git('show', baseline['source_sha'] + ':' + CORE_TEST_SOURCE) == current_test:
        baseline_test = (fixture / 'baseline-androidTest.apk').read_bytes()
    else:
        baseline_test = compile_instrumentation(baseline['source_sha'], work)
    (output / 'baseline-instrumentation.bin').write_bytes(baseline_test)
    binding = dict(source=source, harness_controller=controller, original_core=original,
                   candidate_apk_sha256=sha(app), candidate_instrumentation_sha256=sha(output / test.name),
                   baseline_apk_sha256=baseline['candidate_apk_sha256'],
                   baseline_source=baseline['source_sha'],
                   baseline_instrumentation_sha256=hashlib.sha256(baseline_test).hexdigest())
    (output / 'direct-preparation.json').write_text(json.dumps(binding, indent=2) + '\n')
    candidate_pair(output, source)


if __name__ == '__main__':
    work = Path(os.environ['RUNNER_TEMP']) / 'zec-direct-build'
    work.mkdir(mode=0o700)
    prepare(work, Path(os.environ['RUNNER_TEMP']) / 'zec-hosted-pair', Path('direct-core-artifact'),
            os.environ['ZEC_CORE_SOURCE'], os.environ['ORIGINAL_CORE_ID'],
            os.environ.get('ORIGINAL_ARTIFACT_RUN') or os.environ['GITHUB_RUN_ID'],
            os.environ.get('ORIGINAL_BUILD_CONTROLLER') or os.environ['GITHUB_SHA'])
