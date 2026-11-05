"""Enroll isolated testnet Android acceptance in the shared Windows guard."""
import hashlib
import json
import shutil
import sys
from pathlib import Path

BASE=Path(r'C:\Users\User\.codex\workflow-guards')
sys.path.insert(0,str(BASE))
import guard

PROJECT=Path(__file__).resolve().parents[1]
PARENT=PROJECT.parent
spec=guard.read(PARENT/'core-emulator-job.json')
spec['sources'].append(str(Path(__file__).resolve()))
for name in ('core-testnet-result.json','core-testnet-diagnostic.json','core-testnet-progress.json','core-command.log','core-emulator.log','core-tor-probe-result.json'):
    # Only disposable outputs owned by this adapter; prior attempts are copied
    # to named evidence before rerun. Never let stale diagnostics describe it.
    (PARENT/name).unlink(missing_ok=True)


def retain(root):
    retained={}
    for name in ('tor-probe-result.json','core-testnet-result.json','core-testnet-diagnostic.json','core-testnet-progress.json','command.log','emulator.log'):
        path=root/name
        if path.exists():
            data=path.read_bytes()
            limit=32768 if name.endswith('.json') else 12000
            if len(data)>limit:
                if name.endswith('.json'):raise ValueError('Core evidence exceeds compact retention bound')
                data=data[-limit:]
            (PARENT/('core-'+name if name in ('command.log','emulator.log','tor-probe-result.json') else name)).write_bytes(data)
            retained[name]=dict(bytes=len(data),sha256=hashlib.sha256(data).hexdigest())
    return dict(androidGuest='blank ephemeral API35 x86_64; isolated valueless testnet wallets',
                retainedEvidence=retained,
                footprint=dict(logicalBytes=sum(p.stat().st_size for p in root.rglob('*') if p.is_file()),
                               hostFreeBeforeCleanup=shutil.disk_usage(root.anchor).free))


result=guard.run(spec,guard.state_root(BASE/'state'),PARENT/'tor-emulator-policy.json',finalize=retain)
(PARENT/'core-emulator-guard.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({key:result.get(key) for key in ('job','accepted','cleanupComplete','ownedSurvivors','peakMeasuredBytes','error')}))
raise SystemExit(0 if result['accepted'] else 1)
