"""Bind hosted acceptance to the exact artifacts emitted by the release run."""
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

from acceptance_policy import ROOT, input_fingerprint, validate_core_sources, validate_quotes, verify_promotion_revision
from hosted_claim import binding, verify
from release_inputs import version_code
from release_metadata import ARCHIVE_LIMITS, Metadata, require


def artifact(metadata, artifact_id, name, run, controller):
    value = artifact_record(metadata, artifact_id, name, run, controller)
    return {key: value[key] for key in ('id', 'digest', 'size_in_bytes')}


def artifact_record(metadata, artifact_id, name, run, controller):
    require(re.fullmatch(r'[1-9][0-9]*', str(artifact_id)), 'Exact artifact ID required')
    value = metadata.api('/actions/artifacts/' + str(artifact_id))
    producer = value['workflow_run']
    require(value['id'] == int(artifact_id) and value['name'] == name and value['expired'] is False
            and producer['id'] == int(run) and producer['head_sha'] == controller
            and 0 < value['size_in_bytes'] <= ARCHIVE_LIMITS[name][0]
            and re.fullmatch(r'sha256:[a-f0-9]{64}', value.get('digest', '')),
            'Artifact does not belong to the exact trusted run and role')
    return value


def stage(directory, artifact_id, name, *, metadata=None, run=None, controller=None):
    metadata = metadata or Metadata()
    value = artifact(metadata, artifact_id, name, run or os.environ['GITHUB_RUN_ID'],
                     controller or os.environ['GITHUB_SHA'])
    directory.mkdir(mode=0o700)
    path = directory / 'download.zip'
    with path.open('xb') as output:
        subprocess.run([metadata.gh, 'api', metadata.repo + '/actions/artifacts/' + str(artifact_id) + '/zip'],
                       stdout=output, timeout=120, check=True)
    with path.open('rb') as stream:
        actual = 'sha256:' + hashlib.file_digest(stream, 'sha256').hexdigest()
    require(path.stat().st_size == value['size_in_bytes'] and actual == value['digest'],
            'Downloaded archive bytes differ from the GitHub artifact')
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        extra = {'direct-preparation.json', 'baseline-instrumentation.bin'} if name == 'core-direct-candidate' else set()
        require(len(names) == len(set(names)) <= 8 and
                all(n in extra or re.fullmatch(r'[A-Za-z0-9_.-]+\.apk|source-sha\.txt|SHA256SUMS', n) for n in names)
                and sum(item.file_size for item in archive.infolist()) <= ARCHIVE_LIMITS[name][1],
                'Unexpected or excessive APK archive entries')
        archive.extractall(directory)
    path.unlink()
    (directory / 'producer.json').write_text(json.dumps(value))
    return value


RECEIPT_FILES = {
    'hosted-core-receipt': 'hosted-core.json',
    'hosted-upgrade-receipt': 'hosted-upgrade.json',
    'hosted-quote-receipt': 'hosted-quotes.json',
}


def receipt_bytes(metadata, artifact_id, name, run, controller):
    value = artifact(metadata, artifact_id, name, run, controller)
    archive = subprocess.check_output([metadata.gh, 'api', metadata.repo +
                                       f'/actions/artifacts/{value["id"]}/zip'], timeout=30)
    require(len(archive) == value['size_in_bytes'] and 'sha256:' + hashlib.sha256(archive).hexdigest() ==
            value['digest'], 'Hosted receipt archive differs from its verified artifact')
    filename = RECEIPT_FILES[name]
    with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
        require(bundle.namelist() == [filename] and bundle.getinfo(filename).file_size <= ARCHIVE_LIMITS[name][1],
                'Unexpected hosted receipt archive')
        return bundle.read(filename)


