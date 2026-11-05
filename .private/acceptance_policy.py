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
            '.private/run_tor_emulator.py', '.private/admit_core_emulator.py', '.private/core-network-policy.json',
            '.private/tor_differential_driver.py', '.private/strip_emulator_apk.py',
            '.private/tor-ui-abi.gradle', '.github/workflows/core-validation.yml'}
    if path.startswith(('.idea/','docs/')) or '/src/test/' in path or '/src/androidTest/' in path:
        return False
    if '/screen/theme/' in path or '/design/theme/' in path:
        return False
    return not (path in {'README.md', 'LICENSE', '.gitignore', '.gitattributes'} or
                path.endswith(('.md', '.png', '.jpg', '.webp', '.svg')))


def input_fingerprint(revision='HEAD', root=ROOT, profile='core-testnet'):
    # One bounded Git inventory. Blob IDs refer to canonical committed bytes,
    # independent of Windows checkout line endings; no subprocess per file.
    rows = git('ls-tree', '-r', '-z', revision, root=root).decode().split('\0')
    entries = {}
    for row in rows:
        if not row:
            continue
        metadata, path = row.split('\t', 1)
        selected=relevant(path)
        if profile=='production-quotes' and path.startswith(('.private/','.github/')):
            selected=False
        if profile=='core-build' and path.startswith(('.private/','.github/')):
            selected=path.startswith('.private/core-wallet/') or path in {
                '.private/tor-ui-abi.gradle','.github/workflows/core-validation.yml'}
        if selected:
            entries[path] = metadata
    # Version increments select an update target, not a different wallet engine.
    # All other Gradle properties (identity, SDK and behavior switches) stay bound.
    if 'gradle.properties' in entries:
        properties=git('show',f'{revision}:gradle.properties',root=root).decode().replace('\r\n','\n')
        properties='\n'.join(line for line in properties.splitlines() if not line.startswith(('ZCASH_VERSION_CODE=','ZCASH_VERSION_NAME=')))
        entries['gradle.properties']=hashlib.sha256(properties.encode()).hexdigest()
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
        if '/src/test/' in path or '/src/androidTest/' in path:
            profiles.add('deterministic-regressions')
            reasons.append(path)
            continue
        if not relevant(path):
            continue
        if '/src/main/res/' in path and path.endswith('.xml'):
            # Resources affect navigation, validation and network flavor overlays.
            profiles.add('core-testnet')
        elif '/screen/swap/' in path or any(word in path for word in ('Near', 'SwapQuote', 'SwapRepository')):
            profiles.add('production-quotes')
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
    recovery=stages['interruption']
    if (not all(recovery.get(key) is True for key in ('sync_restarted','synced','balance_preserved','history_preserved')) or
            recovery.get('blocked_packets',0)<=0 or recovery.get('scanned_height',0)<=0):
        raise ValueError('Actual wallet synchronization interruption/recovery required')
    if (not re.fullmatch('[0-9a-f]{64}',str(stages['fund'].get('funding_txid',''))) or
            stages['fund'].get('balance_zatoshi',0)<=0 or stages['send'].get('fee_zatoshi',0)<=0 or
            stages['restore'].get('mined_height',0)<=0 or stages['restore'].get('amount_zatoshi')!=1000000):
        raise ValueError('Missing real transaction accounting/confirmation evidence')
    for key in ('candidate_apk_sha256', 'baseline_apk_sha256', 'instrumentation_apk_sha256', 'harness_sha256'):
        if not re.fullmatch('[0-9a-f]{64}', str(receipt.get(key, ''))):
            raise ValueError('Missing executed artifact/harness digest: ' + key)
    if not re.fullmatch('[0-9a-f]{64}',str(receipt.get('build_input_fingerprint',''))):
        raise ValueError('Missing relevant executed-build input fingerprint')
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


def validate_quotes(receipt, expected_inputs):
    if (receipt.get('schema')!=POLICY_VERSION or receipt.get('input_fingerprint')!=expected_inputs or
            receipt.get('evidence_class')!='real-app-production-quotes' or receipt.get('passed') is not True or
            receipt.get('tor_enabled') is not True or receipt.get('funds_transferred') is not False or
            receipt.get('personal_wallet_access') is not False):
        raise ValueError('Missing real unfunded production quote acceptance')
    if min(receipt.get(key,0) for key in ('quote_requests','quote_reviews','dismissals'))<3:
        raise ValueError('Repeated quote/dismiss path not established')
    if not re.fullmatch('[0-9a-f]{64}',str(receipt.get('executed_apk_sha256',''))):
        raise ValueError('Missing executed quote artifact digest')
    if not re.fullmatch('[0-9a-f]{40}',str(receipt.get('build_source_sha',''))):
        raise ValueError('Missing executed quote source')
    return True


def validate_binding(proof, profile, expected_inputs, trusted_bytes, artifact_bytes):
    bound=proof.get(profile,{})
    if (bound.get('status')!='passed' or bound.get('input_fingerprint')!=expected_inputs or
            bound.get('receipt_sha256')!=hashlib.sha256(trusted_bytes).hexdigest() or
            artifact_bytes!=trusted_bytes):
        raise ValueError('Missing or mismatched trusted acceptance: '+profile)


if __name__ == '__main__':
    print(json.dumps({'input_fingerprint': input_fingerprint()}, sort_keys=True))
