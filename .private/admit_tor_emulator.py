"""Reviewed project-specific Android adapter using default shared admission."""
import sys,json,hashlib
from pathlib import Path
BASE=Path(r"C:\Users\User\.codex\workflow-guards")
sys.path.insert(0,str(BASE))
import guard
PROJECT=Path(__file__).resolve().parents[1]
receipt_dir=PROJECT.parent
spec=guard.read(receipt_dir/'tor-emulator-job.json')
spec['sources'].append(str(Path(__file__).resolve()))
def retain(root):
    retained={}
    for name in ('tor-probe-result.json','tor-probe-diagnostic.txt','command.log','emulator.log'):
        source=root/name
        if source.exists():
            with source.open('rb') as stream:
                stream.seek(max(0,source.stat().st_size-12000));data=stream.read(12000)
            (receipt_dir/name).write_bytes(data)
            retained[name]={'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
    return {'androidGuest':'fresh ephemeral API35 x86_64','lifetime':'enclosing Windows Job kills and verifies entire guest process tree','retainedEvidence':retained}
result=guard.run(spec,guard.state_root(BASE/'state'),receipt_dir/'tor-emulator-policy.json',finalize=retain)
print(json.dumps(result))
raise SystemExit(0 if result['accepted'] else 1)
