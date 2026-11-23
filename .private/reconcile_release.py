"""Resolve the exact original publisher and its retained release bytes."""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

from custom_release import OPERATOR_ID, REPOSITORY, WORKFLOW as CUSTOM_WORKFLOW
from publish import (APK, NOTES, exact_assets, expose_release, release_for_tag, release_title,
                     verify_attestation_publisher, verify_tag)
from release_metadata import ARCHIVE_LIMITS, artifact_producer_job, job_timestamp
from verify_public_release import API, bounded_zip, digest, gh, require, unsigned_proof


PUBLISHERS = {
    '.github/workflows/build-release.yml': ({'workflow_dispatch'}, 'publish'),
    '.github/workflows/sync-upstream.yml': ({'workflow_dispatch', 'schedule', 'push'}, 'build / publish'),
    CUSTOM_WORKFLOW: ({'workflow_dispatch'}, 'build / publish'),
}
PUBLISHER_CONCLUSIONS = ('success', 'failure', 'cancelled')


def publisher_job(run, jobs, run_id, repository, *, attempt=None):
    definition = PUBLISHERS.get(run.get('path'))
    require(definition is not None and run['id'] == run_id
            and run['event'] in definition[0] and run['head_branch'] == 'main'
            and run['head_repository']['full_name'] == repository
            and run['status'] == 'completed'
            and (attempt is None or run['run_attempt'] == attempt)
            and re.fullmatch(r'[0-9a-f]{40}', run['head_sha']),
            'Expected a completed trusted main publication')
    publishers = [job for job in jobs if job['name'] == definition[1]]
    require(len(publishers) == 1 and publishers[0]['status'] == 'completed'
            and publishers[0]['run_id'] == run_id and publishers[0]['head_sha'] == run['head_sha']
            and (attempt is None or publishers[0].get('run_attempt') == attempt)
            and publishers[0]['conclusion'] in PUBLISHER_CONCLUSIONS,
            'Expected the exact original publisher job')
    return publishers[0]


def verify_attestation_upload(job, artifact):
    def successful_step(pattern):
        matches = [step for step in job.get('steps', []) if re.fullmatch(pattern, step['name'])]
        require(len(matches) == 1 and matches[0]['status'] == 'completed'
                and matches[0]['conclusion'] == 'success', 'Expected successful publisher signing and attestation upload')
        return matches[0]

    signing = successful_step('Sign and verify the validated APK')
    upload = successful_step(r'Run actions/upload-artifact@\S+')
    require(job_timestamp(job['started_at']) <= job_timestamp(signing.get('started_at'))
            <= job_timestamp(signing.get('completed_at')) <= job_timestamp(upload.get('started_at'))
            <= job_timestamp(artifact.get('created_at')) <= job_timestamp(upload.get('completed_at'))
            <= job_timestamp(job['completed_at']) and signing['number'] < upload['number'],
            'Attestation was not created during the original publisher upload step')


def archive_bytes(artifact, run, role):
    compressed, _ = ARCHIVE_LIMITS[role]
    require(artifact['expired'] is False and artifact['workflow_run']['id'] == run['id']
            and artifact['workflow_run']['head_sha'] == run['head_sha']
            and 0 < artifact['size_in_bytes'] <= compressed, 'Publisher artifact producer or size mismatch')
    raw = subprocess.check_output(['gh', 'api', f'{API}/actions/artifacts/{artifact["id"]}/zip'], timeout=90)
    require(len(raw) == artifact['size_in_bytes'] and 'sha256:' + digest(raw) == artifact['digest'],
            'Publisher archive differs from GitHub digest')
    return raw


