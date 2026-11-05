"""Bounded installed-app transaction acceptance in the blank guarded Android guest.

Private seeds stay in instrumentation-owned guest storage. Only safe stage
summaries, artifact identities and the testnet transaction ID leave the guest.
"""
import hashlib
import ipaddress
import json
import re
import struct
import sys
import subprocess
import threading
import time
from pathlib import Path
from core_faucet import fund as fund_testnet

PACKAGE = 'com.personal.zecprivate.testnet.foss.debug'
RUNNER = PACKAGE + '.test/co.electriccoin.zcash.test.ZcashUiTestRunner'


def run(adb, root, apks, manifest):
    started = time.monotonic()
    baseline = next(p for p in apks if p.name == 'baseline-ui.apk')
    test = next(p for p in apks if 'androidTest' in p.name)
    candidate = next(p for p in apks if p not in (baseline, test))
    stages = {}
    application_pids=set()
    report = dict(schema=1, passed=False, evidence_class='real-app-testnet', network='zcash-testnet',
                  clearnet_fallback=False, personal_wallet_access=False, real_funds_used=False,
                  stages=stages, input_fingerprint=manifest['input_fingerprint'],
                  build_input_fingerprint=manifest['build_input_fingerprint'],
                  build_source_sha=manifest['build_source_sha'],
                  baseline_source_sha=manifest['baseline_source_sha'],
                  baseline_version_code=manifest['baseline_version_code'], candidate_version_code=manifest['candidate_version_code'],
                  **{name: manifest[name] for name in ('candidate_apk_sha256', 'baseline_apk_sha256',
                     'instrumentation_apk_sha256')},
                  harness_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
    current_stage = 'install'

    def stage(name, timeout=700):
        nonlocal current_stage
        current_stage = name
        if time.monotonic() - started > 1600:
            raise RuntimeError('Core acceptance total deadline exhausted')
        adb('logcat','-c',timeout=15)
        result = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                     'co.electriccoin.zcash.app.CoreWalletAcceptance', '-e', 'isolatedTestnet',
                     'valueless-coins-only', '-e', 'stage', name, RUNNER,
                     timeout=min(timeout, max(1, 1700-int(time.monotonic()-started))), check=False)
        output = result.stdout.decode(errors='replace')
        # Android redirects System.out to logcat. Only retain our bounded safe
        # markers from this app UID after clearing this blank guest's log buffer.
        app_uid=uid()
        logs=adb('logcat','-d','-v','raw','--uid='+str(app_uid),'-s','System.out:I',timeout=15).stdout.decode(errors='replace')
        markers=[line for line in logs.splitlines() if line.startswith(('CORE_RESULT ','CORE_TARGETS ','CORE_SYNC_DIAGNOSTIC ','CORE_NATIVE_ERROR '))]
        thread_logs=adb('logcat','-d','-v','threadtime','-t','2000','--uid='+str(app_uid),timeout=15).stdout.decode(errors='replace')
        application_pids.update(int(match[1]) for match in re.finditer(r'^\S+\s+\S+\s+(\d+)\s+\d+\s+[A-Z]\s+',thread_logs,re.M))
        if sum(map(len,markers))>8192:raise RuntimeError('Safe core markers exceed bound')
        output+='\n'+'\n'.join(markers)
        rows = [json.loads(line.split('CORE_RESULT ', 1)[1]) for line in output.splitlines() if 'CORE_RESULT {' in line]
        if 'OK (1 test)' not in output or len(rows) != 1 or rows[0].get('stage') != name or rows[0].get('passed') is not True:
            # Keep only class/line locations and explicit safe assertions; never raw test output.
            safe = [line[:240] for line in output.splitlines() if re.search(r'CoreWalletAcceptance.kt:\d+|^java\.lang\.|^kotlinx\.coroutines\.|Faucet claim HTTP \d+', line)]
            diagnostic=dict(stage=name,lines=safe[-12:])
            diagnostic['sync']=[json.loads(line.split(' ',1)[1]) for line in markers if line.startswith('CORE_SYNC_DIAGNOSTIC ')][-1:]
            diagnostic['native_errors']=[json.loads(line.split(' ',1)[1]) for line in markers if line.startswith('CORE_NATIVE_ERROR ')][-3:]
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
            raise RuntimeError('Core test stage failed: ' + name)
        if name in ('fund', 'upgrade', 'send', 'restart', 'restore'):
            stages[name] = rows[0]
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

    firewall = False
    try:
        adb('root', timeout=20)
        adb('wait-for-device', timeout=30)
        if shell('id').find('uid=0') < 0:
            raise RuntimeError('Blank AOSP guest root required for network enforcement')
        adb('install', '-r', str(baseline), timeout=90)
        adb('install', '-r', '-t', str(test), timeout=90)
        if version() != manifest['baseline_version_code']:
            raise RuntimeError('Wrong baseline version')
        uid()
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
        # Android netd can resolve on behalf of an app under a system UID.
        # Block guest DNS/DoT as well as app-UID egress; Tor resolves remotely.
        for binary in ('iptables','ip6tables'):
            for protocol,port in (('udp','53'),('tcp','53'),('tcp','853')):
                rule(binary,'-I','OUTPUT','1','-p',protocol,'--dport',port,'-j','REJECT')
        output = stage('networkTargets', 180)
        targets = set()
        for line in output.splitlines():
            if line.startswith('CORE_TARGETS '):
                targets.update(str(ipaddress.ip_address(value)) for value in json.loads(line.split(' ', 1)[1]))
        if not targets:
            raise RuntimeError('Missing Tor-resolved destination addresses')
        app_uid = uid()
        # Independently published Tor relay addresses were fetched through app
        # Tor. They allow guard rotation and process restarts without permitting
        # arbitrary direct Internet connections or DNS.
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
            lines=['*filter', ':CORE_ACCEPT - [0:0]',
                   '-A CORE_ACCEPT -d '+('127.0.0.0/8' if family==4 else '::1')+' -j ACCEPT']
            for target in sorted(targets):
                if ipaddress.ip_address(target).version==family:
                    lines.append('-A CORE_ACCEPT -d '+target+' -j REJECT')
            for peer in sorted(allowed):
                if ipaddress.ip_address(peer).version == family:
                    lines.append('-A CORE_ACCEPT -p tcp -d '+peer+' -j ACCEPT')
            for peer,port in sorted(bootstrap_peers):
                if ipaddress.ip_address(peer).version==family and peer not in allowed:
                    lines.append('-A CORE_ACCEPT -p tcp -d '+peer+' --dport '+str(port)+' -j ACCEPT')
            # No UDP DNS or unobserved endpoint/relay is allowed from the app.
            lines.extend(['-A CORE_ACCEPT -p udp -j REJECT','-A CORE_ACCEPT -j REJECT','COMMIT'])
            local=root/(binary+'-rules.txt')
            # Windows text-mode CRLF is invalid input for Android restore.
            local.write_bytes(('\n'.join(lines)+'\n').encode('ascii'))
            remote='/data/local/tmp/'+local.name
            adb('push',str(local),remote,timeout=20)
            shell(binary+'-restore --noflush < '+remote)
            # Logging is optional diagnostics; the terminal REJECT remains
            # mandatory even on images without the kernel LOG extension.
            diagnostic=adb('shell',binary,'-I','CORE_ACCEPT',str(len(lines)-3),'-m','limit','--limit','30/minute',
                           '-j','LOG','--log-prefix','CORE_EGRESS:',timeout=15,check=False)
            report[binary+'_kernel_logging']=diagnostic.returncode==0
            if diagnostic.returncode:report[binary+'_logging_error']=diagnostic.stderr.decode(errors='replace')[:500]
            rule(binary,'-I','OUTPUT','1','-m','owner','--uid-owner',str(app_uid),'-j','CORE_ACCEPT')
        firewall = True
        stage('recipient',240)
        clear_wallet()
        stage('sender',240)
        private=json.loads(adb('shell','run-as',PACKAGE,'cat','files/core-private.json',timeout=15).stdout)
        if private.get('claimAttempted'):raise RuntimeError('Funding was already attempted; automatic re-claim forbidden')
        private['claimAttempted']=True
        write_fixture(private)
        current_stage='funding_setup'
        # Only the public test address enters this helper; it never sees seeds.
        report['funding_setup']=fund_testnet(private['senderAddress'],root)
        private.update(fundingTxId=report['funding_setup']['txid'],fundingProvider=report['funding_setup']['provider'])
        write_fixture(private)
        # App Tor must reconnect through the enforced relay set; refusal is safe.
        stage('fund',950)
        shell('am','force-stop',PACKAGE)
        adb('install','-r',str(candidate),timeout=90)
        if uid()!=app_uid or version()!=manifest['candidate_version_code']:
            raise RuntimeError('Installed update identity/version mismatch')
        stage('upgrade',240)
        # Start the normal wallet resync path, then interrupt its actual native
        # synchronizer before broadcast. Require fresh sync and intact history.
        before = time.monotonic()
        outcome = {}
        def interrupted_request():
            try: stage('transport',300);outcome['passed']=True
            except Exception as error: outcome['error']=type(error).__name__
        worker = threading.Thread(target=interrupted_request)
        worker.start()
        def wait_marker(name,timeout=120):
            deadline=time.monotonic()+timeout
            while time.monotonic()<deadline:
                data=adb('shell','run-as',PACKAGE,'cat','files/'+name,timeout=10,check=False)
                if data.returncode==0:
                    return json.loads(data.stdout)
                if not worker.is_alive():raise RuntimeError('Wallet sync stage ended before handshake')
                time.sleep(.5)
            raise RuntimeError('Wallet sync handshake deadline exceeded')
        if not wait_marker('core-sync-ready.json').get('ready'):
            raise RuntimeError('Wallet synchronizer not ready for interruption')
        for binary in ('iptables','ip6tables'):
            rule(binary,'-I','CORE_ACCEPT','1','-j','DROP')
        shell('run-as',PACKAGE,'touch','files/core-sync-drop')
        if not wait_marker('core-sync-requested.json',30).get('requested'):
            raise RuntimeError('Wallet resync was not requested under outage')
        time.sleep(10)
        blocked = 0
        for binary in ('iptables','ip6tables'):
            counters = rule(binary,'-L','CORE_ACCEPT','-n','-v','-x')
            for line in counters.splitlines():
                fields=line.split()
                if len(fields)>2 and fields[2]=='DROP':blocked+=int(fields[0])
            rule(binary,'-D','CORE_ACCEPT','1')
        worker.join(timeout=200)
        if worker.is_alive() or not outcome.get('passed') or blocked==0:
            raise RuntimeError('Tor interruption/recovery not established')
        recovery=json.loads(shell('run-as',PACKAGE,'cat','files/core-sync-result.json'))
        if not all(recovery.get(key) is True for key in ('sync_restarted','synced','balance_preserved','history_preserved')):
            raise RuntimeError('Wallet synchronization recovery not established')
        stages['interruption']=dict(passed=True,blocked_packets=blocked,seconds=time.monotonic()-before,**recovery)
        stage('send')
        shell('am','force-stop',PACKAGE)
        stage('restart',240)
        clear_wallet()
        stage('restore')
        denied=0
        for binary in ('iptables','ip6tables'):
            rows=rule(binary,'-L','CORE_ACCEPT','-n','-v','-x').splitlines()
            rejected=[line for line in rows if len(line.split())>2 and line.split()[2]=='REJECT']
            report[binary+'_denials']=rejected
            denied+=sum(int(line.split()[0]) for line in rejected)
        if denied:
            raise RuntimeError('Unexpected app egress attempts blocked; inspect exact destinations')
        report['network_enforcement']=dict(passed=True,mode='app-UID TCP Tor-relay allowlist; dual-stack deny other egress; whole-guest DNS/DoT blocked',
                                           allowed_relay_peers=len(allowed),embedded_bootstrap_pairs=len(bootstrap_peers),tor_resolved_targets=len(targets))
        report['passed']=True
    finally:
        failure=sys.exc_info()[1]
        if failure:report['failure']=dict(type=type(failure).__name__,message=str(failure)[:1600])
        report['seconds']=time.monotonic()-started
        report['last_stage']=current_stage
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
            except RuntimeError as error:report['connection_trace_error']=str(error)[:500]
        if firewall:
            for binary in ('iptables','ip6tables'):
                # Retain counter totals only; guard-owned guest disappears next.
                try:
                    rows=rule(binary,'-L','CORE_ACCEPT','-n','-v','-x').splitlines()
                    report[binary+'_counters']=[line for line in rows if len(line.split())>2 and line.split()[2] in ('REJECT','DROP')]
                except subprocess.SubprocessError: pass
            kernel=adb('shell','dmesg',timeout=15,check=False).stdout.decode(errors='replace')
            report['blocked_connection_diagnostics']=[line[:350] for line in kernel.splitlines() if 'CORE_EGRESS:' in line][-30:]
        try:
            private=json.loads(adb('shell','run-as',PACKAGE,'cat','files/core-private.json',timeout=15,check=False).stdout)
            allowed_fields=('claimId','fundingTxId','fundingProvider','fundedBalance','balanceBeforeSend','feeZatoshi','senderConfirmedBalance','restoreMinedHeight','restoreTransactionState')
            report['transaction_evidence']={key:private[key] for key in allowed_fields if key in private}
        except (ValueError,subprocess.SubprocessError):pass
        (root/'core-testnet-result.json').write_text(json.dumps(report,indent=2)+'\n')
