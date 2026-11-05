"""Reviewed project-specific Android adapter using default shared admission."""
import sys,json,hashlib,shutil
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
    for name in ('tor-probe-result.json','tor-probe-diagnostic.txt','command.log','emulator.log','tor-ui-result.json','tor-differential-result.json','old-native-diagnostic.txt','fixed-native-diagnostic.txt','ui-instrumentation.log','phase1-scenario-result.json','phase1-scenario-error.json'):
        source=root/name
        if source.exists():
            with source.open('rb') as stream:
                stream.seek(max(0,source.stat().st_size-12000));data=stream.read(12000)
            (receipt_dir/name).write_bytes(data)
            retained[name]={'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
    footprint={'logicalBytes':sum(p.stat().st_size for p in root.rglob('*') if p.is_file()),'hostFreeBeforeCleanup':shutil.disk_usage(root.anchor).free}
    return {'footprint':footprint,'androidGuest':'fresh ephemeral API35 x86_64','lifetime':'enclosing Windows Job kills and verifies entire guest process tree','retainedEvidence':retained}
result=guard.run(spec,guard.state_root(BASE/'state'),receipt_dir/'tor-emulator-policy.json',finalize=retain)
result['hostFreeAfterCleanup']=shutil.disk_usage(BASE.anchor).free
print(json.dumps(result))
raise SystemExit(0 if result['accepted'] else 1)
