"""Exercise two real blank guests and an intentional worker failure under the guard."""
import hashlib
import json
import os
from pathlib import Path

from emulator_batch import execute, read_plan

root = Path(os.environ['CODEX_GUARD_OUTPUT'])
source = Path(__file__).parent
manifest = source / 'emulator-smoke-manifest.json'
plan = root / 'plan.json'
plan.write_text(json.dumps(dict(schema=1, workers=[dict(name=name,
    adapter=str(source / 'emulator_smoke.py'), manifest=str(manifest),
    manifest_sha256=hashlib.sha256(manifest.read_bytes()).hexdigest(), seconds=360) for name in ('first', 'second')])))
result = execute(read_plan(plan), root, smoke=True)
assert not result['passed'] and result['stop_reason'] == 'worker-failure'
assert result['workers'][0]['status'] == 'failed' and result['workers'][0]['exit_code'] != 0
assert result['workers'][1]['status'] == 'cancelled-after-peer-failure'
assert 'Intentional batch cancellation probe' in (Path(result['workers'][0]['root']) / 'command.log').read_text()
proofs = [json.loads((Path(w['root']) / 'smoke-result.json').read_text()) for w in result['workers']]
assert all(p['concurrent_isolation_passed'] for p in proofs)
assert proofs[0]['expected_failure'] and all(proofs[0]['graceful_cleanup'].values())
assert len({p['boot_id'] for p in proofs}) == len({p['marker'] for p in proofs}) == 2
(root / 'batch-smoke-verification.json').write_text(json.dumps(dict(
    passed=True, evidence_class='blank-guest-isolation-and-failure-cancellation',
    expected_worker_failure=True, product_acceptance=False, proofs=proofs), indent=2))
print('Two isolated blank guests verified; intentional failure cancelled its peer. Outer Job verifies descendant cleanup.')
