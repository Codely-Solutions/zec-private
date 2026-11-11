"""Read-only maintenance discovery. A shadow finding grants no promotion authority."""
import datetime as dt
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess


REPOSITORY = 'Codely-Solutions/zec-private'
CONTROL_ROOTS = {'.github', '.private'}
DEPENDENCY_FILES = {'gradle.properties', 'gradle/verification-metadata.xml',
                    'gradle/wrapper/gradle-wrapper.properties', 'gradle/wrapper/gradle-wrapper.jar'}


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


def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', path], text=True, timeout=30))


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
        messages.append(f'- #{number} `{detail["head"]["sha"]}`: {classify_paths(files, detail["changed_files"])}')
    messages.append('\nShadow mode. This workflow does not merge or release.')
    with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a', encoding='utf-8') as stream:
        stream.write('\n'.join(messages) + '\n')
    if upstream['state'] != 'active' or age is None or age > 8:
        raise RuntimeError('Upstream discovery schedule is inactive or overdue')


if __name__ == '__main__':
    main()