def read_receipts(directory, *, proof, source, controller, production_id, core_id, quote_id,
                  metadata=None, recovery=None, direct_core_id='', reuse_funded=False):
    metadata = metadata or Metadata()
    run = os.environ['GITHUB_RUN_ID']
    recovery = recovery or {}
    build_run = str(recovery.get('run', run))
    build_controller = recovery.get('original_controller', controller)
    quote_run = recovery.get('quote_run', int(run))
    quote_controller = recovery.get('quote_controller', controller)
    core_run = str(recovery.get('core_run', run if not recovery.get('core_receipt_id') else build_run))
    core_controller = recovery.get('core_controller', controller if core_run == run else build_controller)
    production = artifact(metadata, production_id, 'validated-unsigned-apk', build_run, build_controller)
    version = version_code(ROOT, source)
    require(str(proof['workflow_run_id']) == build_run and proof['versionCode'] == version,
            'Acceptance is not bound to this production candidate and run')
    quote = load_receipt(directory, 'hosted-quote-receipt', recovery, 'quote')
    validate_receipt_identity(quote, source, version, production, quote_run)
    if recovery or reuse_funded:
        require(quote.get('quote_controller_sha') == quote_controller and quote.get('artifact_run_id') == int(build_run),
                'Quote recovery used different execution inputs')
    quote_producer = artifact(metadata, quote_id, 'isolated-ui-repro-apk', build_run, build_controller)
    require(quote.get('producer') == quote_producer, 'Quote receipt names a different test APK producer')
    original_core = artifact(metadata, core_id, 'core-testnet-candidate', build_run, build_controller)
    quote['producer_controller_sha'] = build_controller
    if reuse_funded:
        from funded_reuse import select
        from hosted_core import validate_upgrade

        selected = select(source, controller, metadata, root=ROOT)
        require(selected is not None, 'Candidate does not qualify for reuse of funded evidence')
        validate_quotes(quote, input_fingerprint(source, profile='production-quotes'), use_tor=False)
        prepared_id = recovery.get('prepared_id')
        if prepared_id:
            require(str(direct_core_id) == str(prepared_id), 'Recovery changed the prepared test APK artifact')
        producer_run = str(recovery.get('prepared_run', build_run if prepared_id else run))
        producer_controller = recovery.get('prepared_controller', build_controller if prepared_id else controller)
        producer = artifact(metadata, direct_core_id, 'core-direct-candidate', producer_run, producer_controller)
        upgrade = load_receipt(directory, 'hosted-upgrade-receipt', recovery, 'core')
        validate_upgrade(upgrade, source=source, version=version, production=production, producer=producer,
                         original_core=original_core, fixture=selected['fixture'],
                         run=int(core_run), controller=core_controller,
                         producer_run=int(producer_run), producer_controller=producer_controller)
        return selected['core'], quote, {'funded_reuse': selected['origin'], 'installed_upgrade': upgrade}

    fixture = json.loads((ROOT / '.private/hosted-fixture.json').read_text())
    core = load_receipt(directory, 'hosted-core-receipt', recovery, 'core')
    # A verified prior Tor wallet receipt can only be reused during recovery.
    use_tor = bool(recovery and recovery.get('run_missing_core') != 'true' and core.get('tor_enabled', True) is True)
    if recovery.get('core_receipt_id') and not use_tor:
        if recovery.get('prepared_id'):
            require(str(direct_core_id) == recovery['prepared_id'], 'Recovery changed the prepared test APK artifact')
        direct_core_id = str(recovery.get('prepared_id', core['producer']['id']))
    validate_wallet(core, source=source, version=version, production=production, original_core=original_core,
                    build_run=build_run, build_controller=build_controller, core_run=core_run,
                    core_controller=core_controller, fixture=fixture, metadata=metadata,
                    direct_core_id=direct_core_id, use_tor=use_tor, root=ROOT, recovery=recovery)
    validate_quotes(quote, input_fingerprint(source, profile='production-quotes'), use_tor=use_tor)
    return core, quote


def load_receipt(directory, name, recovery, stage):
    data = (directory / RECEIPT_FILES[name]).read_bytes()
    if stage + '_receipt_id' in recovery:
        require(hashlib.sha256(data).hexdigest() == recovery.get(stage + '_receipt_sha256'),
                'Downloaded receipt differs from the exact selected artifact')
    return json.loads(data)


def validate_receipt_identity(receipt, source, version, production, run):
    require(receipt.get('build_source_sha') == source and receipt.get('candidate_version_code') == version
            and receipt.get('production_artifact') == production and receipt.get('workflow_run_id') == int(run),
            'Acceptance is not bound to this production candidate and trusted run')


