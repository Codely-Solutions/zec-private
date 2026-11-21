"""Bind hosted acceptance to the exact artifacts emitted by the release run."""
import hashlib
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
    require(re.fullmatch(r'[1-9][0-9]*', str(artifact_id)), 'Exact artifact ID required')
    value = metadata.api('/actions/artifacts/' + str(artifact_id))
    producer = value['workflow_run']
    require(value['id'] == int(artifact_id) and value['name'] == name and value['expired'] is False
            and producer['id'] == int(run) and producer['head_sha'] == controller
            and 0 < value['size_in_bytes'] <= ARCHIVE_LIMITS[name][0]
            and re.fullmatch(r'sha256:[a-f0-9]{64}', value.get('digest', '')),
            'Artifact does not belong to the exact trusted run and role')
    return {key: value[key] for key in ('id', 'digest', 'size_in_bytes')}


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


def read_receipts(directory, *, proof, source, controller, production_id, core_id, quote_id,
                  metadata=None, recovery=None, direct_core_id='', reuse_funded=False):
    metadata = metadata or Metadata()
    run = os.environ['GITHUB_RUN_ID']
    build_run = str(recovery['run']) if recovery else run
    build_controller = recovery['original_controller'] if recovery else controller
    quote_run = recovery.get('quote_run', int(run)) if recovery else int(run)
    quote_controller = recovery.get('quote_controller', controller) if recovery else controller
    missing_core = recovery and recovery.get('run_missing_core') == 'true'
    core_run = run if missing_core else build_run
    core_controller = controller if missing_core else build_controller
    production = artifact(metadata, production_id, 'validated-unsigned-apk', build_run, build_controller)
    version = version_code(ROOT, source)
    quote = json.loads((directory / 'hosted-quotes.json').read_text())
    if reuse_funded:
        from funded_reuse import select
        from hosted_core import validate_upgrade

        selected = select(source, controller, metadata, root=ROOT)
        require(selected is not None, 'Candidate does not qualify for reuse of funded evidence')
        require(quote.get('build_source_sha') == source and quote.get('candidate_version_code') == version
                and quote.get('production_artifact') == production and quote.get('workflow_run_id') == quote_run
                and quote.get('quote_controller_sha') == quote_controller and quote.get('artifact_run_id') == int(build_run)
                and str(proof['workflow_run_id']) == build_run and proof['versionCode'] == version,
                'Quote acceptance is not bound to this production candidate and trusted run')
        quote_producer = artifact(metadata, quote_id, 'isolated-ui-repro-apk', build_run, build_controller)
        require(quote.get('producer') == quote_producer, 'Quote receipt names a different test APK producer')
        validate_quotes(quote, input_fingerprint(source, profile='production-quotes'), use_tor=False)
        original_core = artifact(metadata, core_id, 'core-testnet-candidate', build_run, build_controller)
        prepared_id = recovery.get('prepared_id') if recovery else None
        if prepared_id:
            require(str(direct_core_id) == str(prepared_id), 'Recovery changed the prepared test APK artifact')
        producer_run, producer_controller = (build_run, build_controller) if prepared_id else (run, controller)
        producer = artifact(metadata, direct_core_id, 'core-direct-candidate', producer_run, producer_controller)
        upgrade = json.loads((directory / 'hosted-upgrade.json').read_text())
        validate_upgrade(upgrade, source=source, version=version, production=production, producer=producer,
                         original_core=original_core, fixture=selected['fixture'],
                         run=int(core_run if recovery and recovery.get('core_receipt_id') else run),
                         controller=core_controller if recovery and recovery.get('core_receipt_id') else controller,
                         producer_run=int(producer_run), producer_controller=producer_controller)
        quote['producer_controller_sha'] = build_controller
        return selected['core'], quote, {'funded_reuse': selected['origin'], 'installed_upgrade': upgrade}

    fixture = json.loads((ROOT / '.private/hosted-fixture.json').read_text())
    core = json.loads((directory / 'hosted-core.json').read_text())
    # A verified prior Tor wallet receipt can only be reused during recovery.
    use_tor = bool(recovery and not missing_core and core.get('tor_enabled', True) is True)
    original_core = artifact(metadata, core_id, 'core-testnet-candidate', build_run, build_controller)
    prepared = None
    if use_tor:
        require(not direct_core_id, 'Tor acceptance cannot use a direct wallet artifact')
        core_producer = original_core
        core_artifact_run, core_build_controller = build_run, build_controller
    else:
        core_artifact_run, core_build_controller = str(core_run), core_controller
        if recovery and not missing_core:
            direct_core_id = core['producer']['id']
        core_producer = artifact(metadata, direct_core_id, 'core-direct-candidate', core_artifact_run, core_build_controller)
        prepared = core['direct_preparation']
        require(prepared['source'] == source and prepared['harness_controller'] == core_controller
                and prepared['original_core'] == original_core and prepared['baseline_source'] == fixture['source_sha']
                and prepared['baseline_apk_sha256'] == fixture['candidate_apk_sha256']
                and prepared['candidate_apk_sha256'] == core['candidate_apk_sha256']
                and prepared['candidate_instrumentation_sha256'] == core['instrumentation_apk_sha256'],
                'Direct acceptance differs from its exact APK and instrumentation preparation')
    require(all(receipt.get('build_source_sha') == source and receipt.get('candidate_version_code') == version
                and receipt.get('production_artifact') == production and receipt.get('workflow_run_id') == int(receipt_run)
                for receipt, receipt_run in ((core, core_run), (quote, quote_run))) and str(proof['workflow_run_id']) == build_run
            and proof['versionCode'] == version, 'Acceptance is not bound to this production candidate and run')
    if recovery:
        require(quote.get('quote_controller_sha') == quote_controller
                and quote.get('artifact_run_id') == int(build_run), 'Quote recovery used different execution inputs')
    if missing_core:
        require(core.get('artifact_run_id') == int(core_artifact_run), 'Wallet recovery used a different artifact run')
    require(core.get('rehearsal') is False and core['funding_setup']['claim_requests'] == 1,
            'Hosted release requires exactly one funded testnet claim')
    previous = recovery.get('previous_reservation') if missing_core else None
    verify(binding(source, core_controller, version, int(core_run), False, previous), core['hosted_reservation'], metadata)
    require(core['producer'] == core_producer
            and quote['producer'] == artifact(metadata, quote_id, 'isolated-ui-repro-apk', build_run, build_controller),
            'Hosted receipt names a different test APK producer')
    validate_core_sources(core, source, baseline=fixture, use_tor=use_tor, prepared=prepared)
    validate_quotes(quote, input_fingerprint(source, profile='production-quotes'), use_tor=use_tor)
    core.update(artifact_id=core_producer['id'], producer_controller_sha=core_build_controller)
    quote['producer_controller_sha'] = build_controller
    return core, quote


def admit(source, controller, metadata, *, preflight_only=False):
    require(source != controller, 'Hosted automatic releases require a prepared child candidate')
    verify_promotion_revision(source, controller)
    from maintenance_cadence import admit_cadence
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
