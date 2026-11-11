"""Trusted controller: fetch stable upstream, gate critical diffs, apply exact patch."""
import hashlib, json, os, re, shutil, subprocess, sys
from datetime import datetime
from pathlib import Path
from acceptance_policy import requirements
from release_metadata import Metadata, next_release_version, wallet_release_versions

ROOT=Path(__file__).resolve().parents[1]
OFFICIAL='zodl-inc/zodl-android'
BRANDING_ASSETS = {
    'app_logo', 'logo_with_hi', 'ic_item_zashi', 'ic_item_zodl_glyph',
    'zashi_text_logo_small', 'zashi_logo_without_text', 'zashi_logo_sign',
    'ic_zashi_logo_sign_warn', 'ic_zashi_logo_update_available', 'ic_ironwood_zodl_logos',
}
def run(*args,cwd=ROOT):
    if args[0]=='python3':
        args=(sys.executable,*args[1:])
    return subprocess.check_output(args,cwd=cwd,text=True).strip()
def gh(path): return json.loads(run('gh','api',path))
def fork_release_versions():
    return wallet_release_versions(Metadata().api('/releases?per_page=100', paginated=True))


def version(tag):
    match=re.fullmatch(r'(\d+)\.(\d+)\.(\d+)-(\d+)',tag)
    if not match: raise RuntimeError('Unsupported upstream version format; review required')
    return tuple(map(int,match.groups()))
def critical(path):
    # Changes confined to a removed project cannot execute in the candidate.
    # Its root, shared wiring and every retained feature remain review-gated.
    if any(path.startswith(project+'/') for project in excluded_projects()):
        return False
    # The reusable wallet fingerprints omit appearance/About-only inputs.
    # Review identity artwork and these surfaces before upstream integration.
    if ('/src/' in path and '/res/' in path and Path(path).stem in BRANDING_ASSETS):
        return True
    if any(part in path for part in ('/design/theme/', '/res/ui/about/', '/res/ui/theme/')):
        return True
    sensitive_screen=re.match(
        r'^ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/'
        r'(authentication|walletbackup|restore|onboarding|about|theme|exportvk|exportdata|deletewallet|tor|'
        r'chooseserver|donation|updates|send|pay|swap|reviewtransaction|transactionprogress|receive|request|qrcode|'
        r'scan|signkeystonetransaction|connecthw|connectkeystone|scankeystone|selectkeystoneaccount|'
        r'choosehwwallet|ironwood|advancedsettings)/',path)
    return (bool(sensitive_screen) or path.startswith(('build-conventions','buildSrc/','gradle/','feature-','sdk-',
                            'app/src/','tools/','scripts/','preference-','crash-',
                            'configuration-','spackle-','build-info-lib/','wallet-foundation-lib/')) or
            path in ('gradlew','gradlew.bat') or
            path.endswith(('.gradle.kts','gradle.lockfile','AndroidManifest.xml')) or
            path.startswith('ui-lib/src/main/java/co/electriccoin/zcash/ui/common/') or
            '/network/' in path or '/security/' in path or '/di/' in path or
            'network_security' in path or
            (any(part in path for part in ('/src/test/','/src/androidTest/')) and
             any(word in path.lower() for word in ('swap','near','tor','wallet','sign','send','restore','transaction'))))
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

def excluded_projects():
    projects=json.loads((ROOT/'.private/excluded-features.json').read_text())['forbidden_projects']
    if not all(re.fullmatch(r'[a-z][a-z0-9-]*',project) for project in projects):
        raise RuntimeError('Invalid excluded project identity')
    return projects

def remove_excluded_projects(candidate):
    parent=candidate.resolve()
    for project in excluded_projects():
        target=candidate/project
        if target.is_symlink() or getattr(target,'is_junction',lambda:False)():
            raise RuntimeError('Excluded project path is a link; review required')
        if target.exists():
            if not target.is_dir() or target.resolve().parent!=parent:
                raise RuntimeError('Excluded project escaped the candidate workspace')
            shutil.rmtree(target)

def overlay_github(candidate):
    # The controller owns all GitHub automation and repository metadata, including
    # funding, templates, local actions and scripts. Remove upstream-only files.
    target=candidate/'.github'
    if target.is_symlink() or getattr(target,'is_junction',lambda:False)():
        raise RuntimeError('Candidate GitHub metadata is a link; review required')
    if target.exists():
        if not target.is_dir() or target.resolve().parent!=candidate.resolve():
            raise RuntimeError('GitHub metadata escaped the candidate workspace')
        shutil.rmtree(target)
    shutil.copytree(ROOT/'.github',target,ignore=shutil.ignore_patterns('__pycache__','*.pyc'))

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
    # This file is excluded from fork.patch to keep the upstream credential out
    # of deleted hunks. Install the reviewed, credential-free fork implementation
    # after verifying the exact upstream input, preserving its Tor/requote fixes.
    relative=path.relative_to(root).as_posix()
    reviewed=(ROOT/relative).read_text().replace('\r\n','\n')
    recorded=json.loads((ROOT/'.private/source-state.json').read_text())[relative]
    if hashlib.sha256(reviewed.encode()).hexdigest()!=recorded:
        raise RuntimeError('Reviewed NEAR provider differs from source-state')
    if re.search(r'AUTH_TOKEN|Authorization|Bearer |X-API-Key|eyJhbGci',reviewed):
        raise RuntimeError('Reviewed NEAR provider contains partner authentication')
    path.write_text(reviewed)

