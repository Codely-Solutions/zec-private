"""Signing controller; never imports or executes candidate source/build scripts."""
import base64, hashlib, importlib.util, json, os, re, shutil, subprocess, tempfile, zipfile
from unittest.mock import patch
from pathlib import Path
from apk_architecture import check_apk
from acceptance_policy import input_fingerprint, validate_core, validate_quotes, validate_binding, verify_promotion_revision, validate_core_sources, CORE_BINDING
from foundation_architecture import validate_graph_binding

ROOT=Path(__file__).resolve().parents[1]
REPO=os.environ['GITHUB_REPOSITORY']
CERT='410174e0d680638d19647f46e0e4b41ea6253c2351adfa84c5d9b1788d2da168'
def run(*args): return subprocess.check_output(args,text=True).strip()


def require_current_controller(controller):
    if os.environ.get('GITHUB_REF') != 'refs/heads/main' or run('git', 'rev-parse', 'HEAD') != controller:
        raise RuntimeError('Signing requires the checked-out main controller')
    remote = run('gh', 'api', f'repos/{REPO}/git/ref/heads/main', '--jq', '.object.sha')
    if remote != controller:
        raise RuntimeError('Main changed since validation began; resolve a new promotion')

def verify_release_identity(proof, source):
    properties = run('git', 'show', f'{source}:gradle.properties')
    codes = re.findall(r'^ZCASH_VERSION_CODE=([0-9]+)$', properties, re.MULTILINE)
    names = re.findall(r'^ZCASH_VERSION_NAME=([0-9]+\.[0-9]+\.[0-9]+)$', properties, re.MULTILINE)
    revision = json.loads(run('git', 'show', f'{source}:.private/release-revision.json'))['private_revision']
    metadata = '.private/candidate.json'
    if not run('git', 'ls-tree', '--name-only', source, '--', metadata):
        metadata = '.private/upstream.json'
    upstream = json.loads(run('git', 'show', f'{source}:{metadata}'))
    version = re.fullmatch(r'([0-9]+\.[0-9]+\.[0-9]+)-[0-9]+', upstream['tag'])
    if (len(codes) != 1 or len(names) != 1 or type(revision) is not int or not 1 <= revision <= 999
            or not version or version[1] != names[0]):
        raise RuntimeError('Invalid source release identity')
    expected = {
        'versionCode': int(codes[0]), 'versionName': names[0],
        'release_tag': f'v{version[1]}-private.{revision}',
        'upstream': dict(upstream, versionCode=int(codes[0])),
    }
    if any(proof.get(field) != value for field, value in expected.items()):
        raise RuntimeError('Artifact release identity differs from immutable source')


def release_title(tag):
    match=re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+-private\.([0-9]+)',tag) if isinstance(tag,str) else None
    if match is None: raise RuntimeError('Invalid private release tag')
    return f'ZEC Private — Release {int(match[1])}'

def release_notes(proof):
    tag=proof['release_tag']
    changes=json.loads((ROOT/'.private/release-notes.json').read_text()).get(tag,[])
    if not isinstance(changes,list) or len(changes)>6 or any(not isinstance(item,str) or not item.strip() or len(item)>300 for item in changes):
        raise RuntimeError('Release changes must be a list of nonempty strings')
    upstream=f'https://github.com/zodl-inc/zodl-android/releases/tag/{proof["upstream"]["tag"]}'
    lines=[release_title(tag), '', *[f'- {item}' for item in changes]]
    if changes: lines.append('')
    lines.extend([
        'Install **zec-private-latest.apk** (ARM64 Android). Check `SHA256SUMS`; install over the existing wallet.',
        f'Android versionCode: {proof["versionCode"]}', '',
        f'[Upstream changes]({upstream}) · '
        f'[Wallet guide](https://github.com/{REPO}/blob/{tag}/docs/WALLET-GUIDE.md) · '
        f'[Validation details](https://github.com/{REPO}/blob/main/.private/release-evidence/{tag}.json)',
    ])
    return '\n'.join(lines)+'\n'

def record_integration(path, upstream):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(upstream,indent=2)+'\n')

