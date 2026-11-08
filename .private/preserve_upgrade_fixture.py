"""Preserve the signed release's executed seedless test APK; run inside the resource guard."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REPO = 'Sammy-Lastre/zec-private'


def gh(*args, **options):
    executable = os.environ.get('PRIVATE_GH', 'gh')
    return subprocess.check_output([executable, *args], timeout=120, **options)


def api(path):
    return json.loads(gh('api', 'repos/' + REPO + '/' + path))


def digest(path):
    return 'sha256:' + hashlib.sha256(path.read_bytes()).hexdigest()


def preserve(tag):
    if not re.fullmatch(r'v\d+\.\d+\.\d+-private\.\d+', tag):
        raise ValueError('Exact private release tag required')
    out = Path(os.environ['CODEX_GUARD_OUTPUT']).resolve()
    evidence = json.loads((ROOT / '.private/release-evidence' / (tag + '.json')).read_text())
    core = evidence['core']
    if not core['passed'] or core['real_funds_used'] or core['personal_wallet_access']:
        raise ValueError('Accepted isolated testnet execution required')
    release = json.loads(gh('release', 'view', tag, '--repo', REPO, '--json', 'isDraft,body,assets'))
    if release['isDraft'] or 'Android versionCode: ' + str(core['candidate_version_code']) not in release['body']:
        raise ValueError('Published matching wallet release required')
    live = api('actions/artifacts/' + str(core['artifact_id']))
    if live['expired'] or live['workflow_run']['id'] != core['workflow_run_id'] or live['workflow_run']['head_sha'] != core['build_source_sha']:
        raise ValueError('Executed artifact producer mismatch')
    raw = gh('api', 'repos/' + REPO + '/actions/artifacts/' + str(core['artifact_id']) + '/zip')
    if len(raw) != live['size_in_bytes'] or 'sha256:' + hashlib.sha256(raw).hexdigest() != live['digest']:
        raise ValueError('Artifact archive mismatch')
    import io
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        if sum(x.file_size for x in archive.infolist()) > 300 * 1024**2:
            raise ValueError('Artifact expansion exceeds budget')
        source = next(n for n in archive.namelist() if n.endswith('source-sha.txt'))
        if archive.read(source).decode().strip() != core['build_source_sha']:
            raise ValueError('Embedded source mismatch')
        names = [n for n in archive.namelist() if n.endswith('.apk') and 'androidTest' not in n]
        if len(names) != 1:
            raise ValueError('Exactly one build APK required')
        apk = archive.read(names[0])
    if hashlib.sha256(apk).hexdigest() != core['candidate_apk_sha256']:
        raise ValueError('Original executed APK mismatch')
    fixture = out / (tag + '-TESTNET_UPGRADE_FIXTURE.zip')
    with zipfile.ZipFile(fixture, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(Path(names[0]).name, apk)
        archive.writestr('source-sha.txt', core['build_source_sha'] + '\n')
        archive.writestr('SHA256SUMS', core['candidate_apk_sha256'] + '  ' + Path(names[0]).name + '\n')
    provenance = dict(release=tag, production_source=evidence['source_sha'], build_source=core['build_source_sha'],
        version_code=core['candidate_version_code'], package='com.personal.zecprivate.testnet.foss.debug',
        original_artifact_id=core['artifact_id'], workflow_run_id=core['workflow_run_id'],
        original_apk_sha256=core['candidate_apk_sha256'], fixture_digest=digest(fixture), fixture_bytes=fixture.stat().st_size,
        purpose='Next installed-update baseline; original build APK only, no wallet data or keys')
    metadata = out / (tag + '-TESTNET_UPGRADE_FIXTURE.json')
    metadata.write_text(json.dumps(provenance, indent=2) + '\n')
    policy = json.loads((ROOT / '.private/maintenance-assets.json').read_text())
    draft = api('releases/' + str(policy['draft_release_id']))
    if not draft['draft'] or draft['tag_name'] != 'maintenance-fixtures':
        raise ValueError('Authenticated maintenance draft required')
    if {fixture.name, metadata.name} & {a['name'] for a in draft['assets']}:
        raise ValueError('Existing fixture requires provenance reconciliation; replacement refused')
    gh('release', 'upload', 'maintenance-fixtures', '--repo', REPO, str(fixture), str(metadata))
    assets = {a['name']: a for a in api('releases/' + str(policy['draft_release_id']))['assets']}
    index = {}
    for file in (fixture, metadata):
        asset = assets[file.name]
        if asset['digest'] != digest(file) or asset['size'] != file.stat().st_size:
            raise ValueError('Uploaded fixture metadata mismatch')
        downloaded = gh('api', '-H', 'Accept: application/octet-stream', 'repos/' + REPO + '/releases/assets/' + str(asset['id']))
        if len(downloaded) != file.stat().st_size or 'sha256:' + hashlib.sha256(downloaded).hexdigest() != digest(file):
            raise ValueError('Independent fixture download mismatch')
        index[file.name.removeprefix(tag + '-')] = dict(release_asset_id=asset['id'], size_in_bytes=asset['size'],
            digest=asset['digest'], source_release=tag)
    policy['fixtures'][tag] = index
    (out / 'maintenance-assets-next.json').write_text(json.dumps(policy, indent=2) + '\n')
    (out / 'fixture-preservation.json').write_text(json.dumps(dict(passed=True, **provenance, assets=index), indent=2) + '\n')
    print(json.dumps(dict(passed=True, tag=tag, fixture_bytes=fixture.stat().st_size)))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    preserve(parser.parse_args().tag)
