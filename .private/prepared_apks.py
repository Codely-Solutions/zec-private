"""Verified prepared APK inputs and explicit offline exports for emulator sessions."""
import hashlib
import json
import re
import shutil
from pathlib import Path
from strip_emulator_apk import verify_prepared

HASH_FIELDS = {'candidate':'candidate_apk_sha256', 'instrumentation':'instrumentation_apk_sha256',
               'baseline':'baseline_apk_sha256', 'baseline-instrumentation':'baseline_instrumentation_apk_sha256'}


def binding(manifest):
    source = manifest.get('build_source_sha') or manifest.get('source_sha')
    if not isinstance(source, str) or not re.fullmatch('[0-9a-f]{40}', source):
        raise ValueError('Prepared reuse requires a full lowercase Git source SHA')
    return json.loads(json.dumps(manifest, sort_keys=True))


def role(path):
    name = Path(path).name
    if name == 'ui-bridge.apk': return 'ui-bridge'
    if name == 'baseline-androidTest.apk': return 'baseline-instrumentation'
    if name in ('baseline-app.apk', 'baseline-ui.apk'): return 'baseline'
    return 'instrumentation' if 'androidTest' in name else 'candidate'


def expected_roles(manifest):
    roles = {'candidate'}
    if manifest.get('probeKind') != 'ui': roles.add('instrumentation')
    if manifest.get('probeKind') == 'differential' or manifest.get('upgradeBaseline'): roles.add('baseline')
    if manifest.get('residentUiBridge'): roles.add('ui-bridge')
    if manifest.get('upgradeBaseline') and manifest.get('probeKind') == 'core-testnet': roles.add('baseline-instrumentation')
    return roles


def reuse(receipt_path, manifest, root, sdk, expected_sha256):
    receipt_path = Path(receipt_path)
    if not receipt_path.is_absolute():
        raise ValueError('Prepared receipt path must be absolute')
    raw = receipt_path.read_bytes()
    if not isinstance(expected_sha256, str) or not re.fullmatch('[0-9a-f]{64}', expected_sha256) or hashlib.sha256(raw).hexdigest() != expected_sha256:
        raise ValueError('Prepared receipt digest mismatch')
    receipt = json.loads(raw)
    if receipt.get('schema') != 1 or receipt.get('manifest') != binding(manifest):
        raise ValueError('Prepared source/configuration binding mismatch')
    entries = receipt.get('apks', [])
    if not entries or len({e['name'] for e in entries}) != len(entries):
        raise ValueError('Missing or duplicate prepared APKs')
    if len({e['role'] for e in entries}) != len(entries) or {e['role'] for e in entries} != expected_roles(manifest):
        raise ValueError('Prepared APK role count mismatch')
    execution = receipt.get('executionManifest', manifest)
    derived = set(HASH_FIELDS.values())
    if {k:v for k,v in execution.items() if k not in derived} != {k:v for k,v in manifest.items() if k not in derived}:
        raise ValueError('Prepared execution configuration mismatch')
    validated = []
    for entry in entries:
        path = Path(entry['path'])
        if not path.is_absolute() or path.name != entry['name'] or role(path) != entry['role']:
            raise ValueError('Prepared APK path/name/role mismatch')
        field = HASH_FIELDS.get(entry['role'])
        if field and field in execution and execution[field] != entry['preparation'].get('originalSha256'):
            raise ValueError('Prepared original APK role binding mismatch')
        if field and field in manifest and manifest[field] != entry['preparation'].get('originalSha256'):
            raise ValueError('Prepared manifest APK role binding mismatch')
        verify_prepared(path, entry['preparation'], sdk)
        validated.append((path, entry))
    destination = Path(root) / 'apks'
    destination.mkdir()
    paths, preparations = [], {}
    for source, entry in validated:
        target = destination / source.name
        shutil.copyfile(source, target)
        if hashlib.sha256(target.read_bytes()).hexdigest() != entry['preparation']['testedSha256']:
            raise ValueError('Prepared APK changed during copy')
        paths.append(target)
        preparations[target.name] = entry['preparation']
    return paths, preparations, execution


def write_receipt(path, manifest_binding, execution_manifest, paths, preparations):
    binding(manifest_binding)
    payload = dict(schema=1, manifest=manifest_binding, executionManifest=execution_manifest,
        apks=[dict(name=p.name, path=str(p.resolve()), role=role(p), preparation=preparations[p.name]) for p in paths])
    with Path(path).open('x') as stream:
        stream.write(json.dumps(payload, indent=2) + '\n')
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def export_prepared(destination, manifest, execution, paths, preparations, sdk):
    """Copy verified APKs to a new caller-owned retained directory; never replace evidence."""
    destination = Path(destination)
    if not destination.is_absolute():
        raise ValueError('Export destination must be absolute')
    # A caller-selected retained path is still checked for redirected ancestors.
    for parent in destination.parents:
        if parent.is_symlink() or getattr(parent, 'is_junction', lambda: False)():
            raise ValueError('Export ancestor is redirected')
    if len({p.name for p in paths}) != len(paths):
        raise ValueError('Duplicate export APK names')
    binding(manifest)
    for path in paths:
        verify_prepared(path, preparations[path.name], sdk)
    destination.mkdir(exist_ok=False)
    created = destination.stat()
    copied = []
    try:
        for path in paths:
            target = destination/path.name
            with target.open('xb') as output, path.open('rb') as source:
                shutil.copyfileobj(source, output)
            if hashlib.sha256(target.read_bytes()).hexdigest() != preparations[path.name]['testedSha256']:
                raise ValueError('Export APK changed during copy')
            copied.append(target)
        receipt = destination/'prepared-apks.json'
        digest = write_receipt(receipt, manifest, execution, copied, preparations)
        return receipt, digest
    except BaseException:
        # This new exclusive directory is owned solely by this failed export.
        current = destination.lstat()
        if destination.is_symlink() or getattr(destination, 'is_junction', lambda: False)() or (current.st_dev, current.st_ino) != (created.st_dev, created.st_ino):
            raise RuntimeError('Export root changed; preserved for ownership review')
        shutil.rmtree(destination)
        raise
