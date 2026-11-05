"""Resolve only a successful candidate from this repository's release workflow."""
import json, os, re, subprocess

run_id=os.environ['CANDIDATE_RUN']
if not re.fullmatch(r'[1-9][0-9]*',run_id):
    raise SystemExit('Invalid candidate run')
repo=os.environ['GITHUB_REPOSITORY']
run=json.loads(subprocess.check_output(['gh','api',f'repos/{repo}/actions/runs/{run_id}'],text=True))
if (run['conclusion']!='success' or run['event']!='workflow_dispatch' or
        run['path']!='.github/workflows/build-release.yml' or run['head_branch']!='main'):
    raise SystemExit('Candidate must be a successful main-branch release validation run')
with open(os.environ['GITHUB_OUTPUT'],'a') as output:
    output.write('source_sha='+run['head_sha']+'\n')

# A promotion cannot silently discard changes made after candidate validation.
if run['head_sha']!=os.environ['GITHUB_SHA']:
    raise SystemExit('Candidate is not the current controller/source revision; review intervening changes')
