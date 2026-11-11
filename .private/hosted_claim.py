"""Create an atomic, durable one-claim reservation for a hosted lifecycle."""
import json
import os
import re
import subprocess
from urllib.parse import quote

from release_metadata import Metadata, require

PREFIX = 'refs/heads/testnet-claims/'


def binding(source, controller, version, run, rehearsal):
    require(all(re.fullmatch(r'[a-f0-9]{40}', sha) for sha in (source, controller)),
            'Exact source and controller required')
    require(type(version) is int and 0 < version <= 2_100_000_000
            and type(run) is int and run > 0 and type(rehearsal) is bool,
            'Exact version, run and mode required')
    return dict(source=source, controller=controller, version=version, run=run, rehearsal=rehearsal)


def ref_for(value):
    mode = 'rehearsal' if value['rehearsal'] else 'release'
    return PREFIX + f'{mode}-{value["version"]}'


def reserve(value, metadata, post):
    ref = ref_for(value)
    controller = value['controller']
    require(metadata.api('/git/ref/heads/main')['object']['sha'] == controller,
            'Main advanced before claim reservation')
    tree = metadata.api('/git/commits/' + controller)['tree']['sha']
    commit = post('/git/commits', dict(message=json.dumps(value, sort_keys=True),
                                       tree=tree, parents=[controller]))['sha']
    # POST creates the ref atomically. Existing refs and ambiguous responses
    # never grant permission, even if the existing binding names this run.
    created = post('/git/refs', dict(ref=ref, sha=commit))
    require(created['ref'] == ref and created['object']['sha'] == commit,
            'Claim reservation identity mismatch')
    return commit


def verify(value, commit, metadata):
    require(re.fullmatch(r'[a-f0-9]{40}', commit), 'Exact claim reservation required')
    live = metadata.api('/git/ref/' + ref_for(value).removeprefix('refs/'))
    require(live['object']['sha'] == commit
            and json.loads(metadata.api('/git/commits/' + commit)['message']) == value,
            'Claim reservation belongs to different inputs')
    branch = quote(ref_for(value).removeprefix('refs/heads/'), safe='')
    rules = metadata.api('/rules/branches/' + branch)
    require({'update', 'deletion'} <= {rule['type'] for rule in rules},
            'Claim reservation is missing its update and deletion protections')


def main():
    from acceptance_policy import ROOT, verify_promotion_revision
    from release_inputs import version_code

    require(os.environ['GITHUB_REF'] == 'refs/heads/main' and os.environ['GITHUB_RUN_ATTEMPT'] == '1',
            'Only the initial trusted main run may reserve funding')
    controller = os.environ['GITHUB_SHA']
    rehearsal = os.environ['REHEARSAL'] == 'true'
    if rehearsal:
        require(os.environ['GITHUB_EVENT_NAME'] == 'workflow_dispatch' and
                os.environ['GITHUB_WORKFLOW_REF'].endswith('/.github/workflows/hosted-core.yml@refs/heads/main'),
                'Rehearsals require the dedicated hosted workflow')
    source = os.environ.get('SOURCE_SHA') or controller
    verify_promotion_revision(source, controller, ROOT)
    base = json.loads((ROOT / '.private/hosted-fixture.json').read_text())['version_code']
    version = base + 1 if rehearsal else version_code(ROOT, source)
    require(version > base and (not rehearsal or source == controller), 'Expected an advancing testnet candidate')
    value = binding(source, controller, version, int(os.environ['GITHUB_RUN_ID']), rehearsal)
    metadata = Metadata(repo=os.environ['GITHUB_REPOSITORY'])

    def post(path, data):
        return json.loads(subprocess.check_output(
            [metadata.gh, 'api', '-X', 'POST', metadata.repo + path, '--input', '-'],
            input=json.dumps(data), text=True, timeout=30))

    commit = reserve(value, metadata, post)
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as stream:
        stream.write(f'reservation={commit}\nversion={version}\n')


if __name__ == '__main__':
    main()
