"""Recover the exact attestation from a completed trusted publication."""
import json
import os
from pathlib import Path
import re
import subprocess

from publish import release_title
from verify_public_release import API, bounded_zip, digest, gh, require


PUBLISHERS = {
    '.github/workflows/build-release.yml': ({'workflow_dispatch'}, 'publish'),
    '.github/workflows/sync-upstream.yml': ({'workflow_dispatch', 'schedule', 'push'}, 'build / publish'),
}


def publisher_job(run, jobs, run_id, repository):
    definition = PUBLISHERS.get(run.get('path'))
    require(definition is not None and run['id'] == run_id
            and run['event'] in definition[0] and run['head_branch'] == 'main'
            and run['head_repository']['full_name'] == repository
            and run['status'] == 'completed'
            and re.fullmatch(r'[0-9a-f]{40}', run['head_sha']),
            'Expected a completed trusted main publication')
    publishers = [job for job in jobs if job['name'] == definition[1]
                  and job['run_id'] == run_id and job['head_sha'] == run['head_sha']]
    require(len(publishers) == 1 and publishers[0]['status'] == 'completed'
            and publishers[0]['conclusion'] in ('success', 'failure', 'cancelled'),
            'Expected the exact original publisher job')
    return publishers[0]


def resolve(run_id):
    require(re.fullmatch(r'[1-9][0-9]*', run_id), 'Invalid signing run ID')
    run = gh(f'actions/runs/{run_id}')
    jobs = gh(f'actions/runs/{run_id}/attempts/{run["run_attempt"]}/jobs?per_page=100')['jobs']
    publisher_job(run, jobs, int(run_id), os.environ['GITHUB_REPOSITORY'])
    pages = gh(f'actions/runs/{run_id}/artifacts?per_page=100', '--paginate', '--slurp')
    name = f'release-attestation-{run["run_attempt"]}'
    assets = [item for page in pages for item in page['artifacts'] if item['name'] == name]
    require(len(assets) == 1 and not assets[0]['expired'], 'Expected one live publisher attestation')
    artifact = assets[0]
    require(artifact['workflow_run']['id'] == int(run_id)
            and artifact['workflow_run']['head_sha'] == run['head_sha']
            and 0 < artifact['size_in_bytes'] <= 300 * 1024, 'Attestation producer or size mismatch')
    raw = subprocess.check_output(['gh', 'api', f'{API}/actions/artifacts/{artifact["id"]}/zip'], timeout=45)
    require(len(raw) == artifact['size_in_bytes'] and 'sha256:' + digest(raw) == artifact['digest'],
            'Publisher archive differs from GitHub digest')
    with bounded_zip(raw, 256 * 1024) as archive:
        require(archive.namelist() == ['release-attestation.json'], 'Unexpected publisher archive entries')
        data = archive.read('release-attestation.json')
    attestation = json.loads(data)
    require(attestation['controller_sha'] == run['head_sha'], 'Attestation controller differs from the signing run')
    if run['event'] == 'push':
        require(attestation.get('maintenance', {}).get('kind') == 'security',
                'Security review push can only attest a security release')
    tag = attestation['release_tag']
    release_title(tag)
    # Even if the publish job ended during a timeout or cancellation, recovery
    # can use the original attestation only when the complete release is live.
    live = gh(f'releases/tags/{tag}')
    assets = {asset['name']: asset for asset in live['assets']}
    require(live['tag_name'] == tag and live['draft'] is False and live['prerelease'] is False
            and len(live['assets']) == 2 and set(assets) == {'zec-private-latest.apk', 'SHA256SUMS'}
            and assets['zec-private-latest.apk']['digest'] == 'sha256:' + attestation['apk_sha256'],
            'Complete matching publication required; recovery never changes public assets')
    return dict(release_tag=tag, attestation_id=artifact['id'],
                attestation_digest=digest(data), controller_sha=run['head_sha'])


def main():
    proof = resolve(os.environ['RECONCILIATION_RUN'])
    with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8') as output:
        output.writelines(f'{key}={value}\n' for key, value in proof.items())


if __name__ == '__main__':
    main()
