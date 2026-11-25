"""Bounded installed-app transaction acceptance in the blank guarded Android guest.

Private seeds stay in instrumentation-owned guest storage. Only safe stage
summaries, artifact identities and the testnet transaction ID leave the guest.
"""
import hashlib
import ipaddress
import json
import os
import re
import struct
import sys
import subprocess
import threading
import time
from pathlib import Path
from core_faucet import FundingWindowExpired, PAYOUT_POLL_SECONDS, ready_session as faucet_ready_session
from acceptance_policy import (
    BASELINE_INSTRUMENTATION_STAGES, CANDIDATE_INSTRUMENTATION_STAGES, TOR_MODE_STAGES, validate_tor_mode_transition,
)

PACKAGE = 'com.personal.zecprivate.testnet.foss.debug'
RUNNER = PACKAGE + '.test/co.electriccoin.zcash.test.ZcashUiTestRunner'
INSTALLED_UPGRADE_STAGES = {'receiptProbe': 'candidate', 'recipient': 'baseline', 'restoreProbe': 'candidate'}
WALLET_PREFERENCES = 'shared_prefs/co.electriccoin.zcash.encrypted.xml'
TOR_MODE_STAGE_NAMES = {name for name, _ in TOR_MODE_STAGES}
STAGE_START_SECONDS = 1600
INSTRUMENTATION_DEADLINE_SECONDS = 1700
FUNDED_RESERVE_SECONDS = {False: PAYOUT_POLL_SECONDS + 670, True: PAYOUT_POLL_SECONDS + 820}


def instrumentation_role(stage):
    if stage in BASELINE_INSTRUMENTATION_STAGES:
        return 'baseline'
    if (stage in CANDIDATE_INSTRUMENTATION_STAGES or stage in TOR_MODE_STAGE_NAMES
            or stage in ('transportProbe', 'restoreProbe', 'receiptProbe', 'rpcProbe')):
        return 'candidate'
    raise ValueError('Unknown version-paired instrumentation stage: ' + stage)


def select_apk_roles(apks):
    named = {p.name: p for p in apks}
    if len(named) != 4 or len(apks) != 4:
        raise ValueError('Exactly four distinct version-paired APKs required')
    baseline = named['baseline-ui.apk']
    baseline_test = named['baseline-androidTest.apk']
    tests = [p for p in apks if 'androidTest' in p.name and p != baseline_test]
    candidates = [p for p in apks if p not in (baseline, baseline_test) and 'androidTest' not in p.name]
    if len(tests) != 1 or len(candidates) != 1:
        raise ValueError('Unambiguous candidate app/instrumentation roles required')
    return baseline, baseline_test, candidates[0], tests[0]


def installed_apk_hash(shell, package):
    installed = shell('pm', 'path', package).strip()
    managed_path = (r'package:/data/app/[A-Za-z0-9_~+=-]+/' + re.escape(package) +
                    r'-[A-Za-z0-9_~+=-]+/base\.apk')
    if not re.fullmatch(managed_path, installed):
        raise RuntimeError('Expected exactly one installed APK for ' + package)
    return shell('sha256sum', installed.removeprefix('package:')).split()[0]


def verify_instrumentation_pair(adb, expected_version, expected_hash):
    """Verify the installed app/test pair before a core stage or unfunded transport probe."""
    def shell(*args):
        return adb('shell', *args, timeout=20).stdout.decode(errors='replace')
    match = re.search(r'versionCode=(\d+)', shell('dumpsys', 'package', PACKAGE))
    if not match or int(match[1]) != expected_version:
        raise RuntimeError('Wrong app version for instrumentation dispatch')
    installed_hash = installed_apk_hash(shell, PACKAGE + '.test')
    if installed_hash != expected_hash:
        raise RuntimeError('Wrong instrumentation APK for dispatch')
    if RUNNER + ' (target=' + PACKAGE + ')' not in shell('pm', 'list', 'instrumentation', PACKAGE):
        raise RuntimeError('Unexpected instrumentation runner/target')
    return dict(app_version_code=expected_version, executed_sha256=installed_hash, installed_hash_verified=True)


