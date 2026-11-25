"""Signing controller; never imports or executes candidate source/build scripts."""
import base64, hashlib, importlib.util, json, os, re, shutil, subprocess, tempfile, zipfile
from unittest.mock import patch
from pathlib import Path
from apk_architecture import check_apk
from acceptance_policy import (
    CORE_BINDING, input_fingerprint, validate_binding, validate_core, validate_core_sources,
    validate_direct_core_candidate, validate_quotes, verify_promotion_revision,
)
from foundation_architecture import validate_graph_binding
from release_changelog import changes_for, hosted_changes
from release_metadata import (
    ARCHIVE_LIMITS, Metadata, PUBLIC_APK_LIMIT, WALLET_APK, checksum_bytes, find_release_tag, require,
    require_public_version, verify_direct_assembly, wallet_release_versions,
)

ROOT=Path(__file__).resolve().parents[1]
REPO=os.environ['GITHUB_REPOSITORY']
CERT='410174e0d680638d19647f46e0e4b41ea6253c2351adfa84c5d9b1788d2da168'
def run(*args):
    return subprocess.check_output(args, text=True, timeout=120).strip()


def current_main():
    return run('gh', 'api', f'repos/{REPO}/git/ref/heads/main', '--jq', '.object.sha')


def require_current_controller(controller):
    if os.environ.get('GITHUB_REF') != 'refs/heads/main' or run('git', 'rev-parse', 'HEAD') != controller:
        raise RuntimeError('Signing requires the checked-out main controller')
    if current_main() != controller:
        raise RuntimeError('Main changed since validation began; resolve a new promotion')


def require_current_local_upgrade(root=ROOT, metadata=None):
    local = json.loads((root / '.private/upgrade-baseline.json').read_text())
    hosted = json.loads((root / '.private/hosted-fixture.json').read_text())
    require(all(local[key] == hosted[key] for key in ('release', 'source_sha', 'version_code')),
            'Direct publication requires the paired fixture for the latest wallet release')
    from hosted_release import require_current_hosted_upgrade

    require_current_hosted_upgrade(root, metadata or Metadata(REPO))


def read_attestation(path, expected_digest):
    with path.open('rb') as stream:
        data = stream.read(256 * 1024 + 1)
    if len(data) > ARCHIVE_LIMITS['release-attestation'][1] or hashlib.sha256(data).hexdigest() != expected_digest:
        raise RuntimeError('Publication attestation digest or size mismatch')
    return json.loads(data)


def verify_attestation_publisher(proof, run, attempt):
    if 'publisher' in proof:
        require(proof['publisher'] == {'run': str(run), 'attempt': str(attempt)},
                'Publication attestation belongs to a different publisher run or attempt')


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

def release_notes(proof, changes=None):
    tag=proof['release_tag']
    if changes is None:
        changes = changes_for(json.loads((ROOT/'.private/release-notes.json').read_text(encoding='utf-8')), tag,
                              required=os.environ.get('SIMULATION') != 'true')
    upstream=f'https://github.com/zodl-inc/zodl-android/releases/tag/{proof["upstream"]["tag"]}'
    lines=[release_title(tag), '']
    if changes: lines.extend(['## What’s new', '', *[f'- {item}' for item in changes]])
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

def require_simulation_context(source, controller, hosted, recover_run):
    workflow = f'{REPO}/.github/workflows/upgrade-acceptance.yml@refs/heads/main'
    require(os.environ.get('GITHUB_WORKFLOW_REF') == workflow and os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
            and source != controller and not hosted and not recover_run,
            'Only the protected upgrade simulation can use diagnostic signing')


def verify_simulation_negative_control(proof):
    negative = json.loads((ROOT/'artifact/quote-rendering-negative.json').read_text())
    resource_path = 'ui-lib/src/main/res/ui/non_translatable/values/private_strings.xml'
    resource = subprocess.check_output(
        ['git', 'show', f'{proof["source_sha"]}:{resource_path}'], cwd=ROOT, timeout=120)
    require(negative.get('source_sha') == proof['source_sha']
            and negative.get('original_crash_reproduced') is True
            and negative.get('quote_modes_tested') == 3
            and negative.get('source_restored') is True
            and negative.get('restored_resource_sha256') == hashlib.sha256(resource).hexdigest(),
            'Missing original quote-crash regression evidence')


