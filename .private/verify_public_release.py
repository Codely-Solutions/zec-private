"""Verify the latest public wallet APK from the original unsigned artifact."""
import base64
from datetime import datetime
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import zipfile

from apk_architecture import check_apk
from published_history import release_tag_matches
from publish import CERT, REPO, read_attestation
from release_metadata import APK_PAYLOAD_LIMIT, ARCHIVE_LIMITS, MIB, PUBLIC_APK_LIMIT


APK = 'zec-private-latest.apk'
CHECKSUM = 'SHA256SUMS'
PUBLIC = f'https://github.com/{REPO}'
API = f'repos/{REPO}'


def require(value, message):
    if not value:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def gh(path, *options):
    return json.loads(subprocess.check_output(['gh', 'api', f'{API}/{path}', *options], timeout=30))


def public_read(url, maximum):
    with urlopen(Request(url, headers={'User-Agent': 'ZEC-Private-release-verifier'}), timeout=30) as response:
        data = response.read(maximum + 1)
    require(len(data) <= maximum, 'Public response exceeds verification budget')
    return data


def latest(tag):
    for attempt in range(3):
        try:
            value = json.loads(public_read(f'https://api.github.com/{API}/releases/latest', MIB))
            route = 'public-api'
        except HTTPError as error:
            if not (error.code == 429 or (error.code == 403 and error.headers.get('X-RateLimit-Remaining') == '0')):
                raise
            error.close()
            # Shared runner IPs can exhaust the public JSON quota. Verify the
            # unauthenticated latest redirect as well as the read-only API data.
            request = Request(f'{PUBLIC}/releases/latest', headers={'User-Agent': 'ZEC-Private-release-verifier'})
            with urlopen(request, timeout=30) as response:
                public_url = response.geturl()
            require(public_url == f'{PUBLIC}/releases/tag/{tag}', 'Public latest release redirect differs')
            value = gh('releases/latest')
            route = 'public-latest-redirect-and-read-only-api'
        if value.get('tag_name') == tag:
            return value, route
        if attempt < 2:
            time.sleep(5)
    raise ValueError('Public updater discovery has not reached the expected tag')


def published_asset(tag, asset, maximum):
    url = f'{PUBLIC}/releases/download/{tag}/{asset["name"]}'
    require(asset['browser_download_url'] == url and 0 < asset['size'] <= maximum,
            'Public asset URL or size differs from expected updater contract')
    data = public_read(url, asset['size'])
    require(len(data) == asset['size'] and asset['digest'] == 'sha256:' + digest(data),
            'Public asset digest or size mismatch')
    return data


def bounded_zip(data, maximum):
    archive = zipfile.ZipFile(io.BytesIO(data))
    try:
        require(len(archive.namelist()) == len(set(archive.namelist()))
                and sum(item.file_size for item in archive.infolist()) <= maximum,
                'Duplicate or oversized archive entries')
    except BaseException:
        archive.close()
        raise
    return archive


def payload(data):
    with bounded_zip(data, APK_PAYLOAD_LIMIT) as archive:
        return {item: digest(archive.read(item)) for item in archive.namelist()
                if not re.fullmatch(r'META-INF/[^/]+\.(RSA|DSA|EC|SF|MF)', item, re.I)}


