"""Prepare a verified Gradle wrapper patch without running proposal code."""
import json
import os
from pathlib import Path
import re
import subprocess

from maintenance_watch import REPOSITORY, WRAPPER_PROPERTIES, api, verified_wrapper
from release_metadata import Metadata, next_release_version, require, wallet_release_versions
from refresh_source import main as refresh_source
from sync import version


ROOT = Path(__file__).resolve().parents[1]


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT, text=True, timeout=45).strip()


def prepare(number, head, controller):
    require(os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('GITHUB_REF') == 'refs/heads/main'
            and os.environ.get('GITHUB_REPOSITORY') == REPOSITORY,
            'Preparation requires the hosted main controller')
    require(re.fullmatch(r'[1-9][0-9]*', number)
            and all(re.fullmatch(r'[0-9a-f]{40}', value) for value in (head, controller)),
            'Exact proposal and controller identities required')
    require(git('rev-parse', 'HEAD') == controller and not git('status', '--porcelain'),
            'Clean exact controller checkout required')
    metadata = Metadata(repo=REPOSITORY)
    repo_id = metadata.api('')['id']
    release, properties = verified_wrapper(metadata.repo, number, controller, head, repo_id)
    revision_path = ROOT / '.private/release-revision.json'
    state = json.loads((ROOT / '.private/upstream.json').read_text())
    revisions = wallet_release_versions(metadata.api('/releases?per_page=100', paginated=True))
    revision, code = next_release_version(revisions, json.loads(revision_path.read_text())['private_revision'],
                                         state['versionCode'], version(state['tag'])[3])
    app_properties = ROOT / 'gradle.properties'
    content, changed = re.subn(r'^ZCASH_VERSION_CODE=[0-9]+$', f'ZCASH_VERSION_CODE={code}',
                               app_properties.read_text(), flags=re.M)
    require(changed == 1, 'Expected one existing Android versionCode')
    app_properties.write_text(content)
    (ROOT / WRAPPER_PROPERTIES).write_text(properties)
    revision_path.write_text(json.dumps({'private_revision': revision}) + '\n')
    # A historical upstream candidate marker is not this dependency proposal.
    (ROOT / '.private/candidate.json').unlink(missing_ok=True)
    identity = dict(proposal=int(number), proposal_head=head, controller_sha=controller,
                    kind='gradle-wrapper-patch', gradle=release, version_code=code, private_revision=revision)
    (ROOT / '.private/maintenance-candidate.json').write_text(json.dumps(identity, indent=2) + '\n')
    git('add', '-A')
    refresh_source()
    git('add', '.private/fork.patch', '.private/source-state.json')
    branch = f'updates/gradle-{release}-{controller[:12]}-{head[:12]}'
    tree = git('write-tree')
    remote = git('ls-remote', 'origin', f'refs/heads/{branch}')
    if remote:
        source = remote.split()[0]
        git('fetch', '--no-tags', 'origin', source)
        require(git('rev-parse', source + '^{tree}') == tree and git('rev-parse', source + '^') == controller,
                'Existing maintenance branch differs from the verified proposal')
    else:
        source = git('-c', 'user.name=ZEC Private automation',
                     '-c', 'user.email=zec-private@users.noreply.github.com', 'commit-tree', tree, '-p', controller,
                     '-m', f'Prepare Gradle {release} maintenance update')
        current = api(f'{metadata.repo}/pulls/{number}')
        require(current['state'] == 'open' and current['head']['sha'] == head
                and api(f'{metadata.repo}/commits/main')['sha'] == controller,
                'Proposal or main changed before staging')
        subprocess.run(['gh', 'auth', 'setup-git', '--hostname', 'github.com'], check=True, timeout=15)
        git('push', 'origin', f'{source}:refs/heads/{branch}')
    print(json.dumps(dict(identity, source_sha=source, branch=branch)))
    return source


if __name__ == '__main__':
    source = prepare(os.environ['PROPOSAL'], os.environ['PROPOSAL_HEAD'], os.environ['GITHUB_SHA'])
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as stream:
        stream.write(f'source_sha={source}\n')
