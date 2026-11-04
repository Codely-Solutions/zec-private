"""Trusted controller: fetch stable upstream, gate critical diffs, apply exact patch."""
import hashlib, json, os, re, shutil, subprocess
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
OFFICIAL='zodl-inc/zodl-android'
def run(*args,cwd=ROOT):
    return subprocess.check_output(args,cwd=cwd,text=True).strip()
def gh(path): return json.loads(run('gh','api',path))
def version(tag):
    match=re.fullmatch(r'(\d+)\.(\d+)\.(\d+)-(\d+)',tag)
    if not match: raise RuntimeError('Unsupported upstream version format; review required')
    return tuple(map(int,match.groups()))
def critical(path):
    sensitive_screen=re.match(
        r'^ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/'
        r'(authentication|walletbackup|restore|onboarding|exportvk|exportdata|deletewallet|tor|'
        r'chooseserver|send|pay|swap|reviewtransaction|transactionprogress|receive|request|qrcode|'
        r'scan|signkeystonetransaction|connecthw|connectkeystone|scankeystone|selectkeystoneaccount|'
        r'choosehwwallet|ironwood|advancedsettings)/',path)
    return (bool(sensitive_screen) or path.startswith(('build-conventions','buildSrc/','gradle/','feature-','sdk-',
                            'app/src/','tools/','preference-','crash-')) or
            path in ('gradlew','gradlew.bat') or
            path.endswith(('.gradle.kts','gradle.lockfile','AndroidManifest.xml')) or
            path.startswith('ui-lib/src/main/java/co/electriccoin/zcash/ui/common/') or
            '/network/' in path or '/security/' in path or '/di/' in path or
            'network_security' in path or
            ('/src/test/' in path and any(word in path.lower() for word in ('swap','near','tor','wallet','sign'))))
def output(**values):
    with open(os.environ['GITHUB_OUTPUT'],'a') as stream:
        for key,value in values.items(): stream.write(f'{key}={value}\n')

def source_state(root):
    paths=run('git','ls-files',cwd=root).splitlines()
    def digest(path):
        data=path.read_bytes()
        try:
            data.decode('utf-8')
            data=data.replace(b'\r\n',b'\n')
        except UnicodeDecodeError: pass
        return hashlib.sha256(data).hexdigest()
    return {p:digest(root/p) for p in paths
            if not p.startswith(('.private/','.github/')) and p!='README.md' and (root/p).is_file()}

def sanitize_provider(root):
    # Keep the shared upstream JWT out of the maintained patch. The exact original
    # provider is verified by digest before deleting the credential and its uses.
    path=root/'ui-lib/src/main/java/co/electriccoin/zcash/ui/common/provider/NearApiProvider.kt'
    text=path.read_text().replace('\r\n','\n')
    expected='67a73e8a3b1a7e23715f9c80cef47ed5841ddd9eca04bd0a3e9c5e207becd301'
    if hashlib.sha256(text.encode()).hexdigest()!=expected:
        raise RuntimeError('Upstream NEAR provider changed; credential removal needs review')
    if text.count('header(HttpHeaders.Authorization, AUTH_TOKEN)')!=3:
        raise RuntimeError('Unexpected upstream authentication structure')
    text=re.sub(r'\nprivate const val AUTH_TOKEN =[\s\S]*','\n',text)
    text=text.replace('import io.ktor.client.request.header\n','').replace('import io.ktor.http.HttpHeaders\n','')
    text=text.replace('                header(HttpHeaders.Authorization, AUTH_TOKEN)\n','')
    path.write_text(text.rstrip()+'\n')