def unsigned_producer_job(run, jobs, asset, expected_id=None):
    def timestamp(value):
        require(isinstance(value, str), 'Missing GitHub artifact or job timestamp')
        parsed = datetime.fromisoformat(value)
        require(parsed.tzinfo is not None, 'GitHub artifact or job timestamp must include timezone')
        return parsed

    created = timestamp(asset.get('created_at'))
    matches = []
    for job in jobs:
        started = timestamp(job['started_at']) if job.get('started_at') else None
        completed = timestamp(job['completed_at']) if job.get('completed_at') else None
        if started is not None and started <= created and (completed is None or created <= completed):
            matches.append((job, started, completed))
    ids = [job.get('id') for job, _, _ in matches]
    require(all(type(job_id) is int and job_id > 0 for job_id in ids) and len(ids) == len(set(ids)),
            'Expected one record per matching unsigned producer job')

    current = run['run_attempt']
    attempts = {current: run}
    producers, copies = [], []
    for job, started, completed in matches:
        number = job.get('run_attempt')
        require(type(number) is int and 1 <= number <= current, 'Invalid unsigned producer attempt')
        if number not in attempts:
            attempt = gh(f'actions/runs/{run["id"]}/attempts/{number}')
            require(attempt['id'] == run['id'] and attempt['run_attempt'] == number
                    and attempt['head_sha'] == run['head_sha'], 'Unsigned producer attempt identity mismatch')
            attempts[number] = attempt
        attempt_started = timestamp(attempts[number].get('run_started_at'))
        if started >= attempt_started:
            producers.append(job)
        else:
            require(completed is not None and completed < attempt_started,
                    'Unsigned producer overlaps the start of its reported attempt')
            copies.append(job)

    require(len(producers) == 1 and producers[0]['status'] == 'completed'
            and producers[0]['conclusion'] == 'success' and producers[0].get('completed_at'),
            'Expected one successful unsigned producer job when the selected artifact was uploaded')
    job = producers[0]
    if copies:
        # GitHub reruns can copy a successful job into a later attempt under
        # a new job ID. Its original execution finished before that attempt.
        fields = ('status', 'conclusion', 'started_at', 'completed_at', 'runner_id', 'steps')
        require(type(job.get('runner_id')) is int and job['runner_id'] > 0 and job.get('steps')
                and all(copy['run_attempt'] > job['run_attempt']
                        and all(copy.get(field) == job.get(field) for field in fields) for copy in copies),
                'Copied unsigned producer does not match the original execution')
    require(expected_id is None or (type(expected_id) is int and job['id'] == expected_id),
            'Unsigned artifact differs from the exact attested producer job')
    return job['id']


def unsigned_proof(evidence):
    from custom_release import WORKFLOW as CUSTOM_WORKFLOW

    run_id, source = str(evidence['artifact_run_id']), evidence['source_sha']
    require(re.fullmatch(r'[1-9][0-9]*', run_id) and re.fullmatch(r'[0-9a-f]{40}', source),
            'Invalid producer identity')
    run = gh(f'actions/runs/{run_id}')
    direct = (run['path'] == '.github/workflows/build-release.yml' and run['event'] == 'workflow_dispatch'
              and run['head_sha'] == source)
    hosted_event = (run['event'] in ('schedule', 'workflow_dispatch') or
                    (run['event'] == 'push' and evidence.get('maintenance', {}).get('kind') == 'security'))
    build_controller = evidence.get('quote_recovery', {}).get('original_controller', evidence['controller_sha'])
    upstream = (run['path'] == '.github/workflows/sync-upstream.yml' and hosted_event
                and run['head_sha'] == build_controller)
    custom = (run['path'] == CUSTOM_WORKFLOW and run['event'] == 'workflow_dispatch'
              and run['head_sha'] == build_controller and evidence.get('maintenance', {}).get('kind') == 'custom')
    require(run['id'] == int(run_id) and run['head_branch'] == 'main'
            and run.get('head_repository', {}).get('full_name') == REPO
            and (direct or upstream or custom),
            'Expected a main-branch release producer')
    job_pages = gh(f'actions/runs/{run_id}/jobs?filter=all&per_page=100', '--paginate', '--slurp')
    label = 'unsigned' if direct else 'build / unsigned'
    jobs = [job for page in job_pages for job in page['jobs'] if job['name'] == label
            and job['run_id'] == int(run_id) and job['head_sha'] == run['head_sha']]
    pages = gh(f'actions/runs/{run_id}/artifacts?per_page=100', '--paginate', '--slurp')
    assets = [item for page in pages for item in page['artifacts'] if item['name'] == 'validated-unsigned-apk']
    accepted = evidence.get('production_artifact')
    if 'production_artifact' in evidence:
        require(isinstance(accepted, dict) and type(accepted.get('id')) is int and accepted['id'] > 0,
                'Invalid accepted unsigned artifact ID')
        assets = [asset for asset in assets if asset['id'] == accepted['id']]
    require(len(assets) == 1 and not assets[0]['expired'], 'Expected one live validated unsigned artifact')
    asset = assets[0]
    compressed, expanded = ARCHIVE_LIMITS['validated-unsigned-apk']
    require(type(asset.get('id')) is int and asset['id'] > 0
            and asset['workflow_run']['id'] == int(run_id) and asset['workflow_run']['head_sha'] == run['head_sha']
            and 0 < asset['size_in_bytes'] <= compressed,
            'Unsigned artifact run or size mismatch')
    if accepted is not None:
        require(all(asset[key] == accepted.get(key) for key in ('id', 'digest', 'size_in_bytes')),
                'Public verification did not resolve the accepted unsigned artifact')
    require('production_job_id' not in evidence or evidence['production_job_id'] is not None,
            'Missing attested unsigned producer job ID')
    job_id = unsigned_producer_job(run, jobs, asset, evidence.get('production_job_id'))
    data = subprocess.check_output(['gh', 'api', f'{API}/actions/artifacts/{asset["id"]}/zip'], timeout=90)
    require(len(data) == asset['size_in_bytes'] and asset['digest'] == 'sha256:' + digest(data),
            'Unsigned artifact digest or size mismatch')
    with bounded_zip(data, expanded) as archive:
        raw = archive.read('zec-private-unsigned.apk')
        proof = json.loads(archive.read('proof.json'))
    with bounded_zip(raw, APK_PAYLOAD_LIMIT):
        pass
    require(proof['source_sha'] == source and str(proof['workflow_run_id']) == run_id
            and proof['apk_sha256'] == digest(raw), 'Unsigned APK does not match recorded source and producer')
    return raw, proof, {key: asset[key] for key in ('id', 'digest', 'size_in_bytes')}, job_id


