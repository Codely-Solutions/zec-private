"""Related development checks in one disposable guarded checkout.

Run: python -B .private/dev_session.py manifest.json --evidence DIRECTORY
Manifest: {source, operations: [{source: full_commit, config: {tasks, init_scripts,
properties}, seconds}], seconds, idle_seconds, reserve_bytes}. Optional inbox is
a directory containing sequential 0001.json operation messages and finish.json.
Only committed public source is admitted. This helper does not sign or launch guests.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

GUARDS = Path(r'C:\Users\User\.codex\workflow-guards')
JDK = Path(r'C:\Users\User\.codex\cache\jdks\temurin-25.0.5+8')
SDK = Path(r'C:\Android\Sdk')
VERIFICATION_METADATA_MAX_BYTES = 4*1024**2


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def write_once(path, value):
    with path.open('x', encoding='utf-8') as stream:
        json.dump(value, stream, indent=2)
        stream.write('\n')


def git(source, *args):
    return subprocess.check_output(['git', '-c', 'core.autocrlf=false', '-C', str(source), *args],
                                   timeout=60, stderr=subprocess.STDOUT).decode().strip()


def pin(source, commit):
    if not isinstance(commit, str) or not re.fullmatch('[0-9a-f]{40}', commit):
        raise ValueError('Operation requires a full commit hash')
    if git(source, 'rev-parse', commit + '^{commit}') != commit:
        raise ValueError('Source commit mismatch')
    return commit


def gradle(root, config, jdk):
    if set(config) - {'tasks', 'init_scripts', 'properties', 'tests', 'write_verification_metadata'}:
        raise ValueError('Unsupported Gradle configuration')
    if 'write_verification_metadata' in config and config['write_verification_metadata'] != ['sha256']:
        raise ValueError('Dependency verification generation accepts only [sha256]')
    tasks = config.get('tasks', [])
    if not tasks or any(not re.fullmatch(r':[\w:-]+', task) or not (
            'Debug' in task or task.endswith('UnitTest') or task in {':wallet-foundation-lib:test', ':detektAll'}
    ) for task in tasks):
        raise ValueError('Explicit debug, unit-test or Detekt analysis tasks required')
    scripts = config.get('init_scripts', [])
    for script in scripts:
        path = Path(script)
        if path.is_absolute() or '..' in path.parts or not (root/'src'/path).is_file():
            raise ValueError('Init scripts must be committed checkout files')
        git(root/'src', 'ls-files', '--error-unmatch', script)
    properties = config.get('properties', {})
    if any(not re.fullmatch(r'[\w.]+', key) or not isinstance(value, str) for key, value in properties.items()):
        raise ValueError('Invalid Gradle properties')
    args = [str(jdk/'bin/java.exe'), '-classpath', str(root/'src/gradle/wrapper/gradle-wrapper.jar'),
            'org.gradle.wrapper.GradleWrapperMain', '--project-cache-dir', str(root/'project-cache'),
            '-I', str(root/'outputs.gradle'), '--no-daemon', '--no-watch-fs', '--max-workers=2',
            '--stacktrace', '-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g -XX:+ExitOnOutOfMemoryError -XX:-HeapDumpOnOutOfMemoryError',
            '-Pkotlin.compiler.execution.strategy=in-process']
    for script in scripts:
        args += ['-I', script]
    if 'write_verification_metadata' in config:
        args += ['--write-verification-metadata', 'sha256']
    tests = config.get('tests', [])
    for test in tests:
        if not isinstance(test, str) or not re.fullmatch(r'[\w.$*]+', test):
            raise ValueError('Invalid test filter')
    unit_tasks = {task for task in tasks if task.endswith('UnitTest') or task == ':wallet-foundation-lib:test'}
    if tests and not unit_tasks:
        raise ValueError('Test filters require an explicit JVM test task')
    args += ['-P'+key+'='+value for key, value in sorted(properties.items())]
    for task in tasks:
        args.append(task)
        if task in unit_tasks:
            for test in tests:
                args += ['--tests', test]
    return args


def read_verification_metadata(metadata):
    if metadata.stat().st_size > VERIFICATION_METADATA_MAX_BYTES:
        raise ValueError('Verification metadata exceeds 4 MiB')
    with metadata.open('rb') as stream:
        data = stream.read(VERIFICATION_METADATA_MAX_BYTES + 1)
    if len(data) > VERIFICATION_METADATA_MAX_BYTES:
        raise ValueError('Verification metadata exceeds 4 MiB')
    return data


def export_verification_metadata(src, evidence, name, original):
    metadata = src/'gradle/verification-metadata.xml'
    try:
        if not metadata.is_file():
            return None
        data = read_verification_metadata(metadata)
        target = evidence/name/'verification-metadata.xml'
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open('xb') as stream:
            stream.write(data)
        return dict(file=str(target.relative_to(evidence)), sha256=hashlib.sha256(data).hexdigest(),
                    original_sha256=hashlib.sha256(original).hexdigest() if original is not None else None)
    finally:
        if original is None:
            metadata.unlink(missing_ok=True)
        else:
            metadata.parent.mkdir(parents=True, exist_ok=True)
            metadata.write_bytes(original)


def execute(manifest, root, evidence, runner, clock=time.monotonic, sleep=time.sleep):
    source = Path(manifest['source']).resolve()
    start = clock()
    deadline = start + float(manifest.get('seconds', 1700))
    idle_seconds = float(manifest.get('idle_seconds', 300))
    if deadline <= start or idle_seconds <= 0:
        raise ValueError('Positive session and idle bounds required')
    src = root/'src'
    subprocess.run(['git', '-c', 'core.autocrlf=false', 'clone', '--shared', '--quiet', '--no-checkout',
                    str(source), str(src)], check=True, timeout=60)
    (root/'outputs.gradle').write_text("allprojects { p -> p.layout.buildDirectory.set(new File(System.getenv('CODEX_GUARD_OUTPUT'), 'builds/' + (p.path == ':' ? 'root' : p.path.substring(1).replace(':','/')))) }\n")
    env = dict(os.environ, JAVA_HOME=str(manifest.get('jdk', JDK)), ANDROID_HOME=str(manifest.get('sdk', SDK)),
               ANDROID_SDK_ROOT=str(manifest.get('sdk', SDK)), GRADLE_USER_HOME=str(manifest.get('gradle_home', Path.home()/'.gradle')),
               ANDROID_USER_HOME=str(root/'android-home'), CODEX_GUARD_OUTPUT=str(root))
    operations = iter(manifest.get('operations', []))
    index = 0
    previous = None
    idle_start = clock()
    while True:
        if clock() >= deadline:
            raise TimeoutError('Session deadline exceeded')
        operation = next(operations, None)
        if operation is None:
            inbox = manifest.get('inbox')
            if not inbox:
                return
            inbox = Path(inbox)
            message = inbox/f'{index+1:04d}.json'
            if message.exists():
                operation = json.loads(message.read_text(encoding='utf-8-sig'))
            elif (inbox/'finish.json').exists():
                return
            elif clock()-idle_start >= idle_seconds:
                raise TimeoutError('Session idle deadline exceeded')
            else:
                sleep(min(.2, idle_seconds, max(.001, deadline-clock())))
                continue
        index += 1
        name = f'{index:04d}'
        receipt = dict(operation=index, source=operation.get('source'), config=operation.get('config'),
                       checkout=str(src), build_root=str(root/'builds'), project_cache=str(root/'project-cache'),
                       accepted=False)
        began = clock()
        try:
            commit = pin(source, operation['source'])
            # Fetch admits later local commits while preserving the isolated build state.
            git(src, 'fetch', '--quiet', str(source), commit)
            git(src, 'checkout', '--detach', '--quiet', commit)
            if git(src, 'status', '--porcelain', '--untracked-files=no'):
                raise ValueError('Checkout contains tracked modifications')
            config = operation['config']
            tool = Path(env['JAVA_HOME'])/'bin/java.exe'
            receipt['java_sha256'] = hashlib.sha256(tool.read_bytes()).hexdigest() if tool.exists() else None
            receipt['binding'] = digest(dict(source=commit, config=config, environment={key:env[key] for key in
                                             ('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'GRADLE_USER_HOME')}, java_sha256=receipt['java_sha256'],
                                             helper=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()))
            receipt['configuration_changed'] = previous is not None and previous != digest(config)
            previous = digest(config)
            command = gradle(root, config, Path(env['JAVA_HOME']))
            seconds = min(float(operation.get('seconds', 1100)), deadline-clock())
            if seconds <= 0:
                raise TimeoutError('Session deadline exceeded')
            write_once(evidence/(name+'.started.json'), receipt)
            def check_log():
                log = root/(name+'.log')
                if log.exists() and log.stat().st_size > 1048576:
                    raise ValueError('Operation log budget exceeded')
            writing_verification = 'write_verification_metadata' in config
            metadata = src/'gradle/verification-metadata.xml'
            original_metadata = read_verification_metadata(metadata) if writing_verification and metadata.is_file() else None
            try:
                result = runner(command, src, seconds, root, name, environment=env, monitor=check_log,
                                memory_bytes=6*1024**3, process_limit=32)
                receipt['process'] = result
            finally:
                if writing_verification:
                    receipt['verification_metadata'] = export_verification_metadata(src, evidence, name, original_metadata)
            if result.get('exitCode') != 0 or any(result.get(k) for k in ('error', 'timedOut', 'ownedSurvivors', 'cleanupErrors')):
                raise RuntimeError('Operation failed; session stops')
            if writing_verification and receipt['verification_metadata'] is None:
                raise ValueError('Gradle did not generate verification metadata')
            receipt['accepted'] = True
        except BaseException as error:
            receipt['error'] = f'{type(error).__name__}: {error}'
            raise
        finally:
            receipt['seconds'] = round(clock()-began, 3)
            log = root/(name+'.log')
            if log.exists():
                data = log.read_bytes()
                receipt['log_sha256'] = hashlib.sha256(data).hexdigest()
                (evidence/(name+'.log')).write_bytes(data[-1048576:])
                receipt['task_outcomes'] = [line for line in data.decode(errors='replace').splitlines() if line.startswith('> Task ')][-1000:]
            # Reports may describe cached/up-to-date tests; task_outcomes records that distinction.
            receipt['reports'] = []
            for report in sorted((root/'builds').rglob('TEST-*.xml')):
                relative = report.relative_to(root/'builds')
                target = evidence/name/'reports'/relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(report, target)
                receipt['reports'].append(dict(file=str(target.relative_to(evidence)), sha256=hashlib.sha256(target.read_bytes()).hexdigest()))
            for report in [src/'artifact/task-timing.jsonl']:
                if report.is_file():
                    target = evidence/(name+'-'+report.name)
                    shutil.copyfile(report, target)
            write_once(evidence/(name+'.json'), receipt)
            (evidence/(name+'.started.json')).unlink(missing_ok=True)
        idle_start = clock()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest', type=Path)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--guards', type=Path, default=GUARDS)
    parser.add_argument('--state', type=Path)
    parser.add_argument('--policy', type=Path, default=Path(__file__).with_name('development-policy.json'))
    parser.add_argument('--worker', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding='utf-8-sig'))
    sys.path.insert(0, str(args.guards))
    import windows_job
    if args.worker:
        execute(manifest, Path(os.environ['CODEX_GUARD_OUTPUT']), args.evidence, windows_job.run)
        return 0
    import guard
    args.evidence.mkdir(parents=True, exist_ok=False)
    write_once(args.evidence/'inputs.json', manifest)
    spec = dict(owner='zec-private-development', purpose='Related debug checks with bounded incremental state',
                cleanup='Remove isolated checkout and builds; retain immutable operation evidence',
                argv=[sys.executable, '-B', str(Path(__file__).resolve()), str((args.evidence/'inputs.json').resolve()),
                      '--evidence', str(args.evidence.resolve()), '--guards', str(args.guards.resolve()), '--worker'],
                cwd=str(Path(manifest['source']).resolve()), seconds=int(manifest.get('seconds', 1700))+30,
                reserveBytes=int(manifest.get('reserve_bytes', 12*1024**3)), sources=[str(Path(__file__).resolve()), str(args.manifest.resolve()), str((args.evidence/'inputs.json').resolve())])
    state = guard.state_root(args.state or args.guards/'state')
    def retain_interrupted(root):
        # A supervisor/command timeout may prevent the worker from writing its final receipt.
        recovery = args.evidence/'interrupted'
        for log in root.glob('*.log'):
            if log.stat().st_size == 0 or (args.evidence/(log.stem+'.json')).exists():
                continue
            recovery.mkdir(exist_ok=True)
            with (recovery/log.name).open('xb') as output:
                output.write(log.read_bytes()[-1048576:])
        return {'retained': 'Per-operation source/config bindings, reports and bounded interrupted logs'}
    result = guard.run(spec, state, args.policy, finalize=retain_interrupted)
    write_once(args.evidence/'session.json', result)
    shared = state/('receipt-'+result['job']+'.json')
    archived = args.evidence/'guard-receipt.json'
    shutil.copyfile(shared, archived)
    if result['accepted']:
        write_once(args.evidence/'guard-verified.json', guard.verify(shared))
    if result['cleanupComplete'] and not result['ownedSurvivors']:
        if shared.read_bytes() != archived.read_bytes():
            raise ValueError('Archived guard receipt mismatch')
        with guard.lock(state):
            shared.unlink()
    return 0 if result['accepted'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
