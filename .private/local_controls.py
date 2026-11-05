"""Bind broad source scans to the maintained manifest without oversized guard receipts."""
import importlib.util
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('sync', ROOT / '.private/sync.py')
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)
expected = json.loads((ROOT / '.private/source-state.json').read_text())


def assert_sources():
    if sync.source_state(ROOT) != expected:
        raise RuntimeError('Source bytes differ from the guard-bound input manifest')


if __name__ == '__main__':
    assert_sources()
    try:
        subprocess.run([sys.executable, '-B', str(ROOT / '.private/test_controls.py')], check=True, timeout=120)
    finally:
        assert_sources()
