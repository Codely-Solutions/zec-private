"""Run at most two independent emulator adapters inside one enclosing Windows Job."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

from emulator_session import prepare_image, verify_image

PROJECT = Path(__file__).resolve().parents[1]


def read_plan(path):
    value = json.loads(Path(path).read_text())
    if set(value) != {'schema', 'workers'} or value['schema'] != 1:
        raise ValueError('Unknown emulator batch schema')
    workers = value['workers']
    if not isinstance(workers, list) or not 1 <= len(workers) <= 2:
        raise ValueError('One or two independent workers required')
    names = set()
    for worker in workers:
        if set(worker) != {'name', 'adapter', 'manifest', 'manifest_sha256', 'seconds'}:
            raise ValueError('Explicit worker identity and immutable manifest required')
        if type(worker['seconds']) is not int or not 0 < worker['seconds'] <= 3000:
            raise ValueError('Worker deadline must be bounded to 3000 seconds')
        name = worker['name']
        if not isinstance(name, str) or not re.fullmatch('[a-z][a-z0-9-]{0,31}', name) or name in names:
            raise ValueError('Unique bounded worker names required')
        names.add(name)
        for key in ('adapter', 'manifest'):
            p = Path(worker[key])
            if not p.is_absolute() or not p.is_file() or not p.resolve().is_relative_to(PROJECT.parent):
                raise ValueError('Worker inputs must be existing absolute project paths')
        if Path(worker['adapter']).suffix != '.py':
            raise ValueError('Only registered Python adapters supported')
        raw = Path(worker['manifest']).read_bytes()
        if len(raw) > 1048576 or hashlib.sha256(raw).hexdigest() != worker['manifest_sha256']:
            raise ValueError('Worker manifest changed')
    return workers


def execute(workers, root, *, smoke=False):
    """Caller must provide the shared guard Job; never opens a second admission state."""
    root = Path(root).resolve()
    started = time.monotonic()
    shared = root / 'shared-image'
    shared.mkdir()
    system = prepare_image(shared, environ={})
    (root / 'barrier').mkdir()
    children = []
    result = {'schema': 1, 'evidence_class': 'isolated-emulator-batch',
              'workers': [], 'max_concurrency': len(workers), 'passed': False}
    try:
        for slot, worker in enumerate(workers):
            child_root = root / 'workers' / worker['name']
            child_root.mkdir(parents=True)
            raw = Path(worker['manifest']).read_bytes()
            if hashlib.sha256(raw).hexdigest() != worker['manifest_sha256']:
                raise ValueError('Worker manifest changed before launch')
            manifest = child_root / 'manifest.json'
            manifest.write_bytes(raw)
            env = dict(os.environ, CODEX_GUARD_OUTPUT=str(child_root), TEMP=str(child_root), TMP=str(child_root),
                       CODEX_EMULATOR_SLOT=str(slot), CODEX_EMULATOR_MANIFEST=str(manifest),
                       CODEX_EMULATOR_IMAGE=str(system), CODEX_EMULATOR_WORKER=worker['name'],
                       CODEX_EMULATOR_BATCH_BARRIER=str(root / 'barrier'))
            if smoke:
                env['CODEX_EMULATOR_SMOKE'] = 'expected-failure' if slot == 0 else 'wait-for-cancellation'
            log = (child_root / 'command.log').open('wb')
            process = subprocess.Popen([sys.executable, '-B', worker['adapter']], cwd=PROJECT, env=env,
                                       stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                                       creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            row = dict(name=worker['name'], slot=slot, pid=process.pid,
                       manifest_sha256=worker['manifest_sha256'], root=str(child_root),
                       status='running', exit_code=None, started=time.monotonic(), seconds_limit=worker['seconds'])
            result['workers'].append(row)
            children.append((process, log, row))
        while True:
            failed = False
            for process, _, row in children:
                code = process.poll()
                if code is None and time.monotonic() - row['started'] > row['seconds_limit']:
                    row['status'] = 'timed-out'
                    failed = True
                if code is not None and row['status'] == 'running':
                    row.update(exit_code=code, status='passed' if code == 0 else 'failed')
                failed |= code is not None and code != 0
            if failed:
                result['stop_reason'] = 'worker-failure'
                break
            if all(process.poll() is not None for process, _, _ in children):
                result['passed'] = True
                break
            time.sleep(.2)
    finally:
        for process, _, row in children:
            if process.poll() is None:
                process.terminate()
                if row['status'] != 'timed-out':
                    row['status'] = 'cancelled-after-peer-failure'
        for process, log, row in children:
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
            finally:
                row['exit_code'] = process.returncode
                log.close()
        # The enclosing kill-on-close Job also owns ADB/QEMU descendants of
        # cancelled Python workers. Its receipt verifies their final absence.
        try:
            verify_image(system)
            result['shared_image_unchanged'] = True
        except Exception:
            result['passed'] = False
            result['shared_image_unchanged'] = False
            raise
        finally:
            result['seconds'] = time.monotonic() - started
            (root / 'batch-result.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


if __name__ == '__main__':
    workers = read_plan(sys.argv[1])
    result = execute(workers, os.environ['CODEX_GUARD_OUTPUT'])
    print(json.dumps({'passed': result['passed'], 'workers': [
        {k: row[k] for k in ('name', 'slot', 'status', 'exit_code')} for row in result['workers']]}))
    raise SystemExit(0 if result['passed'] else 1)
