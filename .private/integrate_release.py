"""Prepare a release state commit from a trusted publication attestation."""
import json
import os
from pathlib import Path
import subprocess
import tempfile

from acceptance_policy import verify_promotion_revision
from publish import read_attestation, release_title, require_current_controller, verify_release_identity

RECOVERY_CONTROLS = {
    '.github/workflows/build-release.yml', '.github/workflows/verify-public-release.yml',
    '.private/integrate_release.py', '.private/reconcile_release.py',
    '.private/verify_public_release.py', '.private/test_publish.py', '.private/MAINTENANCE.md',
}


def integration_base(candidate, controller, original, root):
    late = verify_promotion_revision(candidate, original, root)
    if original == controller:
        return controller if late else candidate

    def git(*args):
        return subprocess.check_output(['git', *args], cwd=root, timeout=30)

    # A published candidate already present on main can be recorded after an
    # explicitly reviewed recovery-only controller update.
    git('merge-base', '--is-ancestor', candidate, original)
    git('merge-base', '--is-ancestor', original, controller)
    changes = git('diff', '--no-renames', '--name-only', '-z', original, controller)
    if set(filter(None, changes.decode().split('\0'))) - RECOVERY_CONTROLS:
        raise RuntimeError('Main changed outside publication recovery controls')
    return controller


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
    if attestation.get('maintenance', {}).get('kind') == 'custom':
        from custom_release import MARKER

        git('update-index', '--force-remove', '--', MARKER)
    for path, value in ((evidence, attestation), ('.private/upstream.json', attestation['upstream'])):
        blob = git('hash-object', '-w', '--stdin', data=(json.dumps(value, indent=2) + '\n').encode())
        git('update-index', '--add', '--cacheinfo', f'100644,{blob},{path}')
    tree = git('write-tree')
    return git('-c', 'user.name=ZEC Private automation', '-c', 'user.email=zec-private@users.noreply.github.com',
               'commit-tree', tree, '-p', base, '-m', 'Record verified release ' + tag)


def recovered_candidate_base(candidate, original, controller, root):
    from quote_recovery import verify_delta

    verify_delta(root, candidate, original, controller)
    tree = subprocess.check_output(['git', 'merge-tree', '--write-tree', controller, candidate],
                                   cwd=root, text=True, timeout=30).strip()
    return subprocess.check_output(
        ['git', '-c', 'user.name=ZEC Private automation', '-c', 'user.email=zec-private@users.noreply.github.com',
         'commit-tree', tree, '-p', controller, '-p', candidate, '-m', 'Integrate recovered validated candidate'],
        cwd=root, text=True, timeout=30).strip()


def main():
    runner_temp = Path(os.environ['RUNNER_TEMP'])
    proof = read_attestation(runner_temp / 'publication/release-attestation.json', os.environ['ATTESTATION_DIGEST'])
    controller = os.environ['GITHUB_SHA']
    original = os.environ['ATTESTATION_CONTROLLER']
    if proof['controller_sha'] != original:
        raise RuntimeError('Publication attestation belongs to a different controller')
    require_current_controller(controller)
    candidate = proof['source_sha']
    root = Path(__file__).resolve().parents[1]
    recovery = proof.get('quote_recovery')
    if recovery and controller == original:
        base = recovered_candidate_base(candidate, recovery['original_controller'], controller, root)
    else:
        base = integration_base(candidate, controller, original, root)
    verify_release_identity(proof, candidate)
    with tempfile.TemporaryDirectory(dir=runner_temp) as scratch:
        commit = state_commit(base, proof, Path(scratch), root)
    with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8') as output:
        output.write(f'commit={commit}\n')


if __name__ == '__main__':
    main()
