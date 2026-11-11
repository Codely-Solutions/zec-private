"""Recover the exact attestation from a completed trusted publication."""
import json
import os
from pathlib import Path
import re
import subprocess

from publish import release_title
from verify_public_release import API, bounded_zip, digest, gh, require


def main():
    run_id = os.environ['RECONCILIATION_RUN']
    require(re.fullmatch(r'[1-9][0-9]*', run_id), 'Invalid signing run ID')
    run = gh(f'actions/runs/{run_id}')
    require(run['path'] == '.github/workflows/build-release.yml'
            and run['event'] == 'workflow_dispatch' and run['head_branch'] == 'main'
            and run['head_repository']['full_name'] == os.environ['GITHUB_REPOSITORY']
            and run['status'] == 'completed'
            and re.fullmatch(r'[0-9a-f]{40}', run['head_sha']),
            'Expected a completed trusted main publication')
    jobs = gh(f'actions/runs/{run_id}/attempts/{run["run_attempt"]}/jobs?per_page=100')['jobs']
    require(sum(job['name'] == 'publish' and job['conclusion'] == 'success' for job in jobs) == 1,
            'The original publisher must have succeeded')
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
    tag = attestation['release_tag']
    release_title(tag)
    with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8') as output:
        output.write(f'release_tag={tag}\nattestation_id={artifact["id"]}\n'
                     f'attestation_digest={digest(data)}\ncontroller_sha={run["head_sha"]}\n')


if __name__ == '__main__':
    main()