def main():
    expected=json.loads((ROOT/'.private/source-state.json').read_text())
    if source_state(ROOT)!=expected:
        raise RuntimeError('Fork source changed outside maintained patch; preserve modifications and review patch state')
    state=json.loads((ROOT/'.private/upstream.json').read_text())
    releases=gh(f'repos/{OFFICIAL}/releases?per_page=100')
    stable=[r for r in releases if not r['draft'] and not r['prerelease'] and r.get('published_at')]
    latest=max(stable,key=lambda r:r['published_at'])
    tag=latest['tag_name']
    if version(tag)<=version(state['tag']):
        output(changed='false'); return
    if not latest['html_url'].startswith(f'https://github.com/{OFFICIAL}/releases/tag/'):
        raise RuntimeError('Unexpected upstream release repository')
    run('git','fetch','--no-tags',f'https://github.com/{OFFICIAL}.git',f'refs/tags/{tag}')
    commit=run('git','rev-parse','FETCH_HEAD^{commit}')
    run('git','fetch','--no-tags',f'https://github.com/{OFFICIAL}.git',state['sha'])
    changes=run('git','diff','--name-only',state['sha'],commit).splitlines()
    unsafe=[p for p in changes if critical(p)]
    if 'gradle.properties' in changes:
        old=run('git','show',state['sha']+':gradle.properties')
        new=run('git','show',commit+':gradle.properties')
        normalize=lambda text: re.sub(r'^ZCASH_VERSION_(?:CODE|NAME)=.*$','',text,flags=re.M)
        if normalize(old)!=normalize(new): unsafe.append('gradle.properties (dependencies or security configuration)')
    if unsafe: raise RuntimeError('Critical upstream changes require review: '+', '.join(unsafe[:25]))
    branch='updates/'+tag
    # A fresh detached worktree prevents upstream workflows from replacing the controller.
    candidate=Path(os.environ['RUNNER_TEMP'])/'candidate'
    run('git','worktree','add','--detach',str(candidate),commit)
    try:
        run('git','apply','--unidiff-zero','--check',str(ROOT/'.private/fork.patch'),cwd=candidate)
        run('git','apply','--unidiff-zero',str(ROOT/'.private/fork.patch'),cwd=candidate)
        sanitize_provider(candidate)
        shutil.rmtree(candidate/'.github/workflows')
        (candidate/'.github/dependabot.yml').unlink(missing_ok=True)
        shutil.copytree(ROOT/'.github/workflows',candidate/'.github/workflows')
        shutil.copytree(ROOT/'.private',candidate/'.private')
        shutil.copy2(ROOT/'README.md',candidate/'README.md')
        for path in ('docs/UPSTREAM-README.md','docs/UPSTREAM-dependabot.yml'):
            shutil.copy2(ROOT/path,candidate/path)
        revision=int(json.loads((ROOT/'.private/release-revision.json').read_text())['private_revision'])
        if revision<1 or revision>999: raise RuntimeError('Invalid private revision')
        code=max(int(state.get('versionCode',0))+1,version(tag)[3]*1000+revision)
        props=candidate/'gradle.properties'
        props.write_text(re.sub(r'^ZCASH_VERSION_CODE=\d+$',f'ZCASH_VERSION_CODE={code}',props.read_text(),flags=re.M))
        (candidate/'.private/candidate.json').write_text(json.dumps({'tag':tag,'sha':commit,'published_at':latest['published_at'],'versionCode':code},indent=2)+'\n')
        run('python3','.private/check.py',cwd=candidate)
        run('git','add','-A',cwd=candidate)
        (candidate/'.private/source-state.json').write_text(json.dumps(source_state(candidate),indent=2)+'\n')
        run('git','add','.private/source-state.json',cwd=candidate)
        run('git','-c','user.name=ZEC Private automation','-c','user.email=zec-private@users.noreply.github.com','commit','-m',f'Prepare upstream {tag} with private fork changes',cwd=candidate)
        tree=run('git','rev-parse','HEAD^{tree}',cwd=candidate)
        controller=run('git','rev-parse','HEAD')
        # The validated tree descends from the controller, allowing a normal
        # fast-forward integration while recording the exact upstream parent.
        sha=run('git','-c','user.name=ZEC Private automation','-c',
                'user.email=zec-private@users.noreply.github.com','commit-tree',tree,
                '-p',controller,'-p',commit,'-m',f'Integrate upstream {tag} with verified private patch',cwd=candidate)
        # Do not replace a previous candidate. A repeated poll can reuse only the exact same tree.
        existing=run('git','ls-remote','origin',f'refs/heads/{branch}')
        if existing:
            prior=existing.split()[0]
            run('git','fetch','origin',prior)
            if run('git','rev-parse',prior+'^{tree}')!=run('git','rev-parse',sha+'^{tree}',cwd=candidate):
                raise RuntimeError('Candidate branch differs; review required')
            sha=prior
        else: run('git','push','origin',f'{sha}:refs/heads/{branch}')
        output(changed='true',source_sha=sha,branch=branch)
    finally:
        run('git','worktree','remove','--force',str(candidate))

if __name__=='__main__': main()
