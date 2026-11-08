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
CORE_TEST_SOURCE = '.private/core-wallet/src/co/electriccoin/zcash/app/CoreWalletAcceptance.kt'
PROFILES = {'core-testnet', 'core-build', 'production-quotes', 'core-runtime'}
CORE_BINDING = 'core-runtime-v1'
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
            '.private/emulator_session.py', '.private/emulator_batch.py', '.private/admit_emulator_batch.py',
            '.private/tor_differential_driver.py', '.private/strip_emulator_apk.py',
            '.private/core_faucet.py', '.private/upgrade-baseline.json',
            '.private/tor-ui-abi.gradle', '.github/workflows/core-validation.yml'}
    if path.startswith(('.idea/','docs/')) or '/src/test/' in path or '/src/androidTest/' in path:
        return False
    if any(part in path for part in ('/screen/theme/','/design/theme/','/screen/about/','/screen/whatsnew/',
                                     '/res/ui/about/','/res/ui/whatsnew/','/res/ui/theme/')):
        return False
    return not (path in {'README.md', 'LICENSE', '.gitignore', '.gitattributes'} or
                path.endswith(('.md', '.png', '.jpg', '.webp', '.svg')))


def input_fingerprint(revision='HEAD', root=ROOT, profile='core-testnet'):
    # One bounded Git inventory. Blob IDs refer to canonical committed bytes,
    # independent of Windows checkout line endings; no subprocess per file.
    if profile not in PROFILES:
        raise ValueError('Unknown acceptance profile: '+profile)
    rows = git('ls-tree', '-r', '-z', revision, root=root).decode().split('\0')
    entries = {}
    test_source_metadata = None
    for row in rows:
        if not row:
            continue
        metadata, path = row.split('\t', 1)
        if path == CORE_TEST_SOURCE:
            test_source_metadata = metadata
        selected=relevant(path)
        if profile=='core-runtime' and path==CORE_TEST_SOURCE:
            selected=False
        if profile=='production-quotes' and path.startswith(('.private/','.github/')):
            selected=False
        if profile=='core-build' and path.startswith(('.private/','.github/')):
            selected=path.startswith('.private/core-wallet/') or path in {
                '.private/tor-ui-abi.gradle','.github/workflows/core-validation.yml'}
        if selected:
            entries[path] = metadata
    if profile=='core-runtime' and not (test_source_metadata or '').startswith('100644 blob '):
        raise ValueError('Dedicated instrumentation must remain a normal tracked source file')
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
        elif (path.startswith('wallet-foundation-lib/') or '/screen/swap/' in path or
              any(word in path for word in ('Near', 'SwapQuote', 'SwapRepository', 'ZecSwapComposition'))):
            profiles.add('production-quotes')
            if path.startswith('wallet-foundation-lib/'):
                profiles.add('core-testnet')
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
    enforcement=receipt['network_enforcement']
    if enforcement.get('metadata_no_direct_fallback_observed') is not True or not enforcement.get('direct_bulk_targets'):
        raise ValueError('Missing scoped Tor policy and authorized bulk transport evidence')
    preflight=receipt.get('sync_preflight',{})
    if (preflight.get('passed') is not True or preflight.get('mode') not in ('caught-up','advancing-scan') or
            not 0<preflight.get('scanned_height',0)<=preflight.get('network_height',0)):
        raise ValueError('Missing real pre-funding synchronization readiness')
    recovery=stages['interruption']
    if (not all(recovery.get(key) is True for key in ('sync_restarted','synced','balance_preserved','history_preserved',
                                                    'metadata_blocked','direct_bulk_reachable','metadata_recovered')) or
            recovery.get('blocked_packets',0)<=0 or recovery.get('scanned_height',0)<=0):
        raise ValueError('Actual wallet synchronization interruption/recovery required')
    if (not re.fullmatch('[0-9a-f]{64}',str(stages['fund'].get('funding_txid',''))) or
            stages['fund'].get('balance_zatoshi',0)<=0 or stages['send'].get('fee_zatoshi',0)<=0 or
            stages['restore'].get('mined_height',0)<=0 or stages['restore'].get('amount_zatoshi')!=1000000):
        raise ValueError('Missing real transaction accounting/confirmation evidence')
    send=stages['send']
    if not all(send.get(key) is True for key in ('ui_review','ui_cancel_no_submission','ui_approval')):
        raise ValueError('Actual review/cancellation/approval UI evidence required')
    if (not all(send.get(key) is True for key in ('shielded_donation', 'multi_recipient_sdk_accepted',
                                                 'multi_recipient_wallet_rejected')) or
            send.get('automatic_identifying_memo') is not False or send.get('reviewed_memo_verified') is not True or send.get('multi_recipient_submitted') is not False):
        raise ValueError('Real shielded donation and fail-closed multi-recipient review evidence required')
    if not all(send.get(key) is True for key in ('clipboard_metadata_verified','newer_clipboard_preserved')):
        raise ValueError('Real isolated clipboard ownership/metadata evidence required')
    if stages['restore'].get('reviewed_memo_verified') is not True:
        raise ValueError('Restored recipient memo must match explicit review')
    if send.get('ui_approval_actions') != 1:
        raise ValueError('Exactly one real UI approval action required')
    if stages['fund'].get('app_transaction_state') != 'Confirmed':
        raise ValueError('The reused incoming funding transaction must reach the app confirmed state')
    funding=receipt.get('funding_setup',{})
    if (funding.get('tor_transport_verified') is not True or
            funding.get('proxy_unavailable_failed_closed') is not True or
            funding.get('persistent_tor_connection') is not True or
            funding.get('txid')!=stages['fund']['funding_txid']):
        raise ValueError('Missing Tor-only setup payment provenance')
    for key in ('candidate_apk_sha256', 'baseline_apk_sha256', 'instrumentation_apk_sha256', 'harness_sha256'):
        if not re.fullmatch('[0-9a-f]{64}', str(receipt.get(key, ''))):
            raise ValueError('Missing executed artifact/harness digest: ' + key)
    if not re.fullmatch('[0-9a-f]{64}',str(receipt.get('runtime_input_fingerprint',''))):
        raise ValueError('Missing product-runtime input fingerprint')
    if not re.fullmatch('[0-9a-f]{64}',str(receipt.get('build_input_fingerprint',''))):
        raise ValueError('Missing relevant executed-build input fingerprint')
    if not re.fullmatch('[0-9a-f]{40}', str(receipt.get('build_source_sha', ''))):
        raise ValueError('Missing build source identity')
    baseline = json.loads((ROOT / '.private/upgrade-baseline.json').read_text())
    if (receipt.get('baseline_source_sha') != baseline['source_sha'] or
            receipt.get('baseline_version_code') != baseline['version_code']):
        raise ValueError('Unexpected migration baseline')
    if receipt.get('candidate_version_code', 0) <= receipt.get('baseline_version_code', 0):
        raise ValueError('Update did not increase versionCode')
    txids = {stages[name].get('txId') for name in ('send', 'restart', 'restore')}
    if len(txids) != 1 or not re.fullmatch('[0-9a-f]{64}', str(next(iter(txids)))):
        raise ValueError('Transaction identity not preserved through restart/restore')
    return True