def validate_wallet(core, *, source, version, production, original_core, build_run, build_controller, core_run,
                    core_controller, fixture, metadata, direct_core_id, use_tor, root, recovery):
    validate_receipt_identity(core, source, version, production, core_run)
    prepared = None
    if use_tor:
        require(not direct_core_id, 'Tor acceptance cannot use a direct wallet artifact')
        core_producer = original_core
        core_artifact_run, core_build_controller = build_run, build_controller
    else:
        core_artifact_run, core_build_controller = str(core_run), core_controller
        core_producer = artifact(metadata, direct_core_id, 'core-direct-candidate', core_artifact_run, core_build_controller)
        prepared = core['direct_preparation']
        require(prepared['source'] == source and prepared['harness_controller'] == core_controller
                and prepared['original_core'] == original_core and prepared['baseline_source'] == fixture['source_sha']
                and prepared['baseline_apk_sha256'] == fixture['candidate_apk_sha256']
                and prepared['candidate_apk_sha256'] == core['candidate_apk_sha256']
                and prepared['candidate_instrumentation_sha256'] == core['instrumentation_apk_sha256'],
                'Direct acceptance differs from its exact APK and instrumentation preparation')
    if recovery:
        require(core.get('artifact_run_id') == int(core_artifact_run), 'Wallet recovery used a different artifact run')
    claims = core['funding_setup']['claim_requests']
    require(core.get('rehearsal') is False and type(claims) is int and claims == 1,
            'Hosted release requires exactly one funded testnet claim')
    previous = recovery.get('previous_reservation')
    verify(binding(source, core_controller, version, int(core_run), False, previous), core['hosted_reservation'], metadata)
    require(core['producer'] == core_producer, 'Hosted receipt names a different test APK producer')
    validate_core_sources(core, source, root=root, baseline=fixture, use_tor=use_tor, prepared=prepared)
    core.update(artifact_id=core_producer['id'], producer_controller_sha=core_build_controller)


def recovered_reservation(core, source, core_run, core_controller, version, metadata):
    """Recover the prior link from protected refs for a wallet that already passed."""
    from hosted_claim import ref_for

    current = binding(source, core_controller, version, int(core_run), False)
    ref = ref_for(current)
    rows = metadata.api('/git/matching-refs/' + ref.removeprefix('refs/'))
    primary = [row['object']['sha'] for row in rows if row['ref'] == ref]
    linked = [row['object']['sha'] for row in rows if row['ref'] == ref + '-preclaim-recovery']
    require(len(primary) == 1 and len(linked) <= 1, 'Expected exact protected funding reservations')
    if not linked:
        require(core['hosted_reservation'] == primary[0], 'Wallet receipt names a different reservation')
        return None
    require(core['hosted_reservation'] == linked[0] and primary != linked,
            'Wallet receipt names a different linked reservation')
    prior = json.loads(metadata.api('/git/commits/' + primary[0])['message'])
    expected = binding(source, prior['controller'], version, prior['run'], False)
    require(prior['run'] != int(core_run), 'Linked wallet must follow a separate pre-claim run')
    verify(expected, primary[0], metadata)
    return primary[0]


def selected_receipt(outputs, stage, name, run, controller, metadata):
    data = receipt_bytes(metadata, outputs[stage + '_receipt_id'], name, run, controller)
    outputs[stage + '_receipt_sha256'] = hashlib.sha256(data).hexdigest()
    return json.loads(data)


