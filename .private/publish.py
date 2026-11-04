"""Signing controller; never imports or executes candidate source/build scripts."""
import base64, hashlib, json, os, re, subprocess, tempfile, zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
REPO=os.environ['GITHUB_REPOSITORY']
CERT='410174e0d680638d19647f46e0e4b41ea6253c2351adfa84c5d9b1788d2da168'
def run(*args): return subprocess.check_output(args,text=True).strip()
def main():
    if not json.loads((ROOT/'.private/release-policy.json').read_text())['publication_enabled']:
        raise RuntimeError('Publication gated: initial acceptance and immutable wallet SDK evidence required')
    artifact=ROOT/'artifact'
    apk=artifact/'zec-private-unsigned.apk'
    proof=json.loads((artifact/'proof.json').read_text())
    expected=os.environ['SOURCE_SHA']
    if proof['source_sha']!=expected or proof['apk_sha256']!=hashlib.sha256(apk.read_bytes()).hexdigest():
        raise RuntimeError('Artifact identity/digest mismatch')
    if proof['package']!='com.personal.zecprivate.foss' or proof['debuggable']:
        raise RuntimeError('Unexpected APK manifest identity')
    if proof.get('validation')!='unit-lint-detekt-fork-v1':
        raise RuntimeError('Missing release validation evidence')
    tag=proof['release_tag']
    if not re.fullmatch(r'v\d+\.\d+\.\d+-private\.\d+',tag): raise RuntimeError('Invalid private release tag')
    # Existing published tags/assets are immutable, including duplicate polling.
    existing=subprocess.run(['gh','release','view',tag,'--repo',REPO],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    if existing.returncode==0:
        print('Release already exists; refusing replacement'); return
    releases=json.loads(run('gh','api',f'repos/{REPO}/releases?per_page=100'))
    previous=[int(r['body'].split('Android versionCode: ')[1].split()[0]) for r in releases if not r['draft'] and 'Android versionCode: ' in (r['body'] or '')]
    if previous and proof['versionCode']<=max(previous): raise RuntimeError('Android versionCode must increase')
    tools=Path(os.environ['ANDROID_HOME'])/'build-tools/36.0.0'
    manifest=run(str(tools/'aapt'),'dump','badging',str(apk))
    package=re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",manifest)
    if (not package or package[1]!=proof['package'] or int(package[2])!=proof['versionCode'] or
            package[3]!=proof['versionName'] or 'application-debuggable' in manifest):
        raise RuntimeError('Signing job independently rejected APK manifest/proof mismatch')
    xml=run(str(tools/'aapt'),'dump','xmltree',str(apk),'AndroidManifest.xml')
    if 'com.personal.zecprivate.foss.provider' not in xml or '"co.electriccoin.zcash.foss.provider"' in xml:
        raise RuntimeError('Signing job rejected conflicting provider authority')
    with zipfile.ZipFile(apk) as archive:
        info=archive.getinfo('assets/UPSTREAM-LICENSE.txt')
        if info.file_size>8192 or archive.read(info)!=(ROOT/'LICENSE').read_bytes():
            raise RuntimeError('Signing job rejected missing or altered license')
    dist=ROOT/'dist';dist.mkdir(exist_ok=True)
    signed=dist/'zec-private-latest.apk'
    with tempfile.TemporaryDirectory() as temp:
        temp=Path(temp)
        key=temp/'release.p12';key.write_bytes(base64.b64decode(os.environ['RELEASE_KEYSTORE_BASE64'],validate=True));key.chmod(0o600)
        aligned=temp/'aligned.apk'
        run(str(tools/'zipalign'),'-f','-P','16','4',str(apk),str(aligned))
        run(str(tools/'apksigner'),'sign','--ks',str(key),'--ks-key-alias','zec-private','--ks-pass','env:RELEASE_STORE_PASSWORD','--key-pass','env:RELEASE_KEY_PASSWORD','--out',str(signed),str(aligned))
        report=run(str(tools/'apksigner'),'verify','--verbose','--print-certs',str(signed))
        fingerprint=re.search(r'certificate SHA-256 digest: ([0-9a-f]+)',report)
        if fingerprint is None or fingerprint[1]!=CERT: raise RuntimeError('Signing certificate mismatch')
        run(str(tools/'zipalign'),'-c','-P','16','4',str(signed))
    digest=hashlib.sha256(signed.read_bytes()).hexdigest()
    checksum=dist/'SHA256SUMS';checksum.write_text(digest+'  zec-private-latest.apk\n')
    notes=dist/'release-notes.md'
    notes.write_text(f'ZEC Private {tag}\n\nUpstream: {proof["upstream"]["tag"]}\nCommit: {proof["upstream"]["sha"]}\nAndroid versionCode: {proof["versionCode"]}\n\nFork: no affiliate fee, no partner JWT or referral; separate FOSS identity.\nNEAR platform, bridge, execution and network costs remain.\n\nValidated: Kotlin unit tests, Android lint, Detekt and static fork checks.\nNo real-money transactions were tested.\n\nUpstream changes: https://github.com/zodl-inc/zodl-android/releases/tag/{proof["upstream"]["tag"]}\n')
    # Release publication occurs only after all verification. No upstream code runs here.
    run('gh','release','create',tag,str(signed),str(checksum),'--repo',REPO,'--target',expected,'--title',tag,'--notes-file',str(notes))
    # Advance the integrated state only after publication. Candidate is already a full patched tree.
    run('git','fetch','origin',expected)
    run('git','checkout','--detach',expected)
    (ROOT/'.private/upstream.json').write_text(json.dumps(proof['upstream'],indent=2)+'\n')
    run('git','add','.private/upstream.json')
    run('git','-c','user.name=ZEC Private automation','-c','user.email=zec-private@users.noreply.github.com','commit','--allow-empty','-m','Record verified release '+tag)
    new=run('git','rev-parse','HEAD')
    # Candidate trees retain the controller as a parent. Normal fast-forward
    # pushes preserve history and reject concurrent source/control modifications.
    run('git','push','origin',new+':refs/heads/main')
    print('Published signed release '+tag+' SHA256 '+digest)

if __name__=='__main__': main()
