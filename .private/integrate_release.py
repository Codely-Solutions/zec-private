"""Prepare a release state commit from a trusted publication attestation."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

from acceptance_policy import verify_promotion_revision
from publish import (current_main, read_attestation, release_title, require_current_controller,
                     verify_attestation_publisher, verify_release_identity)

RECOVERY_CONTROLS = {
    '.github/workflows/build-release.yml', '.github/workflows/verify-public-release.yml',
    '.private/integrate_release.py', '.private/reconcile_release.py',
    '.github/workflows/automation-test.yml', '.github/workflows/maintenance-watch.yml',
    '.github/workflows/sync-upstream.yml', '.github/workflows/sync-upstream-main.yml',
    '.private/maintenance_watch.py', '.private/test_maintenance_watch.py',
    '.private/release_metadata.py', '.private/publish.py', '.private/signing_wiring.py',
    '.private/verify_public_release.py', '.private/test_publish.py', '.private/MAINTENANCE.md',
    '.private/RELEASE-WORKFLOW.md', '.private/notify.py', '.private/test_notify.py',
}


def integration_base(candidate, controller, original, root, *, recovery=None, funded=False):
    def git(*args):
        return subprocess.check_output(['git', *args], cwd=root, timeout=30)

    build_controller = recovery['original_controller'] if recovery else original
    if build_controller == original:
        late = verify_promotion_revision(candidate, original, root)
        if controller == original:
            return controller if late else candidate
    else:
        from quote_recovery import verify_delta

        verify_delta(root, candidate, build_controller, original, funded=funded,
                     preclaim_stage=recovery.get('preclaim_diagnostic'))
        late = False

    if controller != original:
        git('merge-base', '--is-ancestor', original, controller)
        changes = git('diff', '--no-renames', '--name-only', '-z', original, controller)
        if set(filter(None, changes.decode().split('\0'))) - RECOVERY_CONTROLS:
            raise RuntimeError('Main changed outside publication recovery controls')
    if late or candidate == build_controller:
        return controller
    if subprocess.run(['git', 'merge-base', '--is-ancestor', candidate, controller], cwd=root,
                      timeout=30, check=False).returncode == 0:
        return controller
    tree = git('merge-tree', '--write-tree', controller, candidate).decode().strip()
    return git('-c', 'user.name=ZEC Private automation', '-c', 'user.email=zec-private@users.noreply.github.com',
               'commit-tree', tree, '-p', controller, '-p', candidate,
               '-m', 'Integrate verified release candidate').decode().strip()


def evidence_path(tag):
    release_title(tag)
    return f'.private/release-evidence/{tag}.json'


def evidence_bytes(value):
    return (json.dumps(value, indent=2) + '\n').encode()


def already_integrated(proof, controller, root):
    def git(*args):
        return subprocess.check_output(['git', *args], cwd=root, timeout=30)

    evidence = evidence_path(proof['release_tag'])
    record = git('ls-tree', controller, '--', evidence)
    if not record:
        return False
    if not record.startswith(b'100644 blob ') or git('show', f'{controller}:{evidence}') != evidence_bytes(proof):
        raise RuntimeError('Existing release evidence conflicts with the verified publication attestation')
    for revision in (proof['controller_sha'], proof['source_sha']):
        git('merge-base', '--is-ancestor', revision, controller)
    return True


def state_commit(base, attestation, scratch, root):
    tag = attestation['release_tag']
    evidence = evidence_path(tag)
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
        blob = git('hash-object', '-w', '--stdin', data=evidence_bytes(value))
        git('update-index', '--add', '--cacheinfo', f'100644,{blob},{path}')
    tree = git('write-tree')
    return git('-c', 'user.name=ZEC Private automation', '-c', 'user.email=zec-private@users.noreply.github.com',
               'commit-tree', tree, '-p', base, '-m', 'Record verified release ' + tag)


def push_integration(commit):
    if not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise ValueError('Expected the exact prepared integration commit')
    try:
        subprocess.run(['git', 'push', 'origin', f'{commit}:refs/heads/main'], check=True, timeout=90)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
        if current_main() != commit:
            raise
        print('Protected main contains the exact prepared integration commit.')


def main(root=Path(__file__).resolve().parents[1]):
    runner_temp = Path(os.environ['RUNNER_TEMP'])
    proof = read_attestation(runner_temp / 'publication/release-attestation.json', os.environ['ATTESTATION_DIGEST'])
    controller = os.environ['GITHUB_SHA']
    original = os.environ['ATTESTATION_CONTROLLER']
    if proof['controller_sha'] != original:
        raise RuntimeError('Publication attestation belongs to a different controller')
    verify_attestation_publisher(proof, os.environ['ATTESTATION_RUN'], os.environ['ATTESTATION_ATTEMPT'])
    require_current_controller(controller)
    candidate = proof['source_sha']
    if not re.fullmatch(r'[0-9a-f]{40}', candidate):
        raise ValueError('Expected the exact attested source revision')
    verify_release_identity(proof, candidate)
    recorded = already_integrated(proof, controller, root)
    if recorded:
        commit = controller
        print('Verified publication is already integrated on protected main.')
    else:
        base = integration_base(candidate, controller, original, root, recovery=proof.get('quote_recovery'),
                                funded=bool(proof.get('funded_reuse')))
        with tempfile.TemporaryDirectory(dir=runner_temp) as scratch:
            commit = state_commit(base, proof, Path(scratch), root)
    with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8', newline='\n') as output:
        output.write(f'commit={commit}\nalready_integrated={str(recorded).lower()}\n')


if __name__ == '__main__':
    if sys.argv[1:] == ['--push']:
        push_integration(os.environ['INTEGRATION_COMMIT'])
    else:
        main()