def main():
    tag = os.environ['VERIFY_RELEASE_TAG']
    require(re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+-private\.[1-9][0-9]*', tag), 'Invalid release tag')
    release, discovery_route = latest(tag)
    require(not release['draft'] and not release['prerelease'], 'Expected a public stable release')
    assets = {item['name']: item for item in release['assets']}
    require(len(release['assets']) == 2 and set(assets) == {APK, CHECKSUM}, 'Unexpected public asset set')
    signed = published_asset(tag, assets[APK], PUBLIC_APK_LIMIT)
    sha = digest(signed)
    checksum = published_asset(tag, assets[CHECKSUM], 1024)
    require(checksum.decode().strip() == f'{sha}  {APK}', 'Public checksum file mismatch')
    attestation_digest = os.environ.get('ATTESTATION_DIGEST')
    if attestation_digest:
        evidence = read_attestation(Path(os.environ['RUNNER_TEMP']) / 'publication/release-attestation.json',
                                    attestation_digest)
        require(evidence['release_tag'] == tag and evidence['controller_sha'] == os.environ['ATTESTATION_CONTROLLER'],
                'Publication attestation does not match the current controller')
    else:
        response = gh(f'contents/.private/release-evidence/{tag}.json?ref=main')
        evidence = json.loads(base64.b64decode(response['content']))
    require(evidence['apk_sha256'] == sha, 'Public APK does not match trusted release evidence')
    ref = gh(f'git/ref/tags/{tag}')['object']
    for _ in range(4):
        if ref['type'] != 'tag':
            break
        ref = gh(f'git/tags/{ref["sha"]}')['object']
    require(release_tag_matches(tag, evidence['source_sha'], sha, release['id'], ref, gh),
            'Public tag does not identify the validated source')
    unsigned, proof, original_artifact, producer_job_id = unsigned_proof(evidence)
    require(proof['release_tag'] == tag and payload(signed) == payload(unsigned),
            'Public APK payload differs from the validated candidate')
    version_lines = [line for line in (release['body'] or '').splitlines()
                     if line.startswith('Android versionCode:')]
    require(version_lines == [f'Android versionCode: {proof["versionCode"]}'],
            'Latest-release metadata does not identify the validated version')
    tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0'
    with tempfile.TemporaryDirectory() as scratch:
        apk = Path(scratch) / APK
        apk.write_bytes(signed)
        report = subprocess.check_output([tools / 'apksigner', 'verify', '--print-certs', apk],
                                         text=True, timeout=60)
        require(re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)', report) == [CERT],
                'Public APK certificate mismatch')
        subprocess.run([tools / 'zipalign', '-c', '-P', '16', '4', apk], check=True, timeout=30)
        badging = subprocess.check_output([tools / 'aapt', 'dump', 'badging', apk], text=True, timeout=30)
        version = re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging)
        require(version is not None and version.groups() == (
            'com.personal.zecprivate.foss', str(proof['versionCode']), proof['versionName'])
            and 'application-debuggable' not in badging and check_apk(apk)['abis'] == ['arm64-v8a'],
            'Public APK manifest or architecture mismatch')
    print(json.dumps({'tag': tag, 'apk_sha256': sha, 'version_code': proof['versionCode'],
                      'source_sha': evidence['source_sha'], 'public_tag_source_sha': ref['sha'],
                      'unsigned_artifact_id': original_artifact['id'],
                      'unsigned_producer_job_id': producer_job_id,
                      'public_discovery_verified': True, 'public_discovery_route': discovery_route,
                      'signed_payload_matches': True}))


if __name__ == '__main__':
    main()