def simulation_receipt(proof, signed, digest, dist):
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
def sign_apk(apk, signed, tools):
    for name in ('RELEASE_KEYSTORE_BASE64', 'RELEASE_STORE_PASSWORD', 'RELEASE_KEY_PASSWORD'):
        if not os.environ.get(name):
            raise RuntimeError('Production signing environment did not provide ' + name)
    with tempfile.TemporaryDirectory() as scratch:
        temp = Path(scratch)
        key = temp / 'release.p12'
        key.write_bytes(base64.b64decode(os.environ['RELEASE_KEYSTORE_BASE64'], validate=True))
        key.chmod(0o600)
        aligned = temp / 'aligned.apk'
        run(str(tools / 'zipalign'), '-f', '-P', '16', '4', str(apk), str(aligned))
        run(str(tools / 'apksigner'), 'sign', '--ks', str(key), '--ks-key-alias', 'zec-private',
            '--ks-pass', 'env:RELEASE_STORE_PASSWORD', '--key-pass', 'env:RELEASE_KEY_PASSWORD',
            '--out', str(signed), str(aligned))
    report = run(str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(signed))
    fingerprints = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)', report)
    require(fingerprints == [CERT], 'Signing certificate mismatch')
    run(str(tools / 'zipalign'), '-c', '-P', '16', '4', str(signed))


def publication_origin(source, controller, recovery, hosted):
    if not hosted:
        return {}
    from custom_release import MARKER as CUSTOM_MARKER
    from maintenance_cadence import MARKER, changed_marker

    build_controller = recovery['original_controller'] if recovery else controller
    maintenance = changed_marker(ROOT, source, build_controller, MARKER)
    if changed_marker(ROOT, source, build_controller, CUSTOM_MARKER):
        maintenance = {'kind': 'custom'}
    elif maintenance.get('kind') != 'security':
        maintenance = {'kind': 'routine'}
    return {'maintenance': maintenance}


