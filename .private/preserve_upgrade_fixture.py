"""Preserve the signed release's executed seedless app/instrumentation pair; run inside the resource guard."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

from published_history import require_recorded_public_release


ROOT = Path(__file__).resolve().parents[1]
REPO = 'Codely-Solutions/zec-private'


def gh(*args, **options):
    executable = os.environ.get('PRIVATE_GH', 'gh')
    return subprocess.check_output([executable, *args], timeout=120, **options)


def api(path):
    return json.loads(gh('api', 'repos/' + REPO + '/' + path))


def digest(path):
    return 'sha256:' + hashlib.sha256(path.read_bytes()).hexdigest()


def artifact_matches_pair(asset, pair):
    return (asset['expired'] is False and asset['id'] == pair['artifact_id']
            and asset['workflow_run']['id'] == pair.get('artifact_run_id', pair['workflow_run_id'])
            and asset['workflow_run']['head_sha'] == pair.get('producer_controller_sha', pair['build_source_sha'])
            and ('producer' not in pair or all(asset[key] == pair['producer'][key]
                                               for key in ('id', 'digest', 'size_in_bytes'))))


def fixture_apks(archive,core):
    sources=[n for n in archive.namelist() if n.endswith('source-sha.txt')]
    if len(sources)!=1 or archive.read(sources[0]).decode().strip()!=core['build_source_sha']:
        raise ValueError('Embedded source mismatch')
    payloads={}
    for instrumentation,field in ((False,'candidate_apk_sha256'),(True,'instrumentation_apk_sha256')):
        names=[n for n in archive.namelist() if n.endswith('.apk') and ('androidTest' in n)==instrumentation]
        if len(names)!=1:
            raise ValueError('Exactly one app and one version-matched instrumentation APK required')
        name=Path(names[0]).name
        data=archive.read(names[0])
        if hashlib.sha256(data).hexdigest()!=core[field]:
            raise ValueError('Original executed APK mismatch: '+field)
        if name in payloads:
            raise ValueError('Fixture APK basenames collide')
        payloads[name]=data
    return payloads


def write_seedless_pair(fixture, payloads, source):
    entries = dict(payloads, **{
        'source-sha.txt': (source + '\n').encode(),
        'SHA256SUMS': ''.join(hashlib.sha256(data).hexdigest() + '  ' + name + '\n'
                             for name, data in sorted(payloads.items())).encode(),
    })
    with zipfile.ZipFile(fixture, 'x') as archive:
        for name, data in sorted(entries.items()):
            # A resumed preservation must produce the same archive bytes.
            archive.writestr(zipfile.ZipInfo(name), data, compress_type=zipfile.ZIP_STORED)


def original_pair(core):
    from hosted_fixture import MAX_ARCHIVE

    path = 'actions/artifacts/' + str(core['artifact_id'])
    live = api(path)
    if not artifact_matches_pair(live, core) or not 0 < live['size_in_bytes'] <= MAX_ARCHIVE:
        raise ValueError('Executed artifact producer or size mismatch')
    raw = gh('api', 'repos/' + REPO + '/' + path + '/zip')
    if len(raw) != live['size_in_bytes'] or 'sha256:' + hashlib.sha256(raw).hexdigest() != live['digest']:
        raise ValueError('Artifact archive mismatch')
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        if sum(entry.file_size for entry in archive.infolist()) > 300 * 1024**2:
            raise ValueError('Artifact expansion exceeds budget')
        return fixture_apks(archive, core)


def retained_hosted_pair(tag, core, archive, root=ROOT):
    from hosted_fixture import MAX_ARCHIVE, REGISTRY, verify_pair

    selected = json.loads((root / '.private/hosted-fixture.json').read_bytes())
    if (selected['release'] != tag or selected['source_sha'] != core['build_source_sha']
            or selected['version_code'] != core['candidate_version_code']
            or any(selected[key] != core[key] for key in ('candidate_apk_sha256', 'instrumentation_apk_sha256'))
            or not re.fullmatch(re.escape(REGISTRY) + r'@sha256:[0-9a-f]{64}', selected['reference'])
            or not 0 < archive.stat().st_size == selected['size_in_bytes'] <= MAX_ARCHIVE
            or digest(archive) != selected['digest']):
        raise ValueError('Expected the verified hosted archive for the exact recorded pair')
    return verify_pair(archive, core, prepared=True), selected['reference']


def preserve(tag, hosted_archive=None):
    from hosted_fixture import recorded_pair

    out = Path(os.environ['CODEX_GUARD_OUTPUT']).resolve()
    evidence, core = recorded_pair(tag, ROOT)
    release = api('releases/tags/' + tag)
    require_recorded_public_release(release, evidence, api, ROOT)
    registry_reference = None
    if hosted_archive is None:
        payloads = original_pair(core)
    else:
        payloads, registry_reference = retained_hosted_pair(tag, core, hosted_archive, ROOT)
    fixture = out / (tag + '-TESTNET_UPGRADE_FIXTURE.zip')
    with zipfile.ZipFile(fixture, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for name,data in payloads.items():
            archive.writestr(name,data)
        archive.writestr('source-sha.txt', core['build_source_sha'] + '\n')
        archive.writestr('SHA256SUMS',''.join(hashlib.sha256(data).hexdigest()+'  '+name+'\n'
                                           for name,data in payloads.items()))
    provenance = dict(release=tag, production_source=evidence['source_sha'], build_source=core['build_source_sha'],
        version_code=core['candidate_version_code'], package='com.personal.zecprivate.testnet.foss.debug',
        original_artifact_id=core['artifact_id'], workflow_run_id=core.get('artifact_run_id', core['workflow_run_id']),
        original_apk_sha256=core['candidate_apk_sha256'],
        original_instrumentation_apk_sha256=core['instrumentation_apk_sha256'], fixture_digest=digest(fixture), fixture_bytes=fixture.stat().st_size,
        purpose='Next installed-update baseline; version-matched original app and instrumentation APKs, no wallet data or keys')
    if registry_reference:
        provenance['recovered_from_registry'] = registry_reference
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
    parser.add_argument('--hosted-archive', type=Path, help='Exact ZIP pulled from the recorded private OCI fixture')
    args = parser.parse_args()
    preserve(args.tag, args.hosted_archive)
