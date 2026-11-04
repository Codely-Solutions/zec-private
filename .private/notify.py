"""Notifications contain release/control links only, never wallet data."""
import os, subprocess, sys, urllib.request

message=sys.argv[1]
repo=os.environ['GITHUB_REPOSITORY']
link=f'https://github.com/{repo}/actions/runs/{os.environ["GITHUB_RUN_ID"]}'
if os.environ.get('MAKE_ISSUE')=='true':
    # Fixed title deduplicates repeated failures until an operator closes the issue.
    title='ZEC Private update blocked: review required'
    found=subprocess.check_output(['gh','issue','list','--repo',repo,'--state','open','--search',title+' in:title','--json','number'],text=True)
    import json
    if not json.loads(found):
        subprocess.run(['gh','issue','create','--repo',repo,'--title',title,'--body',message+'\n\nWorkflow evidence: '+link+'\nLast published APK is unchanged.'],check=True)
url=os.environ.get('NTFY_URL')
if url:
    request=urllib.request.Request(url,data=(message+' '+link).encode(),headers={'Title':'ZEC Private','Priority':'default'},method='POST')
    with urllib.request.urlopen(request,timeout=20) as response:
        if response.status!=200: raise RuntimeError('ntfy notification failed')
    print('Push notification accepted by ntfy; phone delivery requires subscription.')
else:
    print('ntfy is not configured; use GitHub workflow and issue notifications.')