def main():
    if not json.loads((ROOT/'.private/release-policy.json').read_text())['publication_enabled']:
        raise RuntimeError('Publication gated: initial acceptance and immutable wallet SDK evidence required')
    artifact=ROOT/'artifact'
    apk=artifact/'zec-private-unsigned.apk'
    proof=json.loads((artifact/'proof.json').read_text())
    expected=os.environ['SOURCE_SHA']
    controller=os.environ['CONTROLLER_SHA']
    require_current_controller(controller)
    recovery = None
    reuse_funded = os.environ.get('REUSE_FUNDED') == 'true'
    hosted = os.environ.get('HOSTED_ACCEPTANCE') == 'true'
    if reuse_funded and not hosted:
        raise RuntimeError('Funded evidence reuse requires hosted acceptance')
    recover_run = os.environ.get('RECOVER_RUN', '')
    simulation = os.environ.get('SIMULATION') == 'true'
    if simulation:
        require_simulation_context(expected, controller, hosted, recover_run)
    if recover_run:
        if not hosted:
            raise RuntimeError('Quote recovery requires hosted acceptance')
        from quote_recovery import resolve

        recovery = resolve(ROOT, expected, controller, recover_run, Metadata(),
                           os.environ.get('RECOVER_QUOTE_RUN', ''), signing=True,
                           preclaim_run=os.environ.get('PRECLAIM_RUN', ''),
                           core_run=os.environ.get('RECOVER_CORE_RUN', ''))
        recovery['run'] = int(recover_run)
        if (recovery.get('reuse_funded') == 'true') != reuse_funded:
            raise RuntimeError('Recovery evidence policy differs from verified original run')
        for name in ('production_id', 'core_id', 'quote_id'):
            if recovery[name] != os.environ[name.upper()]:
                raise RuntimeError('Recovery artifact differs from verified original run')
    late_attestation = False if recovery else verify_promotion_revision(expected, controller)
    if proof['source_sha']!=expected or proof['apk_sha256']!=hashlib.sha256(apk.read_bytes()).hexdigest():
        raise RuntimeError('Artifact identity/digest mismatch')
    verify_release_identity(proof, expected)
    if proof['package']!='com.personal.zecprivate.foss' or proof['debuggable']:
        raise RuntimeError('Unexpected APK manifest identity')
    if proof.get('validation')!='unit-lint-detekt-live-ui-fork-v3':
        raise RuntimeError('Missing release validation evidence')
    # A pending core profile may build a candidate, but cannot enter signing.
    # Controller-owned attestation is independent of the untrusted build output.
    if proof.get('core_acceptance',{}).get('binding')!=CORE_BINDING:
        raise RuntimeError('Missing versioned candidate product-runtime binding')
    quote_inputs=input_fingerprint(expected,profile='production-quotes')
    reused_evidence = []
    if simulation:
        verify_simulation_negative_control(proof)
    elif hosted:
        from hosted_release import read_receipts, require_current_hosted_upgrade
        from maintenance_cadence import admit_cadence
        from maintenance_watch import api

        if not recovery:
            metadata = Metadata()
            require_current_hosted_upgrade(ROOT, metadata)
            admit_cadence(ROOT, expected, controller, metadata, api)
        if (proof['core_acceptance']['input_fingerprint'] != input_fingerprint(expected, profile='core-runtime')
                or proof['quote_acceptance']['input_fingerprint'] != quote_inputs):
            raise RuntimeError('Unsigned artifact acceptance fingerprints differ from the hosted candidate')
        core, quote, *reused_evidence = read_receipts(
            ROOT/'hosted-receipts', proof=proof, source=expected, controller=controller,
            production_id=os.environ['PRODUCTION_ID'], core_id=os.environ['CORE_ID'],
            quote_id=os.environ['QUOTE_ID'], recovery=recovery,
            direct_core_id=os.environ.get('DIRECT_CORE_ID', ''), reuse_funded=reuse_funded)
    else:
        from custom_release import MARKER as CUSTOM_MARKER

        if run('git', 'ls-tree', '--name-only', expected, '--', CUSTOM_MARKER):
            raise RuntimeError('Custom releases require hosted acceptance and explicit dispatch')
        require_current_local_upgrade()
        core_bytes=(ROOT/'.private/core-acceptance.json').read_bytes()
        core=json.loads(core_bytes)
        validate_direct_core_candidate(core, expected, proof['versionCode'])
        acceptance_revision=controller if late_attestation else expected
        core_inputs=validate_core_sources(core,expected,acceptance_revision)
        core_artifact=artifact/'core-acceptance.json'
        validate_binding(proof,'core_acceptance',core_inputs,core_bytes,
                         core_artifact.read_bytes() if core_artifact.exists() else None,allow_late=late_attestation)
        quote_bytes=(ROOT/'.private/quote-acceptance.json').read_bytes()
        quote=json.loads(quote_bytes)
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
    release_title(tag)
    changes = None
    if hosted:
        changes = hosted_changes(ROOT, expected, recovery['original_controller'] if recovery else controller)
    notes_text = release_notes(proof, changes) if not simulation else None
    # Existing published tags/assets are immutable, including duplicate polling.
    origin = publication_origin(expected, controller, recovery, hosted)
    if recovery:
        origin['quote_recovery'] = {'original_controller': recovery['original_controller'],
                                    'quote_run_id': quote['workflow_run_id']}
    archive_binding = {'production_artifact': quote['production_artifact']} if hosted else {}
    if not simulation:
        if not hosted and str(proof['workflow_run_id']) != os.environ['GITHUB_RUN_ID']:
            run_id = int(proof['workflow_run_id'])
            _, produced, producer = Metadata().producer(run_id, expected, 'build-release.yml', 'unsigned',
                                                       'validated-unsigned-apk')
            archive_binding = verify_direct_assembly(ROOT, expected, run_id, produced, producer, receipts={
                'core-acceptance.json': core_bytes, 'quote-acceptance.json': quote_bytes,
            })
        if release_for_tag(tag):
            raise RuntimeError('Release already exists; use reconciliation with its original attestation')
        require_advancing_version(proof['versionCode'], tag)
        verify_tag(tag, expected)
        from verify_public_release import payload, unsigned_proof

        checked_apk, checked_proof, checked_artifact, producer_job_id = unsigned_proof(dict(
            source_sha=expected, controller_sha=controller, artifact_run_id=proof['workflow_run_id'],
            **origin, **archive_binding))
        require(checked_proof == proof and checked_apk == apk.read_bytes(),
                'Signing input differs from the verified original archive')
        archive_binding = {'production_artifact': checked_artifact, 'production_job_id': producer_job_id}
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
    sign_apk(apk, signed, tools)
    signed_bytes = signed.read_bytes()
    if not simulation:
        require(0 < len(signed_bytes) <= PUBLIC_APK_LIMIT and payload(signed_bytes) == payload(checked_apk),
                'Signed APK exceeds the public budget or differs from the validated payload')
    digest = hashlib.sha256(signed_bytes).hexdigest()
    if simulation:
        try:
            simulation_receipt(proof,signed,digest,dist)
        finally:
            signed.unlink(missing_ok=True)
        print('Removed signed test APK after its final verification.')
        return
    (dist/'SHA256SUMS').write_bytes(checksum_bytes(digest))
    (dist/'release-notes.md').write_text(notes_text, encoding='utf-8')
    attestation = dict(release_tag=tag, versionCode=proof['versionCode'], versionName=proof['versionName'],
                       upstream=proof['upstream'], source_sha=expected, controller_sha=controller,
                       publisher={'run': os.environ['GITHUB_RUN_ID'], 'attempt': os.environ['GITHUB_RUN_ATTEMPT']},
                       artifact_run_id=proof['workflow_run_id'], apk_sha256=digest, core=core,
                       production_quotes=quote, production_arm_executed=False, real_funds_used=False)
    attestation.update(origin, **archive_binding)
    if reused_evidence:
        attestation.update(reused_evidence[0])
    attestation['release_notes'] = notes_text
    attestation['notes_sha256'] = hashlib.sha256(notes_text.strip().encode('utf-8')).hexdigest()
    data = (json.dumps(attestation, indent=2) + '\n').encode()
    if len(data) > ARCHIVE_LIMITS['release-attestation'][1]:
        raise RuntimeError('Publication attestation exceeds its size budget')
    (dist/'release-attestation.json').write_bytes(data)
    with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8') as output:
        output.write(f'release_tag={tag}\nattestation_digest={hashlib.sha256(data).hexdigest()}\n')
        output.write(f'attestation_attempt={attestation["publisher"]["attempt"]}\n')


