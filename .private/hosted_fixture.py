"""Bind a private registry fixture to the original accepted testnet APK pair."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

from preserve_upgrade_fixture import digest, fixture_apks, write_seedless_pair
from release_metadata import Metadata, require


ROOT = Path(__file__).resolve().parents[1]
REGISTRY = 'ghcr.io/codely-solutions/zec-private-upgrade-fixtures'
MAX_ARCHIVE = 160 * 1024**2


def recorded_pair(tag):
    require(re.fullmatch(r'v\d+\.\d+\.\d+-private\.\d+', tag), 'Exact release tag required')
    evidence = json.loads((ROOT / '.private/release-evidence' / (tag + '.json')).read_text())
    core = evidence['core']
    require(evidence['release_tag'] == tag and core['passed'] is True
            and core['real_funds_used'] is False and core['personal_wallet_access'] is False
            and core['candidate_version_code'] == evidence['versionCode'],
            'Verified release with isolated testnet execution required')
    return evidence, core


def verify_pair(path, core, *, prepared):
    require(0 < path.stat().st_size <= MAX_ARCHIVE, 'Fixture archive size outside budget')
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        require(len(entries) <= 16 and sum(entry.file_size for entry in entries) <= MAX_ARCHIVE,
                'Fixture archive expansion outside budget')
        names = archive.namelist()
        require(len(names) == len(set(names)), 'Duplicate fixture entries')
        payloads = fixture_apks(archive, core)
        require(all(re.fullmatch(r'[A-Za-z0-9_.-]+\.apk', name) for name in payloads),
                'Invalid APK filename')
        if prepared:
            require(set(names) == set(payloads) | {'source-sha.txt', 'SHA256SUMS'},
                    'Unexpected fixture entries')
        return payloads


def prepare(tag, archive, out, metadata):
    evidence, core = recorded_pair(tag)
    live = metadata.api('/releases/tags/' + tag)
    apks = [asset for asset in live['assets'] if asset['name'] == 'zec-private-latest.apk']
    require(live['tag_name'] == tag and live['draft'] is False and len(apks) == 1
            and apks[0].get('digest') == 'sha256:' + evidence['apk_sha256'],
            'Published APK differs from recorded verification')
    asset = metadata.api('/actions/artifacts/' + str(core['artifact_id']))
    require(asset['expired'] is False and asset['id'] == core['artifact_id']
            and asset['workflow_run']['id'] == core['workflow_run_id']
            and asset['workflow_run']['head_sha'] == core['build_source_sha']
            and 0 < asset['size_in_bytes'] <= MAX_ARCHIVE,
            'Accepted artifact run or source differs')
    with archive.open('xb') as stream:
        subprocess.run(['gh', 'api', metadata.repo + '/actions/artifacts/' + str(asset['id']) + '/zip'],
                       stdout=stream, timeout=120, check=True)
    require(archive.stat().st_size == asset['size_in_bytes'] and digest(archive) == asset['digest'],
            'Accepted artifact archive differs from GitHub digest')
    write_seedless_pair(out, verify_pair(archive, core, prepared=False), core['build_source_sha'])
    verify_pair(out, core, prepared=True)


def verify(tag, path, archive_digest, oci_digest):
    _, core = recorded_pair(tag)
    require(re.fullmatch(r'sha256:[0-9a-f]{64}', archive_digest) and digest(path) == archive_digest,
            'Registry fixture differs from the prepared archive')
    require(re.fullmatch(r'sha256:[0-9a-f]{64}', oci_digest), 'Exact OCI manifest digest required')
    verify_pair(path, core, prepared=True)
    return dict(release=tag, reference=REGISTRY + '@' + oci_digest, digest=archive_digest,
                size_in_bytes=path.stat().st_size, source_sha=core['build_source_sha'],
                candidate_apk_sha256=core['candidate_apk_sha256'],
                instrumentation_apk_sha256=core['instrumentation_apk_sha256'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('prepare', 'verify'))
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--tag', required=True)
    args = parser.parse_args()
    out = args.directory / 'fixture.zip'
    if args.operation == 'prepare':
        prepare(args.tag, args.directory / 'producer.zip', out, Metadata())
        print(f'archive_digest={digest(out)}')
    else:
        print(json.dumps(verify(args.tag, out, os.environ['ARCHIVE_DIGEST'], os.environ['OCI_DIGEST'])))


if __name__ == '__main__':
    main()