def lookup(run_id, attempt, artifact_id, *, allow_draft=False, target=None):
    require(re.fullmatch(r'[1-9][0-9]*', run_id), 'Invalid signing run ID')
    require(not attempt or re.fullmatch(r'[1-9][0-9]*', attempt), 'Invalid publisher attempt')
    require(not artifact_id or re.fullmatch(r'[1-9][0-9]*', artifact_id), 'Invalid attestation artifact ID')
    require(bool(attempt) == bool(artifact_id), 'Specify both the publisher attempt and the attestation artifact ID')
    require(not allow_draft or bool(attempt), 'Draft recovery requires an exact attempt and artifact ID')
    pages = gh(f'actions/runs/{run_id}/artifacts?per_page=100', '--paginate', '--slurp')
    artifacts = [item for page in pages for item in page['artifacts']]
    attestations = [item for item in artifacts if item['expired'] is False
                    and re.fullmatch(r'release-attestation-[1-9][0-9]*', item['name'])]
    if artifact_id:
        attestations = [row for row in attestations if row['id'] == int(artifact_id)
                        and row['name'] == f'release-attestation-{attempt}']
        require(len(attestations) == 1, 'Expected the exact live publisher attestation')
    matches = []
    for item in attestations:
        try:
            matches.append(verify_attempt(run_id, item, artifacts, allow_draft=allow_draft, target=target))
        except (ValueError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
            if artifact_id:
                raise
    require(len(matches) == 1, 'Expected one live publisher attestation matching the release')
    return matches[0]


def verify_attempt(run_id, artifact, artifacts, *, allow_draft, target):
    attempt = int(artifact['name'].removeprefix('release-attestation-'))
    require(sum(row['name'] == artifact['name'] and row['expired'] is False for row in artifacts) == 1,
            'Ambiguous attestation for the original publisher attempt')
    run = gh(f'actions/runs/{run_id}/attempts/{attempt}')
    pages = gh(f'actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100', '--paginate', '--slurp')
    job = publisher_job(run, [job for page in pages for job in page['jobs']], int(run_id),
                        os.environ['GITHUB_REPOSITORY'], attempt=attempt)
    producer = artifact_producer_job(run, [job], artifact,
                                     lambda number: gh(f'actions/runs/{run_id}/attempts/{number}'),
                                     role='attestation', conclusions=PUBLISHER_CONCLUSIONS)
    verify_attestation_upload(producer, artifact)
    raw = archive_bytes(artifact, run, 'release-attestation')
    with bounded_zip(raw, ARCHIVE_LIMITS['release-attestation'][1]) as archive:
        require(archive.namelist() == ['release-attestation.json'], 'Unexpected publisher archive entries')
        data = archive.read('release-attestation.json')
    attestation = json.loads(data)
    verify_attestation_publisher(attestation, run_id, attempt)
    if run['path'] == CUSTOM_WORKFLOW:
        maintenance = attestation.get('maintenance', {}).get('kind')
        require(maintenance == 'custom' or
                (maintenance in ('routine', 'security') and attestation.get('quote_recovery')),
                'Expected a custom release or an authorized maintenance recovery attestation')
    require(attestation['controller_sha'] == run['head_sha'], 'Attestation controller differs from the signing run')
    if run['event'] == 'push':
        require(attestation.get('maintenance', {}).get('kind') == 'security',
                'Security review push can only attest a security release')
    require(re.fullmatch(r'[a-f0-9]{40}', attestation['source_sha']), 'Attestation requires an exact source SHA')
    tag = attestation['release_tag']
    release_title(tag)
    require(target is None or target['tag_name'] == tag, 'Attestation names a different public release')
    if target is not None:
        release = target
    elif allow_draft:
        release = release_for_tag(tag)
    else:
        release = gh(f'releases/tags/{tag}')
    pending = release is None or release['draft'] is True
    require(not pending or allow_draft, 'Public reconciliation requires the complete live release')
    if release is not None:
        exact_assets(release, attestation, complete=not pending)
    if not pending:
        from published_history import release_tag_matches

        from publish import tag_object
        obj = tag_object(tag, gh)
        require(obj is not None and release_tag_matches(tag, attestation['source_sha'], attestation['apk_sha256'],
                                                        release['id'], obj, gh), 'Public tag differs from the attestation')
    output = dict(release_tag=tag, attestation_id=artifact['id'], attestation_digest=digest(data),
                  attestation_attempt=attempt, controller_sha=run['head_sha'])
    return output, attestation, release


def resolve(run_id, attempt='', artifact_id='', *, target=None):
    return lookup(run_id, attempt, artifact_id, target=target)[0]


def resume_draft(run_id, attempt, artifact_id):
    require(os.environ['GITHUB_REPOSITORY'] == REPOSITORY and os.environ['GITHUB_REF'] == 'refs/heads/main'
            and os.environ['GITHUB_EVENT_NAME'] == 'workflow_dispatch',
            'Draft recovery requires an authorized protected main dispatch')
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_bytes())
    require(event['sender']['id'] == OPERATOR_ID and event['inputs']['resume_draft'] == 'true'
            and event['inputs']['reconciliation_run'] == run_id
            and event['inputs']['reconciliation_attempt'] == attempt
            and event['inputs']['reconciliation_artifact'] == artifact_id,
            'Dispatch does not authorize this exact draft recovery')
    output, proof, draft = lookup(run_id, attempt, artifact_id, allow_draft=True)
    if draft and draft['draft'] is False:
        return output

    from integrate_release import integration_base
    from publish import require_current_controller

    controller = os.environ['GITHUB_SHA']
    require_current_controller(controller)
    root = Path(__file__).resolve().parents[1]
    subprocess.run(['git', 'fetch', '--no-tags', 'origin', proof['source_sha']], cwd=root, check=True, timeout=45)
    integration_base(proof['source_sha'], controller, proof['controller_sha'], root,
                     recovery=proof.get('quote_recovery'), funded=bool(proof.get('funded_reuse')))
    verify_tag(proof['release_tag'], proof['source_sha'])
    # The original attestation proves signing. Recheck the original unsigned
    # archive and both payloads before publishing its retained signed bytes.
    from verify_public_release import payload as apk_payload

    unsigned, frozen, _, _ = unsigned_proof(proof)
    require(frozen['release_tag'] == proof['release_tag'], 'Attestation names a different unsigned release')
    with tempfile.TemporaryDirectory(dir=os.environ['RUNNER_TEMP']) as scratch:
        dist = Path(scratch)
        assets = exact_assets(draft, proof, complete=False) if draft else {}
        if APK in assets:
            asset_id = assets[APK]['id']
            signed = subprocess.check_output(
                ['gh', 'api', f'{API}/releases/assets/{asset_id}', '-H', 'Accept: application/octet-stream'],
                timeout=90)
        else:
            from publish import sign_apk
            from release_metadata import Metadata
            from signing_wiring import environment_wiring

            environment_wiring(Metadata())
            require_current_controller(controller)
            subprocess.run(['bash', '.private/verify_signing_key.sh'], cwd=root, check=True, timeout=60)

            # Regenerate only the signing envelope, then require the checksum
            # of the initial publisher's exact signed bytes.
            original = dist / 'original-unsigned.apk'
            original.write_bytes(unsigned)
            sign_apk(original, dist / APK, Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0')
            signed = (dist / APK).read_bytes()
        if draft is None:
            require(isinstance(proof.get('release_notes'), str), 'Original release notes are unavailable')
            (dist / NOTES).write_text(proof['release_notes'], encoding='utf-8')
        require(digest(signed) == proof['apk_sha256'] and apk_payload(unsigned) == apk_payload(signed),
                'Retained signed APK differs from the original candidate or attestation')
        expose_release(proof, dist, controller=controller, existing=draft)
    return output


def main():
    args = (os.environ['RECONCILIATION_RUN'], os.environ.get('RECONCILIATION_ATTEMPT', ''),
            os.environ.get('RECONCILIATION_ARTIFACT', ''))
    proof = resume_draft(*args) if os.environ.get('RESUME_DRAFT') == 'true' else resolve(*args)
    with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8', newline='\n') as output:
        output.writelines(f'{key}={value}\n' for key, value in proof.items())


if __name__ == '__main__':
    main()