APK = WALLET_APK
CHECKSUM = 'SHA256SUMS'
NOTES = 'release-notes.md'


def release_api(path, *, data=None, method=None):
    command = ['gh', 'api', f'repos/{REPO}/{path}']
    if data is not None:
        command += ['-X', method, '--input', '-']
    return json.loads(subprocess.check_output(command, text=True, timeout=45,
                                              input=json.dumps(data) if data is not None else None))


def release_for_tag(tag, *, releases=None):
    release_title(tag)
    if releases is None:
        releases = Metadata(repo=REPO).api('/releases?per_page=100', paginated=True)
    matches = [release for page in releases for release in page if release['tag_name'] == tag]
    require(len(matches) <= 1, 'Release tag is ambiguous')
    return matches[0] if matches else None


def tag_object(tag, api):
    release_title(tag)
    return find_release_tag(tag, api)


def verify_tag(tag, source, *, api=release_api, create=False):
    obj = tag_object(tag, api)
    if obj is None and create:
        api('git/refs', method='POST', data={'ref': 'refs/tags/' + tag, 'sha': source})
        obj = tag_object(tag, api)
        require(obj is not None, 'Expected the exact release tag after creation')
    if obj is not None:
        require(obj['type'] == 'commit' and obj['sha'] == source, 'Release tag points to a different source')
    return obj


def exact_assets(release, proof, *, complete):
    tag = proof['release_tag']
    require(release['tag_name'] == tag and release['prerelease'] is False,
            'Unexpected release state or tag')
    require_public_version(release, proof['versionCode'], 'Release notes differ from the validated version')
    if proof.get('notes_sha256'):
        require(hashlib.sha256(release['body'].strip().encode('utf-8')).hexdigest() == proof['notes_sha256'],
                'Release notes differ from the original publisher')
    expected_checksum = checksum_bytes(proof['apk_sha256'])
    expected = {APK: (proof['apk_sha256'], PUBLIC_APK_LIMIT),
                CHECKSUM: (hashlib.sha256(expected_checksum).hexdigest(), len(expected_checksum))}
    assets = {asset['name']: asset for asset in release['assets']}
    require(len(assets) == len(release['assets']) and assets.keys() <= expected.keys()
            and (not complete or assets.keys() == expected.keys()), 'Unexpected or incomplete release assets')
    uploaded = {}
    for name, asset in assets.items():
        if not complete and release['draft'] is True and asset['state'] == 'starter' and asset['size'] == 0:
            continue
        sha, maximum = expected[name]
        require(0 < asset['size'] <= maximum and asset['state'] == 'uploaded'
                and asset.get('digest') == 'sha256:' + sha, 'Release asset differs from the original publisher: ' + name)
        uploaded[name] = asset
    return uploaded


