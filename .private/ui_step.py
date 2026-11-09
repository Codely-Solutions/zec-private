"""Send one bounded command to the verified active guard-owned UI bridge."""
import argparse
import json
import os
from pathlib import Path
import stat
import sys
import time

DEFAULT_STATE = Path(r'C:\Users\User\.codex\workflow-guards\state')
LEGACY_OWNERS = ('zec-private-tor-retry', 'zec-private-phase1')


def identity(path):
    for part in (path, *path.parents):
        value = part.lstat()
        if stat.S_ISLNK(value.st_mode) or getattr(value, 'st_file_attributes', 0) & 0x400:
            raise ValueError('UI root cannot contain reparse points')
    value = path.stat()
    return [value.st_dev, value.st_ino, value.st_ctime_ns]


def active_root(state_dir, owner=None, expected=None):
    active = json.loads((state_dir/'active.json').read_text())
    if active.get('owner') not in ((owner,) if owner else LEGACY_OWNERS):
        raise ValueError('Active UI owner mismatch; supply --owner for this task')
    root = Path(active['root'])
    if not root.is_absolute() or root.resolve() != root or root.parent != state_dir.resolve():
        raise ValueError('Active UI root is outside the guard state directory')
    if identity(root) != active.get('identity'):
        raise ValueError('Active UI root identity mismatch')
    ledger = json.loads((state_dir/'state.json').read_text())
    if not any(entry.get('path') == str(root) and entry.get('owner') == active['owner']
               and entry.get('identity') == active['identity'] for entry in ledger.get('roots', [])):
        raise ValueError('Active UI root is absent from guard ledger')
    if expected is not None and active != expected:
        raise ValueError('Active UI job changed')
    return root, active


def run(command, state_dir=DEFAULT_STATE, owner=None):
    state_dir = Path(state_dir).resolve()
    root, active = active_root(state_dir, owner)
    state = root/'ui-state.json'
    previous = json.loads(state.read_text())
    command = dict(command, sequence=(previous.get('sequence') or 0)+1)
    active_root(state_dir, owner, active)
    pending = root/'ui-command.next'
    pending.write_text(json.dumps(command))
    pending.replace(root/'ui-command.json')
    if command['action'] == 'finish':
        print('Finish requested; wait for guard cleanup.')
        return
    deadline = time.monotonic()+45
    while time.monotonic() < deadline:
        active_root(state_dir, owner, active)
        try:
            current = json.loads(state.read_text())
        except (FileNotFoundError, json.JSONDecodeError):
            time.sleep(.2)
            continue
        if current.get('sequence') == command['sequence']:
            print('sequence', current['sequence'], 'error', current.get('error'),
                  'hierarchyUnavailable', current.get('hierarchyUnavailable', False))
            for node in current.get('nodes', []):
                print(json.dumps({key: node[key] for key in ['text','id','bounds','clickable','enabled']}))
            return
        time.sleep(.2)
    raise RuntimeError('UI action response deadline exceeded')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--owner', default=os.environ.get('CODEX_GUARD_OWNER'))
    parser.add_argument('--state-dir', type=Path, default=DEFAULT_STATE)
    args = parser.parse_args()
    run(json.loads(sys.stdin.read()), args.state_dir, args.owner)
