"""Trusted acceptance requirements, reusable input bindings and strict receipts.

No candidate modules are imported by the signing controller. Hashes identify
normalized tracked bytes; APK digests identify the binaries actually executed.
"""
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
POLICY_VERSION = 1
CORE_STAGES = {'fund', 'upgrade', 'send', 'restart', 'restore', 'interruption'}


def git(*args, root=ROOT):
    return subprocess.check_output(['git', *args], cwd=root)


def relevant(path):
    # Docs/receipts cannot change app behavior. Build, source, native and test
    # inputs remain included; unknown retained paths fail conservatively.
    if path.startswith(('.private/', '.github/')):
        return path.startswith(('.private/core-wallet/',)) or path in {
            '.private/acceptance_policy.py', '.private/core_testnet_driver.py',
            '.private/run_core_emulator.py', '.private/core-network-policy.json',
            '.private/tor-ui-abi.gradle', '.github/workflows/core-validation.yml'}
    return not (path in {'README.md', 'LICENSE', '.gitignore', '.gitattributes'} or
                path.endswith(('.md', '.png', '.jpg', '.webp', '.svg')))


def input_fingerprint(revision='HEAD', root=ROOT):
    # One bounded Git inventory. Blob IDs refer to canonical committed bytes,
    # independent of Windows checkout line endings; no subprocess per file.
    rows = git('ls-tree', '-r', '-z', revision, root=root).decode().split('\0')
    entries = {}
    for row in rows:
        if not row:
            continue
        metadata, path = row.split('\t', 1)
        if relevant(path):
            entries[path] = metadata
    return hashlib.sha256(json.dumps(entries, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def requirements(paths):
    profiles = set()
    reasons = []
    for path in paths:
        if path.startswith(('.private/', '.github/')):
            if relevant(path) or path in {'.private/proof.py', '.private/publish.py', '.private/promotion.py'}:
                profiles.add('core-testnet')
                reasons.append(path)
            continue
        if not relevant(path):
            continue
        if '/src/main/res/' in path and path.endswith('.xml'):
            # Resources affect navigation, validation and network flavor overlays.
            profiles.add('core-testnet')
        elif '/screen/swap/' in path or any(word in path for word in ('Near', 'SwapQuote', 'SwapRepository')):
            profiles.add('production-quotes')
        elif '/src/test/' in path and not any(word in path.lower() for word in ('wallet', 'tor', 'swap', 'send', 'restore')):
            continue
        else:
            profiles.add('core-testnet')
        if any(word in path.lower() for word in ('tor', 'httpclient', 'synchronizer', '/di/', 'navigation')):
            profiles.add('production-quotes')
        reasons.append(path)
    return {'policy_version': POLICY_VERSION, 'profiles': sorted(profiles), 'reasons': reasons}


def validate_core(receipt, expected_inputs):
    if receipt.get('schema') != POLICY_VERSION or receipt.get('input_fingerprint') != expected_inputs:
        raise ValueError('Core acceptance inputs/policy mismatch')
    if receipt.get('evidence_class') != 'real-app-testnet' or receipt.get('network') != 'zcash-testnet':
        raise ValueError('Real testnet app evidence required')
    if receipt.get('passed') is not True or receipt.get('clearnet_fallback') is not False:
        raise ValueError('Core acceptance failed or transport not established')
    if receipt.get('personal_wallet_access') is not False or receipt.get('real_funds_used') is not False:
        raise ValueError('Unsafe wallet evidence')
    stages = receipt.get('stages', {})
    if set(stages) != CORE_STAGES or any(v.get('passed') is not True for v in stages.values()):
        raise ValueError('Missing required core acceptance stage')
    if not receipt.get('network_enforcement', {}).get('passed'):
        raise ValueError('Missing fail-closed connection evidence')
    for key in ('candidate_apk_sha256', 'baseline_apk_sha256', 'instrumentation_apk_sha256', 'harness_sha256'):
        if not re.fullmatch('[0-9a-f]{64}', str(receipt.get(key, ''))):
            raise ValueError('Missing executed artifact/harness digest: ' + key)
    if not re.fullmatch('[0-9a-f]{40}', str(receipt.get('build_source_sha', ''))):
        raise ValueError('Missing build source identity')
    if receipt.get('baseline_source_sha') != 'a0bd21f27bb53af0eeb9b433d0273c6f39b04e19':
        raise ValueError('Unexpected migration baseline')
    if receipt.get('candidate_version_code', 0) <= receipt.get('baseline_version_code', 0):
        raise ValueError('Update did not increase versionCode')
    txids = {stages[name].get('txId') for name in ('send', 'restart', 'restore')}
    if len(txids) != 1 or not re.fullmatch('[0-9a-f]{64}', str(next(iter(txids)))):
        raise ValueError('Transaction identity not preserved through restart/restore')
    return True


if __name__ == '__main__':
    print(json.dumps({'input_fingerprint': input_fingerprint()}, sort_keys=True))
