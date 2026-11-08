"""Shared-guard entry point for one or two prepared independent emulator jobs."""
import argparse
import ctypes
import hashlib
import json
import shutil
from pathlib import Path
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]
PARENT = ROOT.parent
GUARDS = Path(r'C:\Users\User\.codex\workflow-guards')
sys.path.insert(0, str(GUARDS))
import guard

EVIDENCE = {'tor-probe-result.json', 'core-testnet-result.json', 'core-testnet-diagnostic.json',
    'core-testnet-progress.json', 'tor-ui-result.json', 'phase1-scenario-result.json',
    'phase1-scenario-error.json', 'swap-ui-artifact-verification.json', 'swap-live-presentation.json',
    'donation-ui-result.json', 'donation-capture-evidence.json', 'command.log', 'emulator.log',
    'smoke-result.json', 'smoke-observation.json', 'batch-result.json', 'batch-smoke-verification.json', 'manifest.json',
    'donation-light.png', 'donation-dark.png', 'recovery-copy-light.png', 'recovery-copy-dark.png',
    'donation-off-light.png', 'donation-off-dark.png', 'donation-on-light.png', 'donation-on-dark.png'}


def archive_receipt(state, result, record):
    source = state / ('receipt-' + result['job'] + '.json')
    raw = source.read_bytes()
    (record / 'receipt.json').write_bytes(raw)
    if result['accepted']:
        proof = guard.verify(source)
        (record / 'verified.json').write_text(json.dumps(proof) + '\n')
    if result['cleanupComplete'] and not result['ownedSurvivors']:
        with guard.lock(state):
            if (state / 'active.json').exists() or source.read_bytes() != raw:
                raise RuntimeError('Shared state changed before receipt archival')
            source.unlink()


def available_memory():
    class Memory(ctypes.Structure):
        _fields_ = [('length', ctypes.c_ulong), ('load', ctypes.c_ulong),
                    *[(n, ctypes.c_ulonglong) for n in ('total', 'available', 'page_total', 'page_available',
                                                      'virtual_total', 'virtual_available', 'extended')]]
    value = Memory()
    value.length = ctypes.sizeof(value)
    if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(value)):
        raise ctypes.WinError()
    return value.available


