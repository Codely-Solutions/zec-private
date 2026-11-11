"""Read-only maintenance discovery. A shadow finding grants no promotion authority."""
import base64
import datetime as dt
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
from urllib.request import urlopen


REPOSITORY = 'Codely-Solutions/zec-private'
CONTROL_ROOTS = {'.github', '.private'}
DEPENDENCY_FILES = {'gradle.properties', 'gradle/verification-metadata.xml',
                    'gradle/wrapper/gradle-wrapper.properties', 'gradle/wrapper/gradle-wrapper.jar'}
WRAPPER_PROPERTIES = 'gradle/wrapper/gradle-wrapper.properties'
WRAPPER_VERSION = r'([1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)'


def trusted_proposal(pr, repository_id):
    return (pr['state'] == 'open' and not pr['draft']
            and pr['user']['login'] == 'dependabot[bot]' and pr['user']['type'] == 'Bot'
            and pr['base']['ref'] == 'main' and pr['base']['repo']['id'] == repository_id
            and pr['head']['repo'] is not None and pr['head']['repo']['id'] == repository_id
            and pr['head']['ref'].startswith('dependabot/')
            and re.fullmatch(r'[0-9a-f]{40}', pr['head']['sha']) is not None)


def classify_paths(files, count):
    if not 0 < count <= 100 or len(files) != count:
        return 'incomplete diff'
    paths = [path for file in files for path in (file['filename'], file.get('previous_filename')) if path]
    if any(PurePosixPath(path).parts[0] in CONTROL_ROOTS for path in paths):
        return 'controller change requires review'
    if any(file['status'] != 'modified' for file in files):
        return 'file creation, removal or rename requires review'
    if any(path not in DEPENDENCY_FILES and PurePosixPath(path).name != 'gradle.lockfile' for path in paths):
        return 'change outside dependency declarations requires review'
    return 'dependency proposal needs provenance and acceptance'


def wrapper_patch(base, proposed, published_checksum):
    """Admit only the exact stable patch coordinates of an unchanged wrapper."""
    url = r'distributionUrl=https\\://services\.gradle\.org/distributions/gradle-' + WRAPPER_VERSION + r'-bin\.zip'
    checksum = r'distributionSha256Sum=([0-9a-f]{64})'

    def coordinates(text):
        lines = text.splitlines()
        versions = [match for line in lines if (match := re.fullmatch(url, line))]
        sums = [match[1] for line in lines if (match := re.fullmatch(checksum, line))]
        if len(versions) != 1 or len(sums) != 1:
            raise ValueError('Expected one stable Gradle version and one SHA-256')
        remaining = [line for line in lines if not re.fullmatch(url, line) and not re.fullmatch(checksum, line)]
        if any(line.lstrip().startswith(('distributionUrl', 'distributionSha256Sum')) for line in remaining):
            raise ValueError('Ambiguous wrapper properties')
        return tuple(map(int, versions[0].groups())), sums[0], remaining

    old, _, original = coordinates(base)
    new, proposed_checksum, changed = coordinates(proposed)
    if new[:2] != old[:2] or new <= old or changed != original:
        raise ValueError('Only a stable patch with unchanged wrapper settings is eligible')
    version = '.'.join(map(str, new))
    if published_checksum(version) != proposed_checksum:
        raise ValueError('Proposed checksum differs from the official Gradle distribution')
    return version


def official_checksum(version):
    url = f'https://services.gradle.org/distributions/gradle-{version}-bin.zip.sha256'
    with urlopen(url, timeout=15) as response:
        value = response.read(128).decode('ascii').strip()
    if not re.fullmatch(r'[0-9a-f]{64}', value):
        raise ValueError('Gradle did not return a SHA-256')
    return value


def wrapper_source(prefix, sha):
    if not re.fullmatch(r'[0-9a-f]{40}', sha):
        raise ValueError('Exact wrapper source revision required')
    metadata = api(f'{prefix}/contents/{WRAPPER_PROPERTIES}?ref={sha}')
    if metadata['type'] != 'file' or metadata['encoding'] != 'base64' or not 0 < metadata['size'] <= 8192:
        raise ValueError('Bounded tracked wrapper properties required')
    data = base64.b64decode(''.join(metadata['content'].split()), validate=True)
    if len(data) != metadata['size']:
        raise ValueError('Wrapper metadata size mismatch')
    return data.decode('ascii')