def validate_core_sources(receipt, product_revision='HEAD', acceptance_revision=None, root=ROOT):
    # For late test-only attestations use the trusted current acceptance source.
    # A controller-prepared upstream child instead owns its candidate app inputs,
    # preserving the existing separately gated upstream integration path.
    acceptance_revision = acceptance_revision or product_revision
    validate_core(receipt, input_fingerprint(acceptance_revision, root=root))
    runtime = input_fingerprint(product_revision, root=root, profile='core-runtime')
    # Host/controller inputs still bind current acceptance to the product candidate.
    # The APK producer is checked separately against compiled inputs below: host
    # Python corrections require fresh acceptance, but are not compiled into APKs.
    if input_fingerprint(acceptance_revision, root=root, profile='core-runtime') != runtime:
        raise ValueError('Executed wallet runtime differs from the production candidate')
    if receipt.get('runtime_input_fingerprint') != runtime:
        raise ValueError('Receipt wallet runtime binding mismatch')
    compiled = receipt['build_input_fingerprint']
    for revision in (acceptance_revision, receipt['build_source_sha']):
        if input_fingerprint(revision, root=root, profile='core-build') != compiled:
            raise ValueError('Executed instrumentation differs from current compiled test inputs')
    return runtime


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


def validate_binding(proof, profile, expected_inputs, trusted_bytes, artifact_bytes, allow_late=False):
    bound=proof.get(profile,{})
    if profile=='core_acceptance' and bound.get('binding')!=CORE_BINDING:
        raise ValueError('Missing versioned core product-runtime binding')
    if (allow_late and bound.get('status')=='pending' and bound.get('input_fingerprint')==expected_inputs
            and artifact_bytes is None):
        # The trusted controller has independently validated the real receipt.
        # Its addition after a candidate build must not force another APK build.
        return
    if (bound.get('status')!='passed' or bound.get('input_fingerprint')!=expected_inputs or
            bound.get('receipt_sha256')!=hashlib.sha256(trusted_bytes).hexdigest() or
            artifact_bytes!=trusted_bytes):
        raise ValueError('Missing or mismatched trusted acceptance: '+profile)


ATTESTATION_PATHS={'.private/core-acceptance.json','.private/quote-acceptance.json',
                   '.private/core-validation-summary.json','.private/core-coverage.md','.private/STATUS.md'}


def validate_attestation_changes(paths):
    if set(paths)-(ATTESTATION_PATHS | {CORE_TEST_SOURCE}):
        raise ValueError('Candidate or controller changed after validation; rebuild required')


def verify_promotion_revision(candidate, controller, root=ROOT):
    if not all(re.fullmatch('[0-9a-f]{40}',value) for value in (candidate,controller)):
        raise ValueError('Invalid promotion revision')
    if candidate==controller:return False
    # The upstream controller can prepare a reviewed child candidate. This is
    # the existing update path, independently gated by current real receipts.
    child=subprocess.run(['git','merge-base','--is-ancestor',controller,candidate],cwd=root,
                         stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    if child.returncode==0:return False
    subprocess.run(['git','merge-base','--is-ancestor',candidate,controller],cwd=root,check=True,
                   stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    paths=git('diff','--no-renames','--name-only',candidate,controller,root=root).decode().splitlines()
    validate_attestation_changes(paths)
    if input_fingerprint(candidate,root=root,profile='core-runtime') != input_fingerprint(controller,root=root,profile='core-runtime'):
        raise ValueError('Product runtime changed after the validated candidate')
    return True


if __name__ == '__main__':
    print(json.dumps({'input_fingerprint': input_fingerprint()}, sort_keys=True))
