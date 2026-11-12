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
from release_metadata import Metadata, require

MAX_ARCHIVE = 160 * 1024**2


def artifact(metadata, artifact_id, name, run, controller):
    require(re.fullmatch(r'[1-9][0-9]*', str(artifact_id)), 'Exact artifact ID required')
    value = metadata.api('/actions/artifacts/' + str(artifact_id))
    producer = value['workflow_run']
    require(value['id'] == int(artifact_id) and value['name'] == name and value['expired'] is False
            and producer['id'] == int(run) and producer['head_sha'] == controller
            and 0 < value['size_in_bytes'] <= MAX_ARCHIVE
            and re.fullmatch(r'sha256:[a-f0-9]{64}', value.get('digest', '')),
            'Artifact does not belong to the exact trusted run and role')
    return {key: value[key] for key in ('id', 'digest', 'size_in_bytes')}


def stage(directory, artifact_id, name, *, metadata=None):
    metadata = metadata or Metadata()
    value = artifact(metadata, artifact_id, name, os.environ['GITHUB_RUN_ID'], os.environ['GITHUB_SHA'])
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
        require(len(names) == len(set(names)) <= 8 and
                all(re.fullmatch(r'[A-Za-z0-9_.-]+\.apk|source-sha\.txt|SHA256SUMS', n) for n in names)
                and sum(item.file_size for item in archive.infolist()) <= 192 * 1024**2,
                'Unexpected or excessive APK archive entries')
        archive.extractall(directory)
    path.unlink()
    (directory / 'producer.json').write_text(json.dumps(value))
    return value


def read_receipts(directory, *, proof, source, controller, production_id, core_id, quote_id, metadata=None):
    metadata = metadata or Metadata()
    run = os.environ['GITHUB_RUN_ID']
    production = artifact(metadata, production_id, 'validated-unsigned-apk', run, controller)
    version = version_code(ROOT, source)
    fixture = json.loads((ROOT / '.private/hosted-fixture.json').read_text())
    core = json.loads((directory / 'hosted-core.json').read_text())
    quote = json.loads((directory / 'hosted-quotes.json').read_text())
    require(all(receipt.get('build_source_sha') == source and receipt.get('candidate_version_code') == version
                and receipt.get('production_artifact') == production and receipt.get('workflow_run_id') == int(run)
                for receipt in (core, quote)) and str(proof['workflow_run_id']) == run
            and proof['versionCode'] == version, 'Acceptance is not bound to this production candidate and run')
    require(core.get('rehearsal') is False and core['funding_setup']['claim_requests'] == 1,
            'Hosted release requires exactly one funded testnet claim')
    verify(binding(source, controller, version, int(run), False), core['hosted_reservation'], metadata)
    require(core['producer'] == artifact(metadata, core_id, 'core-testnet-candidate', run, controller)
            and quote['producer'] == artifact(metadata, quote_id, 'isolated-ui-repro-apk', run, controller),
            'Hosted receipt names a different test APK producer')
    validate_core_sources(core, source, baseline=fixture)
    validate_quotes(quote, input_fingerprint(source, profile='production-quotes'))
    core.update(artifact_id=int(core_id), producer_controller_sha=controller)
    return core, quote


def admit(source, controller, metadata):
    require(source != controller, 'Hosted automatic releases require a prepared child candidate')
    verify_promotion_revision(source, controller)
    from maintenance_cadence import admit_cadence
    from maintenance_watch import api
    admit_cadence(ROOT, source, controller, metadata, api)
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
