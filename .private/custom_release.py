"""Authorize an exact hosted custom candidate through the protected dispatch."""
import json
import os
from pathlib import Path
import re

from maintenance_cadence import changed_marker, git
from release_inputs import version_code
from release_metadata import require

MARKER = '.private/custom-candidate.json'
WORKFLOW = '.github/workflows/custom-release.yml'
REPOSITORY = 'Codely-Solutions/zec-private'
OPERATOR_ID = 63964062  # Sammy-Lastre


def verify_custom_child(source, controller, root):
    from acceptance_policy import verify_canonical_patch

    require(git(root, 'rev-list', '--parents', '-n', '1', source).decode().split() == [source, controller],
            'Custom release requires one reviewed commit directly on the current controller')
    require(changed_marker(root, source, controller, MARKER) == {'kind': 'custom', 'controller': controller},
            'Expected the exact custom candidate marker')
    paths = git(root, 'diff', '--no-renames', '--name-only', '-z', controller, source,
                '--', '.github', '.private', 'LICENSE', '.gitattributes').decode().split('\0')
    allowed = {MARKER, '.private/fork.patch', '.private/source-state.json',
               '.private/release-revision.json', '.private/candidate.json', '.private/release-notes.json'}
    require(not (set(filter(None, paths)) - allowed), 'Custom candidate changed controller-owned files')
    require(not git(root, 'ls-tree', '--name-only', source, '--', '.private/candidate.json'),
            'Custom release cannot carry an upstream candidate marker')

    def read(revision, path):
        return git(root, 'show', f'{revision}:{path}')

    upstream = json.loads(read(controller, '.private/upstream.json'))
    previous = json.loads(read(controller, '.private/release-revision.json'))['private_revision']
    revised = json.loads(read(source, '.private/release-revision.json'))
    properties = read(source, 'gradle.properties')
    codes = re.findall(rb'^ZCASH_VERSION_CODE=(\d+)$', properties, re.M)
    names = re.findall(rb'^ZCASH_VERSION_NAME=(.+)$', properties, re.M)
    require(set(revised) == {'private_revision'} and type(revised['private_revision']) is int
            and previous < revised['private_revision'] <= 999 and len(codes) == 1
            and max(upstream['versionCode'], version_code(root, controller)) < int(codes[0]) <= 2_100_000_000
            and names == [upstream['tag'].rsplit('-', 1)[0].encode()], 'Invalid custom release version')
    verify_canonical_patch(source, controller, upstream['sha'], root)


def require_dispatch(source, controller, metadata, *, publish, recover_run='', quote_run='', preflight_only=False,
                     preclaim_run=''):
    require(os.environ.get('GITHUB_REPOSITORY') == REPOSITORY
            and os.environ.get('GITHUB_REF') == 'refs/heads/main'
            and os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch', 'Expected a protected main dispatch')
    require(re.fullmatch(r'[a-f0-9]{40}', source) and re.fullmatch(r'[a-f0-9]{40}', controller),
            'Exact custom candidate and controller SHAs required')
    run_id = os.environ['GITHUB_RUN_ID']
    require(re.fullmatch(r'[1-9][0-9]*', run_id), 'Exact GitHub dispatch run required')
    run = metadata.api('/actions/runs/' + run_id)
    require(run['id'] == int(run_id) and run['path'] == WORKFLOW and run['event'] == 'workflow_dispatch'
            and run['head_branch'] == 'main' and run['head_sha'] == controller
            and run['head_repository']['full_name'] == REPOSITORY
            and run['actor']['id'] == OPERATOR_ID, 'Expected the authorized custom release operator and workflow')
    inputs = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_bytes())['inputs']
    operation = 'recover' if recover_run else ('publish' if publish else ('preflight' if preflight_only else 'check'))
    require(inputs.get('source_sha') == source and inputs.get('operation') == operation
            and inputs.get('recover_run', '') == recover_run and inputs.get('quote_run', '') == quote_run
            and inputs.get('preclaim_run', '') == preclaim_run,
            'Dispatch does not authorize this exact custom candidate and operation')
    require(metadata.api('/git/ref/heads/main')['object']['sha'] == controller,
            'Protected main advanced; prepare and review a new candidate')
