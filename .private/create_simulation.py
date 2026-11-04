"""Prepare an entirely local simulated stable update; never push a branch or release."""
import datetime,importlib.util,json,os,re,subprocess,tempfile
from pathlib import Path
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('sync',ROOT/'.private/sync.py')
sync=importlib.util.module_from_spec(spec);spec.loader.exec_module(sync)
state=json.loads((ROOT/'.private/upstream.json').read_text())
major,minor,patch_version,build=sync.version(state['tag'])
tag=f'{major}.{minor}.{patch_version+1}-{build+1}'
release={'tag_name':tag,'published_at':datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00','Z'),'draft':False,'prerelease':False,
         'html_url':'https://github.com/zodl-inc/zodl-android/releases/tag/'+tag}
original=sync.run
result={}
writes=[]
fake_upstream=None
def commands(*args,**kwargs):
    if args[:2]==('git','fetch'): return ''
    if args[:3]==('git','rev-parse','FETCH_HEAD^{commit}'): return fake_upstream
    if args[:2]==('git','ls-remote'): return ''
    if args[:2]==('git','push'):
        writes.append(args);return ''
    return original(*args,**kwargs)
before=(ROOT/'.private/upstream.json').read_bytes()
with tempfile.TemporaryDirectory(dir=os.environ['RUNNER_TEMP']) as temp:
    upstream=Path(temp)/'synthetic-upstream'
    original('git','worktree','add','--detach',str(upstream),state['sha'])
    try:
        props=upstream/'gradle.properties'
        props.write_text(re.sub(r'^ZCASH_VERSION_NAME=.*$',f'ZCASH_VERSION_NAME={major}.{minor}.{patch_version+1}',props.read_text(),flags=re.M))
        original('git','add','gradle.properties',cwd=upstream)
        original('git','-c','user.name=ZEC Private simulation','-c','user.email=zec-private@users.noreply.github.com','commit','-m','Disposable synthetic release metadata',cwd=upstream)
        fake_upstream=original('git','rev-parse','HEAD',cwd=upstream)
        with patch.dict(os.environ,{'RUNNER_TEMP':temp}),patch.object(sync,'gh',return_value=[release]),patch.object(sync,'run',side_effect=commands),patch.object(sync,'output',side_effect=lambda **values:result.update(values)):
            sync.main()
    finally:
        original('git','worktree','remove','--force',str(upstream))
if result.get('changed')!='true' or len(writes)!=1:
    raise SystemExit('Simulated stable detection/branch preparation failed')
if (ROOT/'.private/upstream.json').read_bytes()!=before:
    raise SystemExit('Simulation modified real integrated release state')
source=result['source_sha']
subprocess.run(['git','update-ref','refs/heads/simulation-local',source],cwd=ROOT,check=True)
bundle=ROOT/'simulation-source.bundle'
subprocess.run(['git','bundle','create',str(bundle),'simulation-local','^HEAD'],cwd=ROOT,check=True)
with open(os.environ['GITHUB_OUTPUT'],'a') as output:
    output.write('source_sha='+source+'\n')
print('Prepared disposable simulated update '+tag+'; no remote branch or release was written.')
