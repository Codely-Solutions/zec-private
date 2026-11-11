"""Calendar and reviewed security eligibility for hosted maintenance releases."""
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

from release_metadata import require

FIRST_WINDOW = datetime(2026, 12, 1, 9, tzinfo=timezone.utc)
REVIEW = '.private/security-maintenance.json'
PATCH = '.private/security-maintenance.patch'
MARKER = '.private/maintenance-candidate.json'
WALLET_TAG = re.compile(r'v\d+\.\d+\.\d+-private\.[1-9]\d*')


def window(now=None):
    now = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    start = now.replace(day=1, hour=9, minute=0, second=0, microsecond=0)
    if now < start:
        start = start.replace(year=start.year - (start.month == 1), month=(start.month - 2) % 12 + 1)
    return start if start >= FIRST_WINDOW else None


def published_wallets(metadata):
    return [release for page in metadata.api('/releases?per_page=100', paginated=True) for release in page
            if not release['draft'] and not release['prerelease'] and WALLET_TAG.fullmatch(release['tag_name'])]


def routine_window(root, metadata, now=None):
    cutoff = window(now)
    if cutoff is None:
        return None
    for release in published_wallets(metadata):
        if datetime.fromisoformat(release['published_at']) < cutoff:
            continue
        evidence = root / '.private/release-evidence' / (release['tag_name'] + '.json')
        if evidence.is_file():
            record = json.loads(evidence.read_bytes())
            apks = [asset for asset in release['assets'] if asset['name'] == 'zec-private-latest.apk']
            if (record.get('maintenance', {}).get('kind') == 'security' and len(apks) == 1
                    and apks[0].get('digest') == 'sha256:' + record['apk_sha256']):
                continue
        return None
    return cutoff


def security_review(root, metadata, advisory_api):
    review = json.loads((root / REVIEW).read_bytes())
    if review is None:
        return None
    tag = review['affected_release']
    require(WALLET_TAG.fullmatch(tag), 'Security review requires an exact affected wallet release')
    latest = metadata.api('/releases/latest')
    if latest['tag_name'] != tag:
        return None
    require(re.fullmatch(r'GHSA(?:-[a-z0-9]{4}){3}', review['advisory'])
            and re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', review['advisory_repository'])
            and review['scope'] in ('wallet', 'release-pipeline')
            and all(isinstance(review[key], str) and review[key].strip() for key in ('exposure', 'fix')),
            'Security review requires upstream provenance, exposure and supported fix evidence')
    evidence = json.loads((root / '.private/release-evidence' / (tag + '.json')).read_bytes())
    require(evidence['source_sha'] == review['affected_source'], 'Security review names a different shipped source')
    apks = [asset for asset in latest['assets'] if asset['name'] == 'zec-private-latest.apk']
    require(len(apks) == 1 and apks[0].get('digest') == 'sha256:' + evidence['apk_sha256'],
            'Security review requires the exact verified public APK')
    advisory = advisory_api(f"repos/{review['advisory_repository']}/security-advisories/{review['advisory']}")
    require(advisory['ghsa_id'] == review['advisory'] and advisory['state'] == 'published'
            and not advisory['withdrawn_at'] and advisory['updated_at'] == review['advisory_updated_at'],
            'Security review requires the exact current published upstream advisory')
    require(hashlib.sha256((root / PATCH).read_bytes()).hexdigest() == review['patch_sha256'],
            'Reviewed security patch digest mismatch')
    return review


def git(root, *args, env=None, data=None):
    return subprocess.check_output(['git', *args], cwd=root, env=env, input=data, timeout=45)


def security_patch_scope(paths):
    require(paths and all(not path.startswith(('.github/', '.private/')) and path not in ('.github', '.private', 'LICENSE')
                          for path in paths), 'Security patch may change product or build inputs only')


