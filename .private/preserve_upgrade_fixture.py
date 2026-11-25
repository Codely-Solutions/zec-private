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


def maintenance_draft(release_id):
    draft = api('releases/' + str(release_id))
    if draft['id'] != release_id or draft['draft'] is not True or draft['tag_name'] != 'maintenance-fixtures':
        raise ValueError('Authenticated maintenance draft required')
    return draft


def fixture_asset(draft, name):
    matches = [asset for asset in draft['assets'] if asset['name'] == name]
    if len(matches) > 1:
        raise ValueError('Duplicate maintenance fixture asset: ' + name)
    return matches[0] if matches else None


def empty_upload_placeholder(asset):
    return bool(asset and asset.get('state') == 'starter' and type(asset.get('size')) is int
                and asset['size'] == 0 and not asset.get('digest')
                and type(asset.get('id')) is int and asset['id'] > 0)


def download_fixture_asset(asset, limit):
    size, asset_id = asset['size'], asset['id']
    if (asset.get('state') != 'uploaded' or type(asset_id) is not int or asset_id <= 0
            or type(size) is not int or not 0 < size <= limit):
        raise ValueError('Invalid existing fixture asset identity')
    raw = gh('api', '-H', 'Accept: application/octet-stream',
             'repos/' + REPO + '/releases/assets/' + str(asset_id))
    if len(raw) != size or 'sha256:' + hashlib.sha256(raw).hexdigest() != asset['digest']:
        raise ValueError('Independent fixture download mismatch')
    return raw


def preserved_index_entry(asset, tag):
    return dict(release_asset_id=asset['id'], size_in_bytes=asset['size'],
                digest=asset['digest'], source_release=tag)


def preserve(tag, hosted_archive=None):
    from hosted_fixture import MAX_ARCHIVE, recorded_pair, verify_pair

    out = Path(os.environ['CODEX_GUARD_OUTPUT']).resolve()
    evidence, core = recorded_pair(tag, ROOT)
    release = api('releases/tags/' + tag)
    require_recorded_public_release(release, evidence, api, ROOT)
    policy = json.loads((ROOT / '.private/maintenance-assets.json').read_text())
    release_id = policy['draft_release_id']
    draft = maintenance_draft(release_id)
    fixture = out / (tag + '-TESTNET_UPGRADE_FIXTURE.zip')
    metadata = out / (tag + '-TESTNET_UPGRADE_FIXTURE.json')
    previous = {file.name: fixture_asset(draft, file.name) for file in (fixture, metadata)}
    placeholders = {name: asset for name, asset in previous.items() if empty_upload_placeholder(asset)}
    existing_fixture = None if fixture.name in placeholders else previous[fixture.name]
    existing_metadata = None if metadata.name in placeholders else previous[metadata.name]
    registry_reference = None
    if existing_fixture:
        fixture.write_bytes(download_fixture_asset(existing_fixture, MAX_ARCHIVE))
        verify_pair(fixture, core, prepared=True)
    else:
        if hosted_archive is None:
            payloads = original_pair(core)
        else:
            payloads, registry_reference = retained_hosted_pair(tag, core, hosted_archive, ROOT)
        write_seedless_pair(fixture, payloads, core['build_source_sha'])
    provenance = dict(release=tag, production_source=evidence['source_sha'], build_source=core['build_source_sha'],
        version_code=core['candidate_version_code'], package='com.personal.zecprivate.testnet.foss.debug',
        original_artifact_id=core['artifact_id'], workflow_run_id=core.get('artifact_run_id', core['workflow_run_id']),
        original_apk_sha256=core['candidate_apk_sha256'],
        original_instrumentation_apk_sha256=core['instrumentation_apk_sha256'], fixture_digest=digest(fixture), fixture_bytes=fixture.stat().st_size,
        purpose='Next installed-update baseline; version-matched original app and instrumentation APKs, no wallet data or keys')
    if existing_metadata:
        raw = download_fixture_asset(existing_metadata, 64 * 1024)
        if json.loads(raw) != provenance:
            raise ValueError('Existing fixture metadata differs from verified provenance')
        metadata.write_bytes(raw)
    else:
        metadata.write_bytes((json.dumps(provenance, indent=2) + '\n').encode())
    for file, existing in ((fixture, existing_fixture), (metadata, existing_metadata)):
        if existing is None:
            placeholder = placeholders.get(file.name)
            if placeholder:
                current = fixture_asset(maintenance_draft(release_id), file.name)
                if current != placeholder or not empty_upload_placeholder(current):
                    raise ValueError('Fixture upload placeholder changed')
                gh('api', '-X', 'DELETE', 'repos/' + REPO + '/releases/assets/' + str(placeholder['id']))
            gh('release', 'upload', 'maintenance-fixtures', '--repo', REPO, str(file))
    final = maintenance_draft(release_id)
    index = {}
    for file, existing, limit in ((fixture, existing_fixture, MAX_ARCHIVE),
                                  (metadata, existing_metadata, 64 * 1024)):
        asset = fixture_asset(final, file.name)
        if (not asset or asset['digest'] != digest(file) or asset['size'] != file.stat().st_size
                or (existing is not None and asset['id'] != existing['id'])):
            raise ValueError('Uploaded fixture metadata mismatch')
        if existing is None:
            download_fixture_asset(asset, limit)
        index[file.name.removeprefix(tag + '-')] = preserved_index_entry(asset, tag)
    if registry_reference:
        provenance['recovered_from_registry'] = registry_reference
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