def allocated_bytes(path):
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    function = kernel.GetCompressedFileSizeW
    function.argtypes = [ctypes.c_wchar_p, ctypes.POINTER(ctypes.c_ulong)]
    function.restype = ctypes.c_ulong
    total = 0
    for file in path.rglob('*'):
        if not file.is_file():
            continue
        high = ctypes.c_ulong()
        ctypes.set_last_error(0)
        low = function(str(file), ctypes.byref(high))
        if low == 0xffffffff and ctypes.get_last_error():
            raise ctypes.WinError(ctypes.get_last_error())
        total += (high.value << 32) + low
    return total


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--worker', nargs=3, action='append', metavar=('NAME', 'JOB_JSON', 'MANIFEST_JSON'))
    parser.add_argument('--smoke', action='store_true', help='Two blank guests; intentional failure and cleanup probe')
    args = parser.parse_args()
    if args.smoke and args.worker or not args.smoke and not 1 <= len(args.worker or []) <= 2:
        parser.error('Use --smoke or one/two --worker NAME JOB_JSON MANIFEST_JSON entries')
    count = 2 if args.smoke else len(args.worker)
    free = available_memory()
    required = (16 if count == 2 else 8) * 1024**3
    if free < required:
        raise RuntimeError(f'Insufficient available RAM: {free} bytes; need {required}')
    record = PARENT / 'emulator-batch-records' / uuid.uuid4().hex
    record.mkdir(parents=True)
    sources = [str(p) for p in (Path(__file__), ROOT / '.private/emulator_batch.py',
        ROOT / '.private/emulator_session.py', ROOT / '.private/run_tor_emulator.py',
        ROOT / '.private/tor_resident_ui_driver.py')]
    from emulator_session import SDK
    sources += [str(SDK / 'emulator/emulator.exe'), str(SDK / 'platform-tools/adb.exe')]
    workers = []
    seconds = 600 if args.smoke else 0
    reserve = 17 * 1024**3 if args.smoke else 0
    if args.smoke:
        manifest = ROOT / '.private/emulator-smoke-manifest.json'
        workers = [dict(name=n, adapter=str(ROOT / '.private/emulator_smoke.py'), manifest=str(manifest),
                        manifest_sha256=hashlib.sha256(manifest.read_bytes()).hexdigest(), seconds=360)
                   for n in ('first', 'second')]
        script = ROOT / '.private/test_emulator_batch_live.py'
        sources += [str(manifest), str(script), str(ROOT / '.private/emulator_smoke.py')]
    else:
        script = ROOT / '.private/emulator_batch.py'
        for name, job_path, manifest_path in args.worker:
            job_path, manifest_path = Path(job_path).resolve(), Path(manifest_path).resolve()
            job = json.loads(job_path.read_text())
            if len(job['argv']) != 3 or job['argv'][1] != '-B' or Path(job['argv'][2]).suffix != '.py':
                raise ValueError('Expected a prepared Python emulator adapter job')
            raw = manifest_path.read_bytes()
            kind = json.loads(raw).get('probeKind')
            if kind not in ('core-testnet', 'ui'):
                raise ValueError('Only independent core-testnet/UI acceptance profiles supported')
            workers.append(dict(name=name, adapter=job['argv'][2], manifest=str(manifest_path),
                manifest_sha256=hashlib.sha256(raw).hexdigest(), seconds=job['seconds']))
            sources += [str(job_path), str(manifest_path), job['argv'][2]]
            sources += [s for s in job['sources'] if Path(s).name != 'tor-probe-artifact.json']
            seconds = max(seconds, job['seconds'])
            reserve += job['reserveBytes']
    plan = record / 'plan.json'
    plan.write_text(json.dumps(dict(schema=1, workers=workers), indent=2) + '\n')
    sources.append(str(plan))
    # Validate all identities and hashes before admission or image download.
    from emulator_batch import read_plan
    read_plan(plan)
    policy = record / 'policy.json'
    if count == 2:
        reserve = max(reserve, 17 * 1024**3)
    policy.write_text(json.dumps(dict(maxMemoryBytes=count * 6 * 1024**3,
        maxTransientBytes=(18 if count == 2 else 8) * 1024**3, maxSeconds=3000, maxLogBytes=1048576)))
    spec = dict(owner='zec-private-emulator-batch', purpose='Blank-guest parallel isolation diagnostic' if args.smoke
        else 'Independent prepared emulator acceptance profiles; each keeps its own evidence',
        cleanup='Kill owned worker/ADB/QEMU trees and remove all shared image and guest state',
        argv=[sys.executable, '-B', str(script), str(plan)], cwd=str(ROOT), seconds=seconds,
        reserveBytes=reserve, sources=sorted(set(sources)))

    def retain(root):
        identities = {}
        for path in root.rglob('*'):
            if not path.is_file() or path.name not in EVIDENCE:
                continue
            relative = path.relative_to(root)
            raw = path.read_bytes()
            limit = 4 * 1024**2 if path.suffix == '.png' else 32768
            if len(raw) > limit:
                if path.suffix != '.log':
                    raise ValueError('Evidence exceeds compact retention bound')
                raw = raw[-limit:]
            target = record / 'evidence' / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(raw)
            identities[relative.as_posix()] = dict(bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())
        return dict(availableMemoryAtAdmission=free, retainedEvidence=identities,
                    footprint=dict(logicalBytes=sum(p.stat().st_size for p in root.rglob('*') if p.is_file()),
                                   hostAllocatedBytes=allocated_bytes(root),
                                   hostFreeBeforeCleanup=shutil.disk_usage(root).free),
                    scope='Per-worker evidence; batch success requires every worker. Smoke is not product acceptance.')

    state = guard.state_root(GUARDS / 'state')
    result = guard.run(spec, state, policy, finalize=retain)
    archive_receipt(state, result, record)
    print(json.dumps(dict(record=str(record), job=result['job'], accepted=result['accepted'],
        cleanupComplete=result['cleanupComplete'], ownedSurvivors=result['ownedSurvivors'])))
    return 0 if result['accepted'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