def wait_for_preclaim_marker(adb, operation, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        marker = adb('shell', 'run-as', PACKAGE, 'cat', 'files/core-sync-ready.json',
                     timeout=10, check=False)
        if marker.returncode == 0:
            try:
                ready = json.loads(marker.stdout)
            except json.JSONDecodeError:
                # The guest may still be writing the marker.
                pass
            else:
                return isinstance(ready, dict) and ready.get('ready') is True and ready.get('probe_only') is True
        if operation.poll() is not None:
            break
        time.sleep(.5)
    return False


def claim_after_readiness(check_readiness, submit_claim):
    if check_readiness is not None:
        check_readiness()
    return submit_claim()


def run(adb, root, apks, manifest, *, transport_only=False, repeat_cold_start=False, upgrade_probe=None,
        preclaim_check=False, preclaim_only=False, use_tor=True, live_tor_probe=False, installed_upgrade=False,
        tor_mode_preparation=None, candidate_only=False, before_faucet_post=None):
    started = time.monotonic()
    tor_mode_transition = tor_mode_preparation is not None
    if candidate_only and (use_tor or transport_only or repeat_cold_start or upgrade_probe or preclaim_only
                           or live_tor_probe or tor_mode_transition or installed_upgrade):
        raise ValueError('Candidate-only lifecycle requires direct testnet with one app/instrumentation pair')
    if installed_upgrade and (use_tor or transport_only or repeat_cold_start or upgrade_probe or preclaim_check
                              or preclaim_only or live_tor_probe or tor_mode_transition):
        raise ValueError('Installed-upgrade verification requires the dedicated direct unfunded path')
    if tor_mode_transition and (use_tor or not live_tor_probe):
        raise ValueError('Tor mode transitions require direct acceptance with a native Tor probe')
    if not use_tor and (transport_only or repeat_cold_start or upgrade_probe or preclaim_only):
        raise ValueError('Tor-specific unfunded probes require the Tor transport')
    if preclaim_only and (not transport_only or upgrade_probe or repeat_cold_start):
        raise ValueError('Pre-claim validation requires the unfunded baseline pair')
    if upgrade_probe and (upgrade_probe not in ('restore', 'transport') or not transport_only or repeat_cold_start):
        raise ValueError('The upgrade probe requires its own unfunded advancing APK pair')
    baseline_fields = ('baseline_source_sha', 'baseline_version_code', 'baseline_apk_sha256',
                       'baseline_instrumentation_apk_sha256')
    pairing = dict(stages={})
    if candidate_only:
        if any(key in manifest for key in (*baseline_fields, 'upgradeBaselineInstrumentation')):
            raise ValueError('Candidate-only lifecycle cannot be bound to an upgrade baseline')
        apps = [path for path in apks if 'androidTest' not in path.name]
        tests = [path for path in apks if 'androidTest' in path.name]
        if len(apps) != 1 or len(tests) != 1 or any(path.name.startswith('baseline-') for path in apks):
            raise ValueError('Candidate-only lifecycle requires exactly one candidate app and test APK')
        candidate, test = apps[0], tests[0]
        ledger = Path(os.environ.get('CORE_FAUCET_LEDGER', ''))
        guard_root = Path(os.environ.get('CODEX_GUARD_OUTPUT') or root).resolve()
        if not ledger.is_absolute() or ledger.resolve().is_relative_to(guard_root):
            raise ValueError('Candidate-only lifecycle requires a durable faucet ledger outside the guard output')
        preclaim_check = True
    elif transport_only and not upgrade_probe:
        baseline, baseline_test = apks
        candidate, test = baseline, baseline_test
        if manifest['candidate_version_code'] != manifest['baseline_version_code']:
            raise ValueError('Unfunded transport probe requires the same recorded app/test pair')
    else:
        baseline, baseline_test, candidate, test = select_apk_roles(apks)
    pairing['candidate'] = dict(original_sha256=manifest['instrumentation_apk_sha256'],
                                executed_sha256=hashlib.sha256(test.read_bytes()).hexdigest(),
                                source_sha=manifest['build_source_sha'], app_version_code=manifest['candidate_version_code'])
    if not candidate_only:
        descriptor = manifest['upgradeBaselineInstrumentation']
        if (descriptor['source_sha'] != manifest['baseline_source_sha'] or
                descriptor['apk_sha256'] != manifest['baseline_instrumentation_apk_sha256']):
            raise ValueError('Baseline instrumentation descriptor/source mismatch')
        pairing['baseline'] = dict(original_sha256=manifest['baseline_instrumentation_apk_sha256'],
                                   executed_sha256=hashlib.sha256(baseline_test.read_bytes()).hexdigest(),
                                   source_sha=manifest['baseline_source_sha'], app_version_code=manifest['baseline_version_code'])
    stages = {}
    application_pids=set()
    report = dict(schema=1, passed=False,
                  evidence_class=('unfunded-installed-upgrade' if installed_upgrade else
                                  'unfunded-app-transport-probe' if transport_only else
                                  'candidate-only-testnet-lifecycle' if candidate_only else 'real-app-testnet'),
                  network='zcash-testnet',
                  tor_enabled=use_tor,
                  clearnet_fallback=False, direct_bulk_downloads=True,personal_wallet_access=False, real_funds_used=False,
                  stages=stages, instrumentation_pairing=pairing, input_fingerprint=manifest['input_fingerprint'],
                  runtime_input_fingerprint=manifest['runtime_input_fingerprint'],
                  build_input_fingerprint=manifest['build_input_fingerprint'],
                  build_source_sha=manifest['build_source_sha'],
                  candidate_version_code=manifest['candidate_version_code'],
                  **{name: manifest[name] for name in ('candidate_apk_sha256', 'instrumentation_apk_sha256')},
                  harness_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
    if not candidate_only:
        report.update({key: manifest[key] for key in baseline_fields})
    current_stage = 'install'
    active_instrumentation={}
    operation_lock=threading.Lock()

    def stage(name, timeout=700, *, readiness_only=False, tor_mode=None):
        nonlocal current_stage
        current_stage = 'preclaim_readiness' if readiness_only else name
        if time.monotonic() - started > STAGE_START_SECONDS:
            raise RuntimeError('Core acceptance total deadline exhausted')
        if candidate_only:
            role = 'candidate'
        elif installed_upgrade:
            role = INSTALLED_UPGRADE_STAGES[name]
        else:
            role = 'baseline' if readiness_only or (not use_tor and name == 'restoreProbe') else instrumentation_role(name)
        stage_uses_tor = (use_tor or name == 'rpcProbe') if tor_mode is None else tor_mode
        expected = pairing[role]
        verify_instrumentation_pair(adb, expected['app_version_code'], expected['executed_sha256'])
        if not readiness_only:
            pairing['stages'][name] = dict(role=role, **expected, installed_hash_verified=True)
        adb('logcat','-c',timeout=15)
        command=adb.command_base+['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                     'co.electriccoin.zcash.app.CoreWalletAcceptance', '-e', 'isolatedTestnet',
                     'valueless-coins-only', '-e', 'transport', 'tor' if stage_uses_tor else 'direct',
                     '-e', 'stage', name, RUNNER]
        timeout_seconds=min(timeout,max(1,INSTRUMENTATION_DEADLINE_SECONDS-int(time.monotonic()-started)))
        operation=subprocess.Popen(command,env=adb.environment,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        with operation_lock:active_instrumentation[name]=operation
        ready = False
        try:
            if readiness_only:
                ready = wait_for_preclaim_marker(adb, operation, timeout_seconds)
                # Stop at the existing unfunded marker, before the host requests an outage.
                adb('shell', 'am', 'force-stop', PACKAGE, timeout=15)
                timeout_seconds = 15
            stdout,stderr=operation.communicate(timeout=timeout_seconds)
            result=subprocess.CompletedProcess(command,operation.returncode,stdout,stderr)
        except BaseException:
            if operation.poll() is None:operation.kill()
            operation.communicate(timeout=10)
            raise
        finally:
            with operation_lock:active_instrumentation.pop(name,None)
        output = result.stdout.decode(errors='replace')
        # Android redirects System.out to logcat. Only retain our bounded safe
        # markers from this app UID after clearing this blank guest's log buffer.
        app_uid=uid()
        logs=adb('logcat','-d','-v','raw','--uid='+str(app_uid),'-s','System.out:I',timeout=15).stdout.decode(errors='replace')
        markers=[line for line in logs.splitlines() if line.startswith(('CORE_RESULT ','CORE_TARGETS ','CORE_SYNC_DIAGNOSTIC ','CORE_NATIVE_ERROR ','CORE_SEND_DIAGNOSTIC ','CORE_ACCOUNTING_DIAGNOSTIC '))]
        thread_logs=adb('logcat','-d','-v','threadtime','-t','2000','--uid='+str(app_uid),timeout=15).stdout.decode(errors='replace')
        application_pids.update(int(match[1]) for match in re.finditer(r'^\S+\s+\S+\s+(\d+)\s+\d+\s+[A-Z]\s+',thread_logs,re.M))
        if sum(map(len,markers))>8192:raise RuntimeError('Safe core markers exceed bound')
        if readiness_only and ready:
            print('CORE_PRECLAIM: app Tor RPC and updater metadata ready', flush=True)
            return
        output+='\n'+'\n'.join(markers)
        direct = re.findall(r'^INSTRUMENTATION_STATUS: zec_core_receipt=(\{[^\r\n]+\})\s*$', output, re.MULTILINE)
        # Published upgrade fixtures predate the instrumentation receipt channel.
        legacy = [line.split('CORE_RESULT ', 1)[1] for line in output.splitlines() if 'CORE_RESULT {' in line]
        rows = [json.loads(row) for row in (direct or legacy)]
        if readiness_only or result.returncode or 'OK (1 test)' not in output or len(rows) != 1 or rows[0].get('stage') != name or rows[0].get('passed') is not True:
            # Keep only class/line locations and explicit safe assertions; never raw test output.
            safe = [line[:240] for line in output.splitlines() if re.search(r'CoreWalletAcceptance.kt:\d+|^java\.lang\.|^kotlinx\.coroutines\.|Faucet claim HTTP \d+', line)]
            diagnostic=dict(stage=current_stage,lines=safe[-12:], result_channel=dict(
                exit_code=result.returncode, junit_passed='OK (1 test)' in output,
                direct_receipts=len(direct), legacy_receipts=len(legacy)))
            diagnostic['sync']=[json.loads(line.split(' ',1)[1]) for line in markers if line.startswith('CORE_SYNC_DIAGNOSTIC ')][-8:]
            diagnostic['native_errors']=[json.loads(line.split(' ',1)[1]) for line in markers if line.startswith('CORE_NATIVE_ERROR ')][-3:]
            diagnostic['submission']=[json.loads(line.split(' ',1)[1]) for line in markers if line.startswith('CORE_SEND_DIAGNOSTIC ')][-1:]
            diagnostic['accounting']=[json.loads(line.split(' ',1)[1]) for line in markers if line.startswith('CORE_ACCOUNTING_DIAGNOSTIC ')][-6:]
            native=adb('logcat','-d','-v','raw','-t','120','--uid='+str(app_uid),'-s','slipstream:V',timeout=15).stdout.decode(errors='replace')
            diagnostic['native_engine']=[re.sub(r'\b[0-9a-fA-F]{64,}\b','[hex omitted]',line)[:400]
                for line in native.splitlines() if re.search(r'stall|error|fail|warn|counter',line,re.I)
                and not re.search(r'seed|mnemonic|phrase|utest|secret|ufvk|viewing.?key',line,re.I)][-20:]
            if name=='fund':
                http=adb('logcat','-d','-v','raw','--uid='+str(app_uid),'-s','HttpClient:D',timeout=15,check=False).stdout.decode(errors='replace')
                failures=[]
                for line in http.splitlines():
                    try:body=json.loads(line.strip())
                    except (ValueError,TypeError):continue
                    if isinstance(body,dict) and body.get('status') in ('failed','review'):
                        message=re.sub(r'utest1[a-z0-9]+','[isolated test address]',str(body.get('message','')))[:800]
                        failures.append(dict(status=body['status'],message=message))
                    elif isinstance(body,dict) and body.get('ok') is False:
                        failures.append({key:re.sub(r'utest1[a-z0-9]+','[isolated test address]',str(body[key]))[:500]
                                         for key in ('code','error','message','kind','retryAfterSeconds') if key in body})
                diagnostic['faucet_failures']=failures[-2:]
            if name=='networkTargets':
                # This first stage precedes wallet/seed creation. Preserve its
                # startup failure, which may happen before JUnit can report it.
                diagnostic['pre_wallet_instrumentation']=output[-6000:]
                crash=adb('logcat','-b','crash','-d','-t','80',timeout=15,check=False)
                diagnostic['pre_wallet_crash']=crash.stdout.decode(errors='replace')[-10000:]
            (root/'core-testnet-diagnostic.json').write_text(json.dumps(diagnostic))
            raise RuntimeError('Core test stage failed: ' + current_stage)
        if (not use_tor or name == 'rpcProbe') and rows[0].get('tor_enabled') is not stage_uses_tor:
            raise RuntimeError('Instrumentation did not establish the expected Tor setting')
        if name == 'rpcProbe':
            report['live_tor_probe'] = dict(pairing['stages'].pop(name), passed=True, tor_enabled=True)
        if name in TOR_MODE_STAGE_NAMES:
            fields = ('stage', 'passed', 'network', 'tor_enabled', 'wallet_preserved', 'process_id',
                      'saved_mode', 'admission_blocked_until_restart')
            return dict(pairing['stages'].pop(name), **{key: rows[0][key] for key in fields})
        if name in ('fund', 'upgrade', 'send', 'restart', 'restore'):
            stages[name] = rows[0]
        if installed_upgrade or (candidate_only and name in ('receiptProbe', 'recipient', 'restoreProbe', 'sender')):
            stages[name] = {key: rows[0][key] for key in ('stage', 'passed', 'network', 'tor_enabled', 'transport')}
            if stages[name]['network'] != 'zcash-testnet' or stages[name]['transport'] != 'direct':
                raise RuntimeError('Unexpected stage network or transport')
        if name=='sender':report['sync_preflight']=rows[0]
        (root/'core-testnet-progress.json').write_text(json.dumps(dict(last_completed_stage=name,stages=stages,elapsed_seconds=time.monotonic()-started)))
        print('CORE_STAGE '+name+' passed', flush=True)
        return output

    def shell(*args):
        try:
            return adb('shell', *args, timeout=20).stdout.decode(errors='replace')
        except subprocess.CalledProcessError as error:
            # Commands contain no seed values; stdout may, so never include it.
            raise RuntimeError('Android shell failed: '+error.stderr.decode(errors='replace')[:1200]) from None

    def uid():
        # dumpsys field labels vary across Android versions. Ask the debug
        # target's own process identity, which also preflights fixture access.
        value=int(shell('run-as',PACKAGE,'id','-u').strip())
        if not 10000 <= value < 20000:raise RuntimeError('Unexpected isolated app UID')
        return value

    def version():
        return int(re.search(r'versionCode=(\d+)', shell('dumpsys', 'package', PACKAGE))[1])

    def rule(binary, *args):
        return shell(binary, *args)

    def denied_rows(binary):
        rows = (rule(binary, '-L', 'CORE_TOR', '-n', '-v', '-x') + '\n' +
                rule(binary, '-L', 'CORE_ACCEPT', '-n', '-v', '-x')).splitlines()
        return [line for line in rows if len(line.split()) > 2 and line.split()[2] == 'REJECT']

    def check_denials(stage_name):
        if not use_tor:
            return
        rejected = {binary: denied_rows(binary) for binary in ('iptables', 'ip6tables')}
        report.update({binary+'_denials': lines for binary, lines in rejected.items()})
        if any(int(line.split()[0]) for lines in rejected.values() for line in lines):
            report['egress_denial_stage'] = stage_name
            raise RuntimeError('Unexpected app egress attempts blocked; inspect exact destinations')

    def finish_network_check(stage_name, *, transport_exercised):
        check_denials(stage_name)
        report['network_enforcement'] = dict(
            passed=True,
            mode='direct public bulk endpoint TCP443 plus Tor relay allowlist; sensitive provider direct egress denied',
            allowed_relay_peers=len(allowed), embedded_bootstrap_pairs=len(bootstrap_peers),
            sensitive_targets=len(targets), direct_bulk_targets=sorted(bulk_targets),
            metadata_no_direct_fallback_observed=transport_exercised,
            metadata_rpc_scope=['getServerInfo', 'getLatestBlockHeight'] if transport_exercised else [],
            sdk_transport_policy='published SDK6.0.0 source inspected; bulk direct, sensitive calls Tor')
        report['passed'] = True

    def clear_wallet():
        private=adb('shell','run-as',PACKAGE,'cat','files/core-private.json',timeout=15).stdout
        if len(private)>16384:
            raise RuntimeError('Private test fixture exceeds bound')
        json.loads(private)
        shell('am','force-stop',PACKAGE)
        shell('pm','clear',PACKAGE)
        shell('run-as',PACKAGE,'mkdir','-p','files')
        # Seed bytes are stdin, never argv, output, or retained diagnostics.
        subprocess.run(adb.command_base+['shell',"run-as "+PACKAGE+" sh -c 'cat > files/core-private.json'"],
                       input=private,env=adb.environment,capture_output=True,check=True,timeout=15)

    def write_fixture(value):
        subprocess.run(adb.command_base+['shell',"run-as "+PACKAGE+" sh -c 'cat > files/core-private.json'"],
                       input=json.dumps(value).encode(),env=adb.environment,capture_output=True,check=True,timeout=15)

    def check_preclaim():
        nonlocal current_stage
        current_stage = 'preclaim_readiness'
        if not use_tor:
            if report.get('sync_preflight', {}).get('tor_enabled') is not False:
                raise RuntimeError('Direct wallet readiness was not established before requesting funds')
            if 'restoreProbe' not in pairing['stages']:
                raise RuntimeError('Direct seed restore was not established before requesting funds')
            report['preclaim_readiness'] = dict(passed=True, route='direct',
                                               role='candidate' if candidate_only else 'baseline', restore_verified=True)
            return
        marker = 'files/core-sync-ready.json'
        shell('run-as', PACKAGE, 'rm', '-f', marker)
        try:
            stage('transportProbe', 180, readiness_only=True)
            check_denials('preclaim_readiness')
            report['preclaim_readiness'] = dict(passed=True, route='app-tor', role='baseline')
        finally:
            shell('run-as', PACKAGE, 'rm', '-f', marker)

    def claim_funding(fund_testnet):
        nonlocal current_stage
        private = json.loads(adb('shell', 'run-as', PACKAGE, 'cat', 'files/core-private.json', timeout=15).stdout)
        if private.get('claimAttempted'):
            raise RuntimeError('Funding was already attempted; automatic re-claim forbidden')
        private['claimAttempted'] = True
        write_fixture(private)
        current_stage = 'funding_setup'
        # The funding helper receives only the public test address.
        try:
            report['funding_setup'] = fund_testnet(private['senderAddress'])
        except FundingWindowExpired:
            current_stage = 'funding_time_budget'
            raise
        return private

    def recheck_local_baseline():
        nonlocal current_stage
        current_stage = 'funding_upgrade_baseline'
        before_faucet_post()
        current_stage = 'funding_setup'

    firewall = False
    try:
        adb('root', timeout=20)
        adb('wait-for-device', timeout=30)
        if shell('id').find('uid=0') < 0:
            raise RuntimeError('Blank AOSP guest root required for network enforcement')
        if not transport_only or upgrade_probe:
            adb('install', '-r', str(candidate), timeout=90)
            adb('install', '-r', '-t', str(test), timeout=90)
            if candidate_only or tor_mode_transition:
                candidate_hash = installed_apk_hash(shell, PACKAGE)
                if candidate_hash != hashlib.sha256(candidate.read_bytes()).hexdigest():
                    raise RuntimeError('Installed candidate bytes differ from the prepared app APK')
                if candidate_only:
                    report['candidate_installed_sha256'] = candidate_hash
            stage('receiptProbe', 50)
            if live_tor_probe:
                stage('rpcProbe', 120)
            if tor_mode_transition:
                mode_steps = []
                for name, tor in TOR_MODE_STAGES:
                    shell('am', 'force-stop', PACKAGE)
                    mode_steps.append(stage(name, 120, tor_mode=tor))
                transition = dict(installed_apk_sha256=candidate_hash, steps=mode_steps)
                validate_tor_mode_transition(transition, pairing['candidate'], report['candidate_apk_sha256'],
                                             tor_mode_preparation)
                report['tor_mode_transition'] = transition
            if not candidate_only:
                adb('uninstall', PACKAGE + '.test', timeout=30)
                adb('uninstall', PACKAGE, timeout=30)
                # Release evidence records the required funded stages.
                if not installed_upgrade:
                    pairing['stages'].pop('receiptProbe')
        if not candidate_only:
            adb('install', '-r', str(baseline), timeout=90)
            adb('install', '-r', '-t', str(baseline_test), timeout=90)
            if version() != manifest['baseline_version_code']:
                raise RuntimeError('Wrong baseline version')
        app_uid = uid()
        if installed_upgrade:
            baseline_hash = installed_apk_hash(shell, PACKAGE)
            if baseline_hash != hashlib.sha256(baseline.read_bytes()).hexdigest():
                raise RuntimeError('Installed baseline app bytes differ from the prepared APK')
            stage('recipient', 240)
            current_stage = 'installed_upgrade'
            shell('am', 'force-stop', PACKAGE)
            # Hash private files inside the guest and record only the comparison outcome.
            # The encrypted wallet preferences contain the seed persisted by the app.
            def wallet_snapshot():
                return shell('run-as', PACKAGE, 'sha256sum', WALLET_PREFERENCES, 'files/core-private.json')

            previous = wallet_snapshot()
            if len(re.findall(r'^[0-9a-f]{64}[ \t]+', previous, re.MULTILINE)) != 2:
                raise RuntimeError('Expected encrypted wallet preferences and recovery fixture')
            adb('install', '-r', str(candidate), timeout=90)
            adb('install', '-r', '-t', str(test), timeout=90)
            candidate_hash = installed_apk_hash(shell, PACKAGE)
            if (uid() != app_uid or version() != manifest['candidate_version_code']
                    or candidate_hash != hashlib.sha256(candidate.read_bytes()).hexdigest()):
                raise RuntimeError('Installed update identity, version or APK mismatch')
            if previous != wallet_snapshot():
                raise RuntimeError('Encrypted wallet preferences or recovery fixture changed during installation')
            # The native restore probe requires cleared app storage. This happens
            # after the replacement/persistence assertions and restores the same seed.
            clear_wallet()
            stage('restoreProbe', 240)
            report.update(passed=True, faucet_claims=0, installed_upgrade=dict(
                package=PACKAGE, uid_preserved=True, encrypted_wallet_preserved=True, recovery_fixture_preserved=True,
                baseline_installed_sha256=baseline_hash, candidate_installed_sha256=candidate_hash))
            return
        if use_tor:
            # Bounded kernel connection metadata identifies blocked native SDK
            # bootstrap attempts when this AOSP image lacks the LOG extension.
            adb('shell','mount','-t','tracefs','nodev','/sys/kernel/tracing',timeout=15,check=False)
            try:
                for path,value in (('tracing_on','0'),('buffer_size_kb','2048'),('options/record-tgid','1'),
                                   ('events/sock/inet_sock_set_state/enable','1'),('tracing_on','1')):
                    shell('echo '+value+' > /sys/kernel/tracing/'+path)
                report['connection_tracing']=True
            except RuntimeError as error:
                report['connection_tracing']=False
                report['connection_tracing_error']=str(error)[:400]
            # Normal DNS is permitted for the explicitly authorized public bulk path.
            # Sensitive SDK/provider calls must still use native Tor without fallback.
            output = stage('networkTargets', 180)
            targets = set()
            bulk_targets = set()
            for line in output.splitlines():
                if line.startswith('CORE_TARGETS '):
                    resolved=json.loads(line.split(' ',1)[1])
                    targets.update(str(ipaddress.ip_address(value)) for value in resolved['sensitive'])
                    bulk_targets.update(str(ipaddress.ip_address(value)) for value in resolved['bulk'])
            if not targets or not bulk_targets or targets & bulk_targets:
                raise RuntimeError('Missing Tor-resolved destination addresses')
            app_uid = uid()
            # The fixed public relay directory is fetched by bounded direct HTTPS
            # before wallet creation/firewall installation. It contains no wallet data.
            # These peers permit Tor rotation, not arbitrary direct wallet requests.
            directory=json.loads(shell('run-as',PACKAGE,'cat','files/core-relays.json'))
            allowed=set()
            for relay in directory['relays']:
                for address in relay.get('or_addresses',[]):
                    host=address.rsplit(':',1)[0].strip('[]')
                    allowed.add(str(ipaddress.ip_address(host)))
            if not 1000 <= len(allowed) <= 40000:
                raise RuntimeError('Tor relay inventory outside reviewed bound')
            bootstrap_path=Path(__file__).with_name('core-network-policy.json')
            bootstrap=json.loads(bootstrap_path.read_text())
            properties=(Path(__file__).resolve().parents[1]/'gradle.properties').read_text()
            if 'ZCASH_SDK_VERSION='+bootstrap['sdk_version'] not in properties:
                raise RuntimeError('Tor bootstrap inventory does not match the compiled SDK')
            bootstrap_peers={(str(ipaddress.ip_address(row['address'])),int(row['port'])) for row in bootstrap['addresses']}
            if not 50<len(bootstrap_peers)<2000 or any(not 1<=port<=65535 for _,port in bootstrap_peers):
                raise RuntimeError('Tor bootstrap inventory outside reviewed bounds')
            if {address for address,_ in bootstrap_peers}&targets:
                raise RuntimeError('Tor bootstrap inventory overlaps direct wallet/provider destinations')
            report['bootstrap_policy_sha256']=hashlib.sha256(bootstrap_path.read_bytes()).hexdigest()
            # Snapshot established app TCP sockets after native Tor bootstrap.
            peers = set()
            for table in ('tcp', 'tcp6'):
                for line in shell('cat', '/proc/net/'+table).splitlines()[1:]:
                    fields = line.split()
                    if len(fields) < 8 or int(fields[7]) != app_uid or fields[3] != '01':
                        continue
                    address = fields[2].split(':')[0]
                    raw = bytes.fromhex(address)
                    raw = b''.join(raw[i:i+4][::-1] for i in range(0,len(raw),4))
                    peer = ipaddress.ip_address(raw)
                    if not peer.is_loopback and not peer.is_unspecified:
                        peers.add(str(peer))
            if peers & targets or not peers.issubset(allowed | {address for address,_ in bootstrap_peers}):
                raise RuntimeError('Cannot establish isolated native Tor relay peers')
            for binary, family in (('iptables',4),('ip6tables',6)):
                lines=['*filter', ':CORE_ACCEPT - [0:0]', ':CORE_TOR - [0:0]',
                       '-A CORE_ACCEPT -d '+('127.0.0.0/8' if family==4 else '::1')+' -j ACCEPT']
                for target in sorted(targets):
                    if ipaddress.ip_address(target).version==family:
                        lines.append('-A CORE_ACCEPT -d '+target+' -j REJECT')
                for target in sorted(bulk_targets):
                    if ipaddress.ip_address(target).version==family:
                        lines.append('-A CORE_ACCEPT -p tcp -d '+target+' --dport 443 -j ACCEPT')
                lines.append('-A CORE_ACCEPT -j CORE_TOR')
                for peer in sorted(allowed):
                    if ipaddress.ip_address(peer).version == family:
                        lines.append('-A CORE_TOR -p tcp -d '+peer+' -j ACCEPT')
                for peer,port in sorted(bootstrap_peers):
                    if ipaddress.ip_address(peer).version==family and peer not in allowed:
                        lines.append('-A CORE_TOR -p tcp -d '+peer+' --dport '+str(port)+' -j ACCEPT')
                # No UDP DNS or unobserved endpoint/relay is allowed from the app.
                lines.extend(['-A CORE_TOR -p udp -j REJECT','-A CORE_TOR -j REJECT','COMMIT'])
                local=root/(binary+'-rules.txt')
                # Windows text-mode CRLF is invalid input for Android restore.
                local.write_bytes(('\n'.join(lines)+'\n').encode('ascii'))
                remote='/data/local/tmp/'+local.name
                adb('push',str(local),remote,timeout=20)
                shell(binary+'-restore --noflush < '+remote)
                rule(binary,'-I','OUTPUT','1','-m','owner','--uid-owner',str(app_uid),'-j','CORE_ACCEPT')
            firewall = True
        if transport_only:
            stage('recipient',240)
            check_denials('recipient')
            clear_wallet()
            stage('sender',240)
            check_denials('sender')
            report['faucet_claims'] = 0
            if preclaim_only or preclaim_check:
                check_preclaim()
            if preclaim_only:
                finish_network_check('preclaim_readiness', transport_exercised=False)
                return
            if upgrade_probe:
                shell('am','force-stop',PACKAGE)
                adb('install','-r',str(candidate),timeout=90)
                adb('install','-r','-t',str(test),timeout=90)
                if uid()!=app_uid or version()!=manifest['candidate_version_code']:
                    raise RuntimeError('Installed restore probe identity/version mismatch')
                if upgrade_probe == 'restore':
                    clear_wallet()
                    stage('restoreProbe',240)
                    finish_network_check('restore-probe', transport_exercised=False)
                    return
        else:
            current_stage='faucet_readiness'
            post_by = started + STAGE_START_SECONDS - FUNDED_RESERVE_SECONDS[use_tor]
            local_check = {'before_post': recheck_local_baseline} if before_faucet_post is not None else {}
            with faucet_ready_session(root, post_by=post_by, **local_check) as (readiness, fund_testnet):
                report['faucet_readiness']=readiness
                stage('recipient',240)
                check_denials('recipient')
                clear_wallet()
                if not use_tor:
                    stage('restoreProbe', 240)
                    clear_wallet()
                stage('sender',240)
                check_denials('sender')
                private = claim_after_readiness(check_preclaim if preclaim_check else None,
                                                lambda: claim_funding(fund_testnet))
            private.update(fundingTxId=report['funding_setup']['txid'],fundingProvider=report['funding_setup']['provider'])
            write_fixture(private)
            if candidate_only:
                report['faucet_claims'] = report['funding_setup']['claim_requests']
            # App Tor must reconnect through the enforced relay set; refusal is safe.
            stage('fund',950)
            check_denials('fund')
            if not candidate_only:
                shell('am','force-stop',PACKAGE)
                adb('install','-r',str(candidate),timeout=90)
                adb('install','-r','-t',str(test),timeout=90)
                if uid()!=app_uid or version()!=manifest['candidate_version_code']:
                    raise RuntimeError('Installed update identity/version mismatch')
                stage('upgrade',240)
                check_denials('upgrade')
        if use_tor:
            # Start the normal wallet resync path, then interrupt its actual native
            # synchronizer before broadcast. Require fresh sync and intact history.
            before = time.monotonic()
            outcome = {}
            transport_stage = 'transportProbe' if transport_only else 'transport'
            def interrupted_request():
                try: stage(transport_stage,420);outcome['passed']=True
                except Exception as error: outcome['error']=type(error).__name__
            worker = threading.Thread(target=interrupted_request)
            worker.start()
            active_drops=[]
            try:
                def wait_marker(name,timeout=120):
                    deadline=time.monotonic()+timeout
                    while time.monotonic()<deadline:
                        data=adb('shell','run-as',PACKAGE,'cat','files/'+name,timeout=10,check=False)
                        if data.returncode==0:
                            return json.loads(data.stdout)
                        if not worker.is_alive():raise RuntimeError('Wallet sync stage ended before handshake')
                        time.sleep(.5)
                    raise RuntimeError('Wallet sync handshake deadline exceeded')
                if not wait_marker('core-sync-ready.json',180).get('ready'):
                    raise RuntimeError('Wallet synchronizer not ready for interruption')
                for binary in ('iptables','ip6tables'):
                    rule(binary,'-I','CORE_TOR','1','-j','DROP')
                    active_drops.append(binary)
                shell('run-as',PACKAGE,'touch','files/core-sync-drop')
                outage=wait_marker('core-sync-requested.json',35)
                if not all(outage.get(key) is True for key in ('requested','metadata_blocked','update_blocked','direct_bulk_reachable')):
                    raise RuntimeError('Sensitive Tor failure with reachable direct bulk path not established')
                time.sleep(10)
                blocked = 0
                for binary in ('iptables','ip6tables'):
                    counters = rule(binary,'-L','CORE_TOR','-n','-v','-x')
                    for line in counters.splitlines():
                        fields=line.split()
                        if len(fields)>2 and fields[2]=='DROP':blocked+=int(fields[0])
                    rule(binary,'-D','CORE_TOR','-j','DROP')
                    active_drops.remove(binary)
                worker.join(timeout=200)
                if worker.is_alive() or not outcome.get('passed') or blocked==0:
                    report['transport_failure'] = dict(worker_active=worker.is_alive(),
                        worker_error=outcome.get('error'), blocked_packets=blocked)
                    raise RuntimeError('Tor interruption/recovery not established')
                recovery=json.loads(shell('run-as',PACKAGE,'cat','files/core-sync-result.json'))
                required = ['sync_restarted', 'synced', 'balance_preserved', 'update_metadata_valid_before',
                            'update_blocked_during_outage']
                required.append('probe_only' if transport_only else 'history_preserved')
                updater_reachable = recovery.get('update_metadata_valid_after') is True or (
                    recovery.get('update_rate_limited_after') is True and recovery.get('update_http_status_after') in (403,429))
                if not all(recovery.get(key) is True for key in required) or not updater_reachable:
                    raise RuntimeError('Wallet synchronization recovery not established')
                print('CORE_UPDATER_RECOVERY ' + json.dumps(dict(metadata_valid=recovery['update_metadata_valid_after'],
                    http_status=recovery.get('update_http_status_after', 200))), flush=True)
                stages['interruption']=dict(passed=True,metadata_blocked=True,direct_bulk_reachable=True,metadata_recovered=True,blocked_packets=blocked,seconds=time.monotonic()-before,**recovery)
            finally:
                cleanup_errors=[]
                for binary in reversed(active_drops):
                    try:rule(binary,'-D','CORE_TOR','-j','DROP')
                    except Exception as error:cleanup_errors.append(type(error).__name__)
                if worker.is_alive():
                    try:adb('shell','am','force-stop',PACKAGE,timeout=15,check=False)
                    except Exception as error:cleanup_errors.append(type(error).__name__)
                    with operation_lock:operation=active_instrumentation.get(transport_stage)
                    if operation is not None and operation.poll() is None:
                        operation.terminate()
                        try:operation.wait(timeout=10)
                        except subprocess.TimeoutExpired:operation.kill();operation.wait(timeout=10)
                    worker.join(timeout=100)
                if worker.is_alive():raise RuntimeError('Interruption instrumentation worker did not exit')
                if cleanup_errors:raise RuntimeError('Tor outage cleanup failed: '+','.join(cleanup_errors))
        if not transport_only:
            check_denials('transport')
            stage('send')
            check_denials('send')
            shell('am','force-stop',PACKAGE)
            stage('restart',240)
            check_denials('restart')
            clear_wallet()
            stage('restore')
        if repeat_cold_start:
            if not transport_only:
                raise ValueError('Cold-start diagnostics must be unfunded')
            check_denials('transport-probe')
            for cycle in range(2):
                clear_wallet()
                stage('sender', 240)
                check_denials(f'cold-start-{cycle + 1}')
        if use_tor:
            finish_network_check('cold-start' if repeat_cold_start else current_stage, transport_exercised=True)
        else:
            report['passed'] = True
    finally:
        failure=sys.exc_info()[1]
        if failure:report['failure']=dict(type=type(failure).__name__,message=str(failure)[:1600])
        report['seconds']=time.monotonic()-started
        report['last_stage']=current_stage
        if failure and firewall and 'egress_denial_stage' not in report:
            try:check_denials(current_stage)
            except (RuntimeError, subprocess.SubprocessError):pass
        if report.get('connection_tracing'):
            try:
                shell('echo 0 > /sys/kernel/tracing/tracing_on')
                mapping=shell('cat','/sys/kernel/tracing/saved_tgids')
                tgids={int(row[0]):int(row[1]) for line in mapping.splitlines() if len(row:=line.split())==2 and all(v.isdigit() for v in row)}
                trace=shell('cat','/sys/kernel/tracing/trace')
                report['connection_trace_header']=trace[:500]
                report['connection_trace_sample']=[re.sub(r'skaddr=\S+','skaddr=[omitted]',line)[:350] for line in trace.splitlines() if 'inet_sock_set_state:' in line][:8]
                connections=set()
                for line in trace.splitlines():
                    tid=re.search(r'-(\d+)(?:\s+\(\s*\d+\s*\))?\s+\[',line)
                    if not tid or tgids.get(int(tid[1]),int(tid[1])) not in application_pids:continue
                    port=re.search(r'\bdport=(\d+)',line)
                    family='daddrv6' if 'family=AF_INET6' in line or 'family=10 ' in line else 'daddr'
                    address=re.search(r'\b'+family+r'=([^\s]+)',line)
                    if port and address:
                        try:
                            parsed=ipaddress.ip_address(address[1]);parsed=getattr(parsed,'ipv4_mapped',None) or parsed
                            connections.add((str(parsed),int(port[1])))
                        except ValueError:pass
                report['application_pids']=sorted(application_pids)
                report['connection_destinations']=[dict(address=address,port=port) for address,port in sorted(connections)[:100]]
                report['connection_destinations_truncated']=len(connections)>100
                if 'egress_denial_stage' in report:
                    report['unclassified_connections'] = [
                        dict(address=address, port=port)
                        for address, port in sorted(connections)
                        if address not in allowed and (address, port) not in bootstrap_peers
                        and (address not in bulk_targets or port != 443)
                        and not ipaddress.ip_address(address).is_loopback
                    ][:20]
            except (RuntimeError, subprocess.SubprocessError) as error:
                report['connection_trace_error']=type(error).__name__
        if firewall:
            for binary in ('iptables','ip6tables'):
                # Retain counter totals only; guard-owned guest disappears next.
                try:
                    rows=(rule(binary,'-L','CORE_TOR','-n','-v','-x')+'\n'+rule(binary,'-L','CORE_ACCEPT','-n','-v','-x')).splitlines()
                    report[binary+'_counters']=[line for line in rows if len(line.split())>2 and line.split()[2] in ('REJECT','DROP')]
                except (RuntimeError, subprocess.SubprocessError) as error:
                    report[binary+'_diagnostic_error']=type(error).__name__
            try:
                kernel=adb('shell','dmesg',timeout=15,check=False).stdout.decode(errors='replace')
                report['blocked_connection_diagnostics']=[line[:350] for line in kernel.splitlines() if 'CORE_EGRESS:' in line][-30:]
            except (RuntimeError, subprocess.SubprocessError) as error:
                report['guest_diagnostic_error']=type(error).__name__
        if not installed_upgrade:
            try:
                private=json.loads(adb('shell','run-as',PACKAGE,'cat','files/core-private.json',timeout=15,check=False).stdout)
                allowed_fields=('claimId','fundingTxId','fundingProvider','fundedBalance','balanceBeforeSend','feeZatoshi','senderConfirmedBalance','restoreMinedHeight','restoreTransactionState','candidateTxIds','initialSubmitType')
                report['transaction_evidence']={key:private[key] for key in allowed_fields if key in private}
            except (ValueError,subprocess.SubprocessError):pass
        (root/'core-testnet-result.json').write_text(json.dumps(report,indent=2)+'\n')