def require_advancing_version(version_code, own_tag):
    history = Metadata(repo=REPO).api('/releases?per_page=100', paginated=True)
    versions = wallet_release_versions([[release for page in history for release in page if release['tag_name'] != own_tag]])
    require(not versions or version_code > max(code for _, code in versions), 'Android versionCode must increase')


def require_publication_baseline(proof, root=None, metadata=None):
    from hosted_release import require_current_hosted_upgrade

    fixture = require_current_hosted_upgrade(root or ROOT, metadata or Metadata(repo=REPO))
    upgrade = proof['installed_upgrade'] if 'funded_reuse' in proof else proof['core']
    require(upgrade.get('baseline_source_sha') == fixture['source_sha']
            and upgrade.get('baseline_version_code') == fixture['version_code']
            and upgrade.get('hosted_fixture', fixture) == fixture,
            'The attested upgrade baseline differs from the latest public release fixture')


def expose_release(proof, dist, *, controller, existing=None):
    tag = proof['release_tag']
    source = proof['source_sha']
    verify_release_identity(proof, source)
    current = release_for_tag(tag)
    require((current is None and existing is None) or
            (current is not None and existing is not None and current['id'] == existing['id']
             and current['draft'] is True), 'Expected the exact unpublished release state')
    require_advancing_version(proof['versionCode'], tag)
    assets = exact_assets(current, proof, complete=False) if current else {}
    if APK not in assets:
        signed = dist / APK
        require(signed.is_file() and 0 < signed.stat().st_size <= PUBLIC_APK_LIMIT
                and hashlib.sha256(signed.read_bytes()).hexdigest() == proof['apk_sha256'],
                'Recovery needs the exact original signed APK')
    if CHECKSUM not in assets:
        (dist / CHECKSUM).write_bytes(checksum_bytes(proof['apk_sha256']))
    if not current:
        notes = dist / NOTES
        require(notes.is_file() and proof.get('notes_sha256') ==
                hashlib.sha256(notes.read_text(encoding='utf-8').strip().encode('utf-8')).hexdigest(),
                'Draft creation needs the exact original release notes')
    require_current_controller(controller)
    verify_tag(tag, source, create=True)
    if not current:
        run('gh', 'release', 'create', tag, '--repo', REPO, '--draft', '--verify-tag', '--target', source,
            '--title', release_title(tag), '--notes-file', str(notes))
        current = release_for_tag(tag)
        require(current is not None and current['draft'] is True, 'Expected a draft before uploading APK assets')
        assets = exact_assets(current, proof, complete=False)
    # A failed GitHub upload can leave an empty placeholder in a draft.
    # The placeholder carries no release bytes.
    for asset in current['assets']:
        if asset['name'] not in assets:
            require(type(asset['id']) is int and asset['id'] > 0, 'Unexpected draft asset ID')
            run('gh', 'api', '-X', 'DELETE', f'repos/{REPO}/releases/assets/{asset["id"]}')
    for name in (APK, CHECKSUM):
        if name not in assets:
            run('gh', 'release', 'upload', tag, str(dist / name), '--repo', REPO)

    final = release_for_tag(tag)
    require(final is not None and final['id'] == current['id'] and final['draft'] is True,
            'Draft identity changed during publication')
    exact_assets(final, proof, complete=True)
    require(verify_tag(tag, source) is not None, 'Release tag disappeared before publication')
    require_current_controller(controller)
    require_publication_baseline(proof)
    result = release_api(f'releases/{final["id"]}', method='PATCH', data={'draft': False, 'make_latest': 'true'})
    require(result['id'] == final['id'] and result['draft'] is False, 'Expected the exact release to be public')
    exact_assets(result, proof, complete=True)
    print('Published signed release ' + tag + ' SHA256 ' + proof['apk_sha256'])


def publish_release():
    dist = ROOT / 'dist'
    proof = read_attestation(dist / 'release-attestation.json', os.environ['ATTESTATION_DIGEST'])
    expose_release(proof, dist, controller=proof['controller_sha'])


if __name__=='__main__':
    if os.environ.get('PUBLISH_SIGNED_RELEASE') == 'true':
        publish_release()
    else:
        main()
