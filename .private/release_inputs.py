"""Read release inputs from the selected immutable source revision."""
import argparse
import json
import re
import subprocess
from pathlib import Path
from release_metadata import Metadata, next_release_version, require, reserved_release_versions, wallet_release_versions


def git(source, *args):
    return subprocess.check_output(['git', *args], cwd=source, text=True, timeout=30).strip()


def selected_baseline(source, sha, path=None):
    value = json.loads(git(source, 'show', sha + ':.private/upgrade-baseline.json'))
    if path is not None:
        require(json.loads(Path(path).read_text()) == value, 'Selected baseline differs from source manifest')
    require(value.get('schema') == 1 and re.fullmatch('[0-9a-f]{40}', value.get('source_sha', ''))
            and type(value.get('version_code')) is int and value['version_code'] > 0
            and value.get('package') == 'com.personal.zecprivate.testnet.foss.debug',
            'Paired baseline source/version/package required')
    instrumentation = value.get('instrumentation', {})
    require(re.fullmatch('[0-9a-f]{64}', instrumentation.get('apk_sha256', ''))
            and instrumentation.get('artifact', {}).get('source_sha') == value['source_sha']
            and instrumentation['artifact'].get('source_release') == value['release'],
            'Paired baseline instrumentation identity required')
    return value


def require_local_upgrade_pair(local, hosted):
    require(all(local.get(key) == hosted[key] for key in ('release', 'source_sha', 'version_code'))
            and local.get('apk_sha256') == hosted['candidate_apk_sha256']
            and local.get('instrumentation', {}).get('apk_sha256') == hosted['instrumentation_apk_sha256'],
            'Direct release requires the exact paired fixture for the latest wallet release')


def version_code(source, sha):
    match = re.search(r'^ZCASH_VERSION_CODE=(\d+)$', git(source, 'show', sha + ':gradle.properties'), re.M)
    require(match is not None, 'Source versionCode required')
    return int(match[1])


def replacement_version(root, abandoned_code, metadata):
    from sync import version

    controller = git(root, 'rev-parse', 'HEAD')
    require(metadata.api('/git/ref/heads/main')['object']['sha'] == controller,
            'Select current protected main before preparing a replacement')
    reservations = reserved_release_versions(metadata)
    current_code = version_code(root, controller)
    require(abandoned_code > current_code and (0, abandoned_code) in reservations,
            'Expected a reserved, unpublished version above current main')
    upstream = json.loads(git(root, 'show', controller + ':.private/upstream.json'))
    revision = json.loads(git(root, 'show', controller + ':.private/release-revision.json'))['private_revision']
    versions = wallet_release_versions(metadata.api('/releases?per_page=100', paginated=True))
    require(all(code < abandoned_code for _, code in versions), 'A published version supersedes the abandoned code')
    next_revision, next_code = next_release_version(versions + reservations, revision, current_code,
                                                     version(upstream['tag'])[3])
    return dict(private_revision=next_revision, version_code=next_code, controller=controller)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Suggest a version for a reviewed replacement release')
    parser.add_argument('--replacement-of', type=int, required=True, help='Abandoned reserved Android version code')
    args = parser.parse_args()
    print(json.dumps(replacement_version(Path(__file__).resolve().parents[1], args.replacement_of, Metadata())))