def simulation_receipt(proof, signed, digest, dist):
    negative=json.loads((ROOT/'artifact/quote-rendering-negative.json').read_text())
    if (negative.get('source_sha')!=proof['source_sha'] or not negative.get('original_crash_reproduced') or
            negative.get('quote_modes_tested')!=3 or not negative.get('source_restored')):
        raise RuntimeError('Missing original quote-crash regression evidence')
    original_state=(ROOT/'.private/upstream.json').read_bytes()
    with tempfile.TemporaryDirectory(dir=os.environ['RUNNER_TEMP']) as temp:
        temp=Path(temp)
        state=temp/'.private/upstream.json'
        record_integration(state,json.loads(original_state))
        record_integration(state,proof['upstream'])
        expected=json.loads((ROOT/'.private/source-state.json').read_text())
        (temp/'.private/source-state.json').write_text(json.dumps(expected))
        spec=importlib.util.spec_from_file_location('simulation_sync',ROOT/'.private/sync.py')
        sync=importlib.util.module_from_spec(spec);spec.loader.exec_module(sync)
        release={'tag_name':proof['upstream']['tag'],'draft':False,'prerelease':False,
                 'published_at':proof['upstream']['published_at']}
        outputs={}
        with patch.object(sync,'ROOT',temp),patch.object(sync,'source_state',return_value=expected),patch.object(sync,'gh',return_value=[release]),patch.object(sync,'output',side_effect=lambda **values:outputs.update(values)):
            sync.main()
        if outputs!={'changed':'false'}: raise RuntimeError('Duplicate simulated polling was not skipped')
        tampered=temp/'tampered.apk'
        shutil.copy2(signed,tampered)
        with zipfile.ZipFile(tampered,'a') as archive:
            archive.writestr('simulation-tamper.txt','Expected signature verification failure')
        tools=Path(os.environ['ANDROID_HOME'])/'build-tools/36.0.0'
        check=subprocess.run([str(tools/'apksigner'),'verify',str(tampered)],capture_output=True)
        if check.returncode==0: raise RuntimeError('Tampered APK unexpectedly passed signature verification')
        if json.loads(state.read_text())!=proof['upstream']:
            raise RuntimeError('Simulated integration state did not advance')
    if (ROOT/'.private/upstream.json').read_bytes()!=original_state:
        raise RuntimeError('Simulation changed real integrated state')
    receipt=dict(simulation=True,original_quote_crash_reproduced=True,quote_modes_tested=3,source_sha=proof['source_sha'],upstream=proof['upstream'],
                 package=proof['package'],versionCode=proof['versionCode'],versionName=proof['versionName'],
                 apk_sha256=digest,certificate_sha256=CERT,signature_verified=True,
                 tampered_apk_rejected=True,duplicate_poll_skipped=True,
                 disposable_state_advanced=True,real_state_unchanged=True,remote_publication=False)
    (dist/'simulation-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print('Simulated upgrade signed and verified; real release/state unchanged.')
def main():
    if not json.loads((ROOT/'.private/release-policy.json').read_text())['publication_enabled']:
        raise RuntimeError('Publication gated: initial acceptance and immutable wallet SDK evidence required')
    artifact=ROOT/'artifact'
    apk=artifact/'zec-private-unsigned.apk'
    proof=json.loads((artifact/'proof.json').read_text())
    expected=os.environ['SOURCE_SHA']
    controller=os.environ['CONTROLLER_SHA']
    require_current_controller(controller)
    late_attestation=verify_promotion_revision(expected,controller)
    if proof['source_sha']!=expected or proof['apk_sha256']!=hashlib.sha256(apk.read_bytes()).hexdigest():
        raise RuntimeError('Artifact identity/digest mismatch')
    verify_release_identity(proof, expected)
    if proof['package']!='com.personal.zecprivate.foss' or proof['debuggable']:
        raise RuntimeError('Unexpected APK manifest identity')
    if proof.get('validation')!='unit-lint-detekt-live-ui-fork-v3':
        raise RuntimeError('Missing release validation evidence')
    # A pending core profile may build a candidate, but cannot enter signing.
    # Controller-owned attestation is independent of the untrusted build output.
    core_bytes=(ROOT/'.private/core-acceptance.json').read_bytes()
    core=json.loads(core_bytes)
    acceptance_revision=controller if late_attestation else expected
    core_inputs=validate_core_sources(core,expected,acceptance_revision)
    if proof.get('core_acceptance',{}).get('binding')!=CORE_BINDING:
        raise RuntimeError('Missing versioned candidate product-runtime binding')
    core_artifact=artifact/'core-acceptance.json'
    validate_binding(proof,'core_acceptance',core_inputs,core_bytes,
                     core_artifact.read_bytes() if core_artifact.exists() else None,allow_late=late_attestation)
    quote_bytes=(ROOT/'.private/quote-acceptance.json').read_bytes()
    quote=json.loads(quote_bytes)
    quote_inputs=input_fingerprint(expected,profile='production-quotes')
    validate_quotes(quote,quote_inputs)
    if input_fingerprint(quote['build_source_sha'],profile='production-quotes')!=quote_inputs:
        raise RuntimeError('Production quote inputs changed since executed acceptance')
    quote_artifact=artifact/'quote-acceptance.json'
    validate_binding(proof,'quote_acceptance',quote_inputs,quote_bytes,
                     quote_artifact.read_bytes() if quote_artifact.exists() else None,allow_late=late_attestation)
    contract_bytes=(artifact/'near-contract.json').read_bytes()
    contract=json.loads(contract_bytes)
    if (hashlib.sha256(contract_bytes).hexdigest()!=proof.get('live_contract_sha256') or
            not contract.get('success') or contract.get('source_sha')!=expected or
            contract.get('funds_transferred') or contract.get('wallet_secrets_used')):
        raise RuntimeError('Invalid live API contract evidence')
    if proof.get('workflow_run_id')!=os.environ.get('ARTIFACT_RUN_ID'):
        raise RuntimeError('Artifact came from a different validation run')
    runtime_bytes=(artifact/'runtime.json').read_bytes()
    if (hashlib.sha256(runtime_bytes).hexdigest()!=proof.get('runtime_graph_sha256') or
            json.loads(runtime_bytes).get('source_sha')!=expected):
        raise RuntimeError('Invalid runtime graph binding')
    validate_graph_binding((artifact/'foundation-runtime.json').read_bytes(),proof,expected)
    tag=proof['release_tag']
    title=release_title(tag)
    # Existing published tags/assets are immutable, including duplicate polling.
    existing=subprocess.run(['gh','release','view',tag,'--repo',REPO],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    if existing.returncode==0:
        raise RuntimeError('Release tag already exists; refusing to replace immutable assets')
    releases=json.loads(run('gh','api',f'repos/{REPO}/releases?per_page=100'))
    previous=[int(r['body'].split('Android versionCode: ')[1].split()[0]) for r in releases if not r['draft'] and 'Android versionCode: ' in (r['body'] or '')]
    if previous and proof['versionCode']<=max(previous): raise RuntimeError('Android versionCode must increase')
    if proof.get('architecture') != check_apk(apk):
        raise RuntimeError('Release architecture evidence mismatch')
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
    notes.write_text(release_notes(proof))
    if os.environ.get('SIMULATION')=='true':
        try:
            simulation_receipt(proof,signed,digest,dist)
        finally:
            signed.unlink(missing_ok=True)
        print('Removed signed test APK after its final verification.')
        return
    # Release publication occurs only after all verification. No upstream code runs here.
    validation=dist/'VALIDATION.json'
    validation.write_text(json.dumps(dict(source_sha=expected,controller_sha=controller,artifact_run_id=proof['workflow_run_id'],
        apk_sha256=digest,core=core,production_quotes=quote,production_arm_executed=False,real_funds_used=False),indent=2)+'\n')
    validation_bytes=validation.read_bytes()
    require_current_controller(controller)
    run('gh','release','create',tag,str(signed),str(checksum),'--repo',REPO,'--target',expected,'--title',title,'--notes-file',str(notes))
    # Advance the integrated state only after publication. Candidate is already a full patched tree.
    run('git','fetch','origin',expected)
    run('git','checkout','--detach',controller if late_attestation else expected)
    record_integration(ROOT/'.private/upstream.json',proof['upstream'])
    evidence=ROOT/'.private/release-evidence'/f'{tag}.json'
    evidence.parent.mkdir(exist_ok=True)
    if evidence.exists():
        raise RuntimeError('Refusing to replace an existing release evidence record')
    evidence.write_bytes(validation_bytes)
    run('git','add','.private/upstream.json',str(evidence.relative_to(ROOT)))
    run('git','-c','user.name=ZEC Private automation','-c','user.email=zec-private@users.noreply.github.com','commit','--allow-empty','-m','Record verified release '+tag)
    new=run('git','rev-parse','HEAD')
    # Candidate trees retain the controller as a parent. Normal fast-forward
    # pushes preserve history and reject concurrent source/control modifications.
    run('git','push','origin',new+':refs/heads/main')
    print('Published signed release '+tag+' SHA256 '+digest)

if __name__=='__main__': main()
