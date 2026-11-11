"""Verify the latest public wallet APK from the original unsigned artifact."""
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
from urllib.request import Request, urlopen
import zipfile

from apk_architecture import check_apk
from publish import CERT, REPO, read_attestation


APK = 'zec-private-latest.apk'
CHECKSUM = 'SHA256SUMS'
PUBLIC = f'https://github.com/{REPO}'
API = f'repos/{REPO}'
MIB = 1024 * 1024


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
        value = json.loads(public_read(f'https://api.github.com/{API}/releases/latest', MIB))
        if value.get('tag_name') == tag:
            return value
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
    with bounded_zip(data, 256 * MIB) as archive:
        return {item: digest(archive.read(item)) for item in archive.namelist()
                if not re.fullmatch(r'META-INF/[^/]+\.(RSA|DSA|EC|SF|MF)', item, re.I)}


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
    upstream = (run['path'] == '.github/workflows/sync-upstream.yml' and hosted_event
                and run['head_sha'] == evidence['controller_sha'])
    custom = (run['path'] == CUSTOM_WORKFLOW and run['event'] == 'workflow_dispatch'
              and run['head_sha'] == evidence['controller_sha'] and evidence.get('maintenance', {}).get('kind') == 'custom')
    require(run['head_branch'] == 'main' and (direct or upstream or custom),
            'Expected a main-branch release producer')
    jobs = gh(f'actions/runs/{run_id}/jobs?per_page=100')['jobs']
    successful = [job for job in jobs if job['name'].rsplit(' / ', 1)[-1] == 'unsigned'
                  and job['conclusion'] == 'success']
    require(len(successful) == 1, 'Expected one successful unsigned producer job')
    pages = gh(f'actions/runs/{run_id}/artifacts?per_page=100', '--paginate', '--slurp')
    assets = [item for page in pages for item in page['artifacts'] if item['name'] == 'validated-unsigned-apk']
    require(len(assets) == 1 and not assets[0]['expired'], 'Expected one live validated unsigned artifact')
    asset = assets[0]
    require(asset['workflow_run']['id'] == int(run_id) and asset['workflow_run']['head_sha'] == run['head_sha']
            and 0 < asset['size_in_bytes'] <= 64 * MIB,
            'Unsigned artifact run or size mismatch')
    if 'production_artifact' in evidence:
        require(all(asset[key] == evidence['production_artifact'][key]
                    for key in ('id', 'digest', 'size_in_bytes')),
                'Public verification did not resolve the accepted unsigned artifact')
    data = subprocess.check_output(['gh', 'api', f'{API}/actions/artifacts/{asset["id"]}/zip'], timeout=90)
    require(len(data) == asset['size_in_bytes'] and asset['digest'] == 'sha256:' + digest(data),
            'Unsigned artifact digest or size mismatch')
    with bounded_zip(data, 192 * MIB) as archive:
        raw = archive.read('zec-private-unsigned.apk')
        proof = json.loads(archive.read('proof.json'))
    require(proof['source_sha'] == source and str(proof['workflow_run_id']) == run_id
            and proof['apk_sha256'] == digest(raw), 'Unsigned APK does not match recorded source and producer')
    return raw, proof, asset['id']


def main():
    tag = os.environ['VERIFY_RELEASE_TAG']
    require(re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+-private\.[1-9][0-9]*', tag), 'Invalid release tag')
    release = latest(tag)
    require(not release['draft'] and not release['prerelease'], 'Expected a public stable release')
    assets = {item['name']: item for item in release['assets']}
    require(len(release['assets']) == 2 and set(assets) == {APK, CHECKSUM}, 'Unexpected public asset set')
    signed = published_asset(tag, assets[APK], 96 * MIB)
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
    require(ref['type'] == 'commit' and ref['sha'] == evidence['source_sha'],
            'Public tag does not identify the validated source')
    unsigned, proof, artifact_id = unsigned_proof(evidence)
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
                      'source_sha': evidence['source_sha'], 'unsigned_artifact_id': artifact_id,
                      'public_discovery_verified': True, 'signed_payload_matches': True}))


if __name__ == '__main__':
    main()
