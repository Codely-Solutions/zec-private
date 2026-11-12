"""Select the signing smoke check from protected controller inputs."""
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path

from release_metadata import Metadata


PATHS = (
    '.github/workflows/build-release.yml',
    '.github/workflows/custom-release.yml',
    '.github/workflows/sync-upstream.yml',
    '.github/workflows/verify-signing-environment.yml',
    '.private/publish.py',
    '.private/signing_wiring.py',
    '.private/verify_signing_key.sh',
)


def environment_wiring(metadata):
    path = '/environments/production-signing'
    environment = metadata.api(path)
    policies = metadata.pages(path + '/deployment-branch-policies', 'branch_policies')
    if (environment['deployment_branch_policy'] != {'protected_branches': False, 'custom_branch_policies': True}
            or [(policy['type'], policy['name']) for policy in policies] != [('branch', 'main')]):
        raise RuntimeError('Production signing must be restricted to the main branch only')
    return {'environment': environment['id'], 'rules': environment['protection_rules'], 'branches': policies}


def fingerprint(wiring):
    tree = subprocess.check_output(['git', 'ls-tree', '-z', 'HEAD', '--', *PATHS], timeout=15)
    if tree.count(b'\0') != len(PATHS):
        raise RuntimeError('Signing wiring is incomplete')
    settings = json.dumps(wiring, sort_keys=True, separators=(',', ':')).encode()
    return hashlib.sha256(tree + settings).hexdigest()


def main():
    digest = fingerprint(environment_wiring(Metadata()))
    baseline = json.loads(Path('.private/signing-smoke.json').read_bytes())
    changed = baseline is None or baseline['fingerprint'] != digest
    if baseline is not None and (not re.fullmatch('[a-f0-9]{64}', baseline['fingerprint'])
                                 or type(baseline['run_id']) is not int or baseline['run_id'] < 1):
        raise RuntimeError('Invalid verified signing smoke record')
    print(f'Signing wiring SHA-256: {digest}')
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
        output.write(f'changed={str(changed).lower()}\n')


if __name__ == '__main__':
    main()
