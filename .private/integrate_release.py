"""Prepare a release state commit from a trusted publication attestation."""
import json
import os
from pathlib import Path
import subprocess
import tempfile

from acceptance_policy import verify_promotion_revision
from publish import read_attestation, release_title, require_current_controller, verify_release_identity


def state_commit(base, attestation, scratch, root):
    tag = attestation['release_tag']
    release_title(tag)
    evidence = f'.private/release-evidence/{tag}.json'
    env = dict(os.environ, GIT_INDEX_FILE=str(scratch / 'index'))

    def git(*args, data=None):
        return subprocess.check_output(['git', *args], cwd=root, env=env, input=data).decode().strip()

    if git('ls-tree', '--name-only', base, '--', evidence):
        raise RuntimeError('Refusing to replace an existing release evidence record')
    git('read-tree', base)
    for path, value in ((evidence, attestation), ('.private/upstream.json', attestation['upstream'])):
        blob = git('hash-object', '-w', '--stdin', data=(json.dumps(value, indent=2) + '\n').encode())
        git('update-index', '--add', '--cacheinfo', f'100644,{blob},{path}')
    tree = git('write-tree')
    return git('-c', 'user.name=ZEC Private automation', '-c', 'user.email=zec-private@users.noreply.github.com',
               'commit-tree', tree, '-p', base, '-m', 'Record verified release ' + tag)


def main():
    runner_temp = Path(os.environ['RUNNER_TEMP'])
    proof = read_attestation(runner_temp / 'publication/release-attestation.json', os.environ['ATTESTATION_DIGEST'])
    controller = os.environ['GITHUB_SHA']
    if proof['controller_sha'] != controller:
        raise RuntimeError('Publication attestation belongs to a different controller')
    require_current_controller(controller)
    candidate = proof['source_sha']
    root = Path(__file__).resolve().parents[1]
    late = verify_promotion_revision(candidate, controller, root)
    verify_release_identity(proof, candidate)
    with tempfile.TemporaryDirectory(dir=runner_temp) as scratch:
        commit = state_commit(controller if late else candidate, proof, Path(scratch), root)
    with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8') as output:
        output.write(f'commit={commit}\n')


if __name__ == '__main__':
    main()