def verify_recovered_receipts(root, source, controller, run, outputs, metadata, *,
                              original_prepared, original_reserve):
    """Validate retained evidence before scheduling any fresh acceptance."""
    if 'core_receipt_id' not in outputs and 'quote_receipt_id' not in outputs:
        return
    original = outputs['original_controller']
    production = artifact(metadata, outputs['production_id'], 'validated-unsigned-apk', run, original)
    version = version_code(root, source)
    use_tor = False
    if 'core_receipt_id' in outputs:
        core_run = str(outputs.get('core_run', run))
        core_controller = outputs.get('core_controller', original)
        original_core = artifact(metadata, outputs['core_id'], 'core-testnet-candidate', run, original)
        if outputs.get('reuse_funded') == 'true':
            from funded_reuse import select
            from hosted_core import validate_upgrade

            upgrade = selected_receipt(outputs, 'core', 'hosted-upgrade-receipt', core_run, core_controller, metadata)
            selected = select(source, controller, metadata, root=root)
            require(selected is not None, 'Passing installed upgrade requires matching funded provenance')
            prepared_run = str(outputs.get('prepared_run', run))
            prepared_controller = outputs.get('prepared_controller', original)
            producer = artifact(metadata, outputs['prepared_id'], 'core-direct-candidate', prepared_run,
                                prepared_controller)
            validate_upgrade(upgrade, source=source, version=version, production=production, producer=producer,
                             original_core=original_core, fixture=selected['fixture'], run=int(core_run),
                             controller=core_controller, producer_run=int(prepared_run),
                             producer_controller=prepared_controller)
        else:
            core = selected_receipt(outputs, 'core', 'hosted-core-receipt', core_run, core_controller, metadata)
            use_tor = core.get('tor_enabled', True) is True
            fixture = json.loads((root / '.private/hosted-fixture.json').read_text())
            if core_run != run:
                require(not use_tor, 'Wallet recovery requires direct app networking')
                previous = recovered_reservation(core, source, core_run, core_controller, version, metadata)
                if previous:
                    outputs['previous_reservation'] = previous
            else:
                require(original_reserve == 'success' and (use_tor or original_prepared == 'success'),
                        'Original wallet requires a passing reservation and applicable preparation job')
            require(use_tor or outputs.get('prepared_id'), 'Direct wallet requires its verified preparation archive')
            direct_core_id = '' if use_tor else outputs['prepared_id']
            validate_wallet(core, source=source, version=version, production=production, original_core=original_core,
                            build_run=run, build_controller=original, core_run=core_run,
                            core_controller=core_controller, fixture=fixture, metadata=metadata,
                            direct_core_id=direct_core_id, use_tor=use_tor, root=root, recovery=outputs)
    if 'quote_receipt_id' in outputs:
        quote_run, quote_controller = outputs['quote_run'], outputs['quote_controller']
        quote = selected_receipt(outputs, 'quote', 'hosted-quote-receipt', quote_run, quote_controller, metadata)
        validate_receipt_identity(quote, source, version, production, quote_run)
        require(quote.get('quote_controller_sha') == quote_controller and quote.get('artifact_run_id') == int(run)
                and quote.get('producer') == artifact(metadata, outputs['quote_id'], 'isolated-ui-repro-apk',
                                                      run, original),
                'Quote recovery used different execution inputs or APKs')
        validate_quotes(quote, input_fingerprint(source, root=root, profile='production-quotes'), use_tor=use_tor)


def admit(source, controller, metadata, *, preflight_only=False, custom_caller=False):
    require(source != controller, 'Hosted automatic releases require a prepared child candidate')
    from custom_release import MARKER as CUSTOM_MARKER
    from maintenance_cadence import admit_cadence, changed_marker
    require(not custom_caller or changed_marker(ROOT, source, controller, CUSTOM_MARKER),
            'A fresh custom release requires a prepared custom candidate')
    verify_promotion_revision(source, controller)
    from maintenance_watch import api
    from release_changelog import hosted_changes
    admit_cadence(ROOT, source, controller, metadata, api, preflight_only=preflight_only)
    hosted_changes(ROOT, source, controller)
    version = version_code(ROOT, source)
    baseline = json.loads((ROOT / '.private/hosted-fixture.json').read_text())['version_code']
    live = metadata.api('/releases/latest')['tag_name']
    selected = json.loads((ROOT / '.private/hosted-fixture.json').read_text())['release']
    require(live == selected and version > baseline, 'Latest public release needs its verified hosted baseline')
    from hosted_claim import ref_for
    ref = ref_for(binding(source, controller, version, int(os.environ['GITHUB_RUN_ID']), False))
    refs = metadata.api('/git/matching-refs/' + ref.removeprefix('refs/'))
    require(not any(item['ref'] == ref for item in refs),
            'Funding already reserved for this version. Recover the original run without rebuilding or claiming again.')