def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', path], text=True, timeout=30))


def verified_wrapper(prefix, number, base, head, repository_id):
    detail = api(f'{prefix}/pulls/{number}')
    if (not trusted_proposal(detail, repository_id) or detail['head']['sha'] != head
            or detail['base']['sha'] != base or detail['changed_files'] != 1):
        raise ValueError('Exact current Dependabot wrapper-only proposal required')
    files = api(f'{prefix}/pulls/{number}/files?per_page=2')
    if (len(files) != 1 or files[0]['filename'] != WRAPPER_PROPERTIES
            or files[0]['status'] != 'modified' or files[0].get('previous_filename')):
        raise ValueError('Wrapper-only proposal required')
    proposed = wrapper_source(prefix, head)
    version = wrapper_patch(wrapper_source(prefix, base), proposed, official_checksum)
    final = api(f'{prefix}/pulls/{number}')
    if (not trusted_proposal(final, repository_id) or final['head']['sha'] != head
            or final['base']['sha'] != base or api(f'{prefix}/commits/main')['sha'] != base):
        raise ValueError('Proposal or main changed during verification')
    return version, proposed


def main():
    prefix = f'repos/{REPOSITORY}'
    repository = api(prefix)
    upstream = api(f'{prefix}/actions/workflows/sync-upstream.yml')
    runs = api(f'{prefix}/actions/workflows/sync-upstream.yml/runs?event=schedule&per_page=1')['workflow_runs']
    now = dt.datetime.now(dt.timezone.utc)
    age = ((now - dt.datetime.fromisoformat(runs[0]['created_at'])).total_seconds() / 3600
           if runs else None)
    status = ('missing' if age is None else f'{age:.1f}h ago')
    messages = ['## Maintenance watch', '', f'Upstream discovery is {upstream["state"]}. Last schedule {status}.']
    ready = repository['has_pull_requests'] and repository['pull_request_creation_policy'] == 'collaborators_only'
    if not ready:
        raise RuntimeError('Expected collaborator-only maintenance proposals')
    proposals = api(f'{prefix}/pulls?state=open&base=main&per_page=30')
    if len(proposals) == 30:
        messages.append('- Proposal listing capped at 30 pull requests.')
    eligible = []
    for pr in proposals:
        if not trusted_proposal(pr, repository['id']):
            continue
        number = pr['number']
        detail = api(f'{prefix}/pulls/{number}')
        if not trusted_proposal(detail, repository['id']) or detail['head']['sha'] != pr['head']['sha']:
            messages.append(f'- #{number} changed during discovery')
            continue
        files = api(f'{prefix}/pulls/{number}/files?per_page=100') if detail['changed_files'] <= 100 else []
        current = api(f'{prefix}/pulls/{number}')
        if not trusted_proposal(current, repository['id']) or current['head']['sha'] != detail['head']['sha']:
            messages.append(f'- #{number} changed during discovery')
            continue
        head = detail['head']['sha']
        finding = classify_paths(files, detail['changed_files'])
        if detail['changed_files'] == len(files) == 1 and files[0]['filename'] == WRAPPER_PROPERTIES \
                and files[0]['status'] == 'modified' and not files[0].get('previous_filename'):
            base = api(f'{prefix}/commits/main')['sha']
            if base != detail['base']['sha']:
                finding = 'proposal must rebase against current main'
            else:
                try:
                    version, _ = verified_wrapper(prefix, number, base, head, repository['id'])
                except ValueError as error:
                    finding = str(error)
                except OSError:
                    finding = 'official Gradle checksum unavailable'
                else:
                    if base == os.environ['GITHUB_SHA']:
                        eligible.append((number, head))
                        finding = f'Gradle {version} wrapper coordinates verified; acceptance pending'
                    else:
                        finding = 'controller main advanced; wait for the next watch run'
        messages.append(f'- #{number} `{head}`: {finding}')
    messages.append('\nVerified proposals may prepare an isolated branch. Integration and release require acceptance.')
    with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a', encoding='utf-8') as stream:
        stream.write('\n'.join(messages) + '\n')
    if upstream['state'] != 'active' or age is None or age > 8:
        raise RuntimeError('Upstream discovery schedule is inactive or overdue')
    if len(eligible) == 1:
        with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8') as stream:
            number, head = eligible[0]
            stream.write(f'proposal={number}\nproposal_head={head}\n')


if __name__ == '__main__':
    main()