def discover(cutoff=None):
    releases=gh(f'repos/{OFFICIAL}/releases?per_page=100')
    stable=[r for r in releases if not r['draft'] and not r['prerelease'] and r.get('published_at')
            and (cutoff is None or datetime.fromisoformat(r['published_at']) <= cutoff)]
    return max(stable,key=lambda r:r['published_at'],default=None)


def main(latest=None, wrapper=None):
    expected=json.loads((ROOT/'.private/source-state.json').read_text())
    if source_state(ROOT)!=expected:
        raise RuntimeError('Fork source changed outside maintained patch; preserve modifications and review patch state')
    state=json.loads((ROOT/'.private/upstream.json').read_text())
    latest=latest or discover()
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
    controller=run('git','rev-parse','HEAD')
    branch=f'updates/{tag}-{controller[:12]}'
    current_revision=json.loads((ROOT/'.private/release-revision.json').read_text())['private_revision']
    revision,code=next_release_version(fork_release_versions(),current_revision,state['versionCode'],version(tag)[3])
    # A fresh detached worktree prevents upstream workflows from replacing the controller.
    candidate=Path(os.environ['RUNNER_TEMP'])/'candidate'
    run('git','worktree','add','--detach',str(candidate),commit)
    try:
        run('git','apply','--unidiff-zero','--check',str(ROOT/'.private/fork.patch'),cwd=candidate)
        run('git','apply','--unidiff-zero',str(ROOT/'.private/fork.patch'),cwd=candidate)
        sanitize_provider(candidate)
        remove_excluded_projects(candidate)
        overlay_github(candidate)
        shutil.copytree(ROOT/'.private',candidate/'.private',ignore=shutil.ignore_patterns('__pycache__','*.pyc'))
        if wrapper:
            from maintenance_watch import WRAPPER_PROPERTIES
            path = candidate / WRAPPER_PROPERTIES
            if path.read_bytes() != (ROOT / WRAPPER_PROPERTIES).read_bytes():
                raise RuntimeError('Upstream patch changed the verified Gradle wrapper base')
            path.write_text(wrapper[1], encoding='ascii', newline='\n')
        shutil.copy2(ROOT/'README.md',candidate/'README.md')
        for path in ('docs/UPSTREAM-README.md','docs/UPSTREAM-dependabot.yml'):
            shutil.copy2(ROOT/path,candidate/path)
        props=candidate/'gradle.properties'
        properties, count=re.subn(r'^ZCASH_VERSION_CODE=\d+$',f'ZCASH_VERSION_CODE={code}',props.read_text(),flags=re.M)
        if count!=1 or re.findall(r'^ZCASH_VERSION_NAME=(.+)$',properties,re.M)!=[tag.rsplit('-',1)[0]]:
            raise RuntimeError('Upstream Gradle release coordinates disagree with the stable tag')
        props.write_text(properties)
        (candidate/'.private/release-revision.json').write_text(json.dumps({'private_revision':revision})+'\n')
        record = dict(tag=tag, sha=commit, published_at=latest['published_at'], versionCode=code,
                      acceptance=requirements(changes))
        if wrapper:
            record['wrapper'] = wrapper[0]
            record['wrapper_head'] = wrapper[2]
        (candidate/'.private/candidate.json').write_text(json.dumps(record,indent=2)+'\n')
        run('python3','-B','.private/check.py',cwd=candidate)
        run('python3','-B','.private/foundation_architecture.py',cwd=candidate)
        run('git','add','-A',cwd=candidate)
        from refresh_source import generate_patch, patch_paths
        (candidate/'.private/fork.patch').write_bytes(generate_patch(candidate,commit,patch_paths(excluded_projects())))
        (candidate/'.private/source-state.json').write_text(json.dumps(source_state(candidate),indent=2)+'\n')
        run('git','add','.private/source-state.json','.private/fork.patch',cwd=candidate)
        run('git','-c','user.name=ZEC Private automation','-c','user.email=zec-private@users.noreply.github.com','commit','-m',f'Prepare upstream {tag} with private fork changes',cwd=candidate)
        tree=run('git','rev-parse','HEAD^{tree}',cwd=candidate)
        # The validated tree descends from the controller, allowing a normal
        # fast-forward integration while recording the exact upstream parent.
        sha=run('git','-c','user.name=ZEC Private automation','-c',
                'user.email=zec-private@users.noreply.github.com','commit-tree',tree,
                '-p',controller,'-p',commit,'-m',f'Integrate upstream {tag} with verified private patch',cwd=candidate)
        # Do not replace a previous candidate. A repeated poll can reuse only the exact same tree.
        if wrapper:
            branch += '-gradle-' + wrapper[0] + '-' + wrapper[2][:12]
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
