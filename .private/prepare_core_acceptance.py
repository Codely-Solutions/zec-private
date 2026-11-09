"""Bind immutable CI artifacts to the current guarded host harness."""
import json
import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
PARENT=ROOT.parent
sys.path.insert(0,str(ROOT/'.private'))
from acceptance_policy import input_fingerprint
GH=r'C:\Program Files\GitHub CLI\gh.exe'


def api(path):
    return json.loads(subprocess.check_output([GH,'api','repos/Codely-Solutions/zec-private/'+path],text=True))


parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--run-id',required=True,type=int)
parser.add_argument('--baseline-artifact',required=True,type=int)
args=parser.parse_args()
assert args.run_id>0 and args.baseline_artifact>0
run=api('actions/runs/'+str(args.run_id))
assert run['conclusion']=='success' and run['head_branch']=='main'
assert run['path']=='.github/workflows/core-validation.yml'
candidate=api('actions/runs/'+str(args.run_id)+'/artifacts')['artifacts']
candidate=next(a for a in candidate if a['name']=='core-testnet-candidate' and not a['expired'])
baseline=api('actions/artifacts/'+str(args.baseline_artifact))
assert not baseline['expired'] and baseline['name']=='core-testnet-baseline'
build_inputs=input_fingerprint(run['head_sha'],profile='core-build')
assert build_inputs==input_fingerprint(profile='core-build'), 'Compiled relevant inputs changed'
manifest={key:candidate[key] for key in ('id','size_in_bytes','digest')}
properties=subprocess.check_output(['git','show',run['head_sha']+':gradle.properties'],cwd=ROOT,text=True)
candidate_code=int(re.search(r'^ZCASH_VERSION_CODE=(\d+)$',properties,re.M)[1])
assert candidate_code>3234006
manifest.update(probeKind='core-testnet',package='com.personal.zecprivate.testnet.foss.debug',
                candidate_version_code=candidate_code,baseline_version_code=3234006,
                build_source_sha=run['head_sha'],build_input_fingerprint=build_inputs,
                input_fingerprint=input_fingerprint(),baseline_source_sha='a0bd21f27bb53af0eeb9b433d0273c6f39b04e19',
                upgradeBaseline={**{key:baseline[key] for key in ('id','size_in_bytes','digest')},
                                 'source_sha':'a0bd21f27bb53af0eeb9b433d0273c6f39b04e19'})
(PARENT/'tor-probe-artifact.json').write_text(json.dumps(manifest,indent=2)+'\n')
(PARENT/'core-artifacts.json').write_text(json.dumps(dict(candidate=candidate,baseline=baseline,run=run['id']),indent=2)+'\n')

old=json.loads((PARENT/'tor-emulator-job.json').read_text())
old.update(owner='zec-private-core-validation',purpose='Real installed-app testnet receive/send, update, persistence, restoration and Tor interruption',
           seconds=3000,reserveBytes=8450000000,
           cleanup='Stop exact blank emulator/private ADB Job and destroy isolated test-wallet seeds, userdata and transient output')
names=('run_tor_emulator.py','emulator_session.py','strip_emulator_apk.py','tor_differential_driver.py',
       'core_testnet_driver.py','core_faucet.py','core-network-policy.json','acceptance_policy.py',
       'core-wallet/src/co/electriccoin/zcash/app/CoreWalletAcceptance.kt','core-wallet/include.gradle')
old['sources']=[str(ROOT/'.private'/name) for name in names]+[str(PARENT/'tor-probe-artifact.json')]+[
    value for value in old['sources'] if value.startswith('C:\\')]
(PARENT/'core-emulator-job.json').write_text(json.dumps(old,indent=2)+'\n')
print(json.dumps(dict(candidate_run=run['id'],candidate_artifact=candidate['id'],baseline_artifact=baseline['id'],
                      build_source=run['head_sha'],compiled_inputs_match=True)))