def verify_security_child(source, controller, root):
    from acceptance_policy import verify_canonical_patch

    def read(revision, path):
        return git(root, 'show', f'{revision}:{path}')

    review = json.loads(read(controller, REVIEW))
    require(isinstance(review, dict), 'Security candidate needs a protected review')
    expected = dict(kind='security', advisory=review['advisory'], affected_release=review['affected_release'],
                    patch_sha256=review['patch_sha256'])
    require(json.loads(read(source, MARKER)) == expected, 'Security candidate differs from the protected review')
    require(git(root, 'rev-list', '--parents', '-n', '1', source).decode().split() == [source, controller],
            'Expected a single exact security candidate parent')
    patch = read(controller, PATCH)
    require(hashlib.sha256(patch).hexdigest() == review['patch_sha256'], 'Reviewed security patch digest mismatch')
    require(re.fullmatch(r'[a-f0-9]{40}', review['affected_source']), 'Exact affected source required')
    require(not git(root, 'diff', '--name-only', review['affected_source'], controller, '--', '.',
                    ':(exclude).github', ':(exclude).private', ':(exclude)docs', ':(exclude)README.md'),
            'Main contains unrelated product changes since the reviewed release')
    with tempfile.TemporaryDirectory(dir=os.environ.get('RUNNER_TEMP')) as scratch:
        env = dict(os.environ, GIT_INDEX_FILE=str(Path(scratch) / 'index'))
        git(root, 'read-tree', controller, env=env)
        git(root, 'apply', '--cached', '-', env=env, data=patch)
        tree = git(root, 'write-tree', env=env).decode().strip()
        paths = git(root, 'diff', '--no-renames', '--name-only', '-z', controller, tree).decode().split('\0')
        security_patch_scope([path for path in paths if path])
        derived = (MARKER, '.private/candidate.json', '.private/source-state.json',
                   '.private/fork.patch', '.private/release-revision.json', 'gradle.properties')
        require(not git(root, 'diff', '--name-only', tree, source, '--', '.',
                        *(':(exclude)' + path for path in derived)), 'Security candidate adds unreviewed changes')
        require(not git(root, 'ls-tree', '--name-only', source, '--', '.private/candidate.json'),
                'Security candidate must not include an upstream update')
        version = rb'^ZCASH_VERSION_CODE=\d+$'
        old, new = (read(revision, 'gradle.properties') for revision in (tree, source))
        require(len(re.findall(version, old, re.M)) == len(re.findall(version, new, re.M)) == 1
                and re.sub(version, b'', old, flags=re.M) == re.sub(version, b'', new, flags=re.M),
                'Security candidate changes Gradle settings outside the reviewed patch')
    state = json.loads(read(controller, '.private/upstream.json'))
    prior = json.loads(read(controller, '.private/release-revision.json'))['private_revision']
    revised = json.loads(read(source, '.private/release-revision.json'))
    require(set(revised) == {'private_revision'} and type(revised['private_revision']) is int
            and prior < revised['private_revision'] <= 999
            and state['versionCode'] < int(re.search(rb'^ZCASH_VERSION_CODE=(\d+)$', new, re.M)[1]) <= 2_100_000_000,
            'Security candidate must advance the release version')
    verify_canonical_patch(source, controller, state['sha'], root)
    return expected


def changed_marker(root, source, controller, path):
    if (git(root, 'diff', '--name-only', controller, source, '--', path)
            and git(root, 'ls-tree', '--name-only', source, '--', path)):
        return json.loads(git(root, 'show', f'{source}:{path}'))
    return {}


def admit_cadence(root, source, controller, metadata, advisory_api):
    maintenance = changed_marker(root, source, controller, MARKER)
    if maintenance.get('kind') == 'security':
        review = security_review(root, metadata, advisory_api)
        require(review is not None and review['advisory'] == maintenance['advisory'],
                'Security review is no longer eligible for the shipped release')
        return
    require(os.environ.get('GITHUB_EVENT_NAME') != 'push', 'Security review push cannot admit routine maintenance')
    cutoff = routine_window(root, metadata)
    require(cutoff is not None, 'Monthly routine release window is not due')
    upstream = changed_marker(root, source, controller, '.private/candidate.json')
    require(upstream or maintenance.get('kind') == 'gradle-wrapper-patch',
            'Expected a prepared routine maintenance candidate')
    if upstream:
        require(datetime.fromisoformat(upstream['published_at']) <= cutoff,
                'Upstream release arrived after the monthly cutoff')
    head = upstream.get('wrapper_head') or maintenance.get('proposal_head')
    if head:
        require(re.fullmatch(r'[a-f0-9]{40}', head), 'Exact wrapper proposal head required')
        date = metadata.api('/commits/' + head)['commit']['committer']['date']
        require(datetime.fromisoformat(date) <= cutoff, 'Wrapper update arrived after the monthly cutoff')


def main():
    from maintenance_watch import api, verified_wrapper
    from prepare_maintenance import prepare, prepare_security
    from release_metadata import Metadata
    import sync

    root = sync.ROOT
    metadata = Metadata()
    controller = os.environ['GITHUB_SHA']
    current = json.loads((root / '.private/upstream.json').read_bytes())['tag']
    latest = sync.discover()
    if latest and sync.version(latest['tag_name']) > sync.version(current):
        print(f"Stable upstream {latest['tag_name']} is available for maintenance selection.")
    review = security_review(root, metadata, api)
    if review:
        source = prepare_security(review, controller, metadata)
        sync.output(source_sha=source)
        return
    if os.environ.get('GITHUB_EVENT_NAME') == 'push':
        return
    cutoff = routine_window(root, metadata)
    if cutoff is None:
        print('Routine changes remain queued in the upstream release history and open proposals.')
        return
    if latest and datetime.fromisoformat(latest['published_at']) > cutoff:
        latest = sync.discover(cutoff)
    proposal, head = os.environ['PROPOSAL'], os.environ['PROPOSAL_HEAD']
    if latest and sync.version(latest['tag_name']) > sync.version(current):
        wrapper = None
        if proposal:
            wrapper = (*verified_wrapper(metadata.repo, int(proposal), controller, head, metadata.api('')['id']), head)
        sync.main(latest, wrapper)
    elif proposal:
        sync.output(source_sha=prepare(proposal, head, controller))


if __name__ == '__main__':
    main()
