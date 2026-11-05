"""Bounded installed-app transaction acceptance in the blank guarded Android guest.

Private seeds stay in instrumentation-owned guest storage. Only safe stage
summaries, artifact identities and the testnet transaction ID leave the guest.
"""
import hashlib
import ipaddress
import json
import re
import struct
import subprocess
import threading
import time
from pathlib import Path

PACKAGE = 'com.personal.zecprivate.testnet.foss.debug'
RUNNER = PACKAGE + '.test/co.electriccoin.zcash.test.ZcashUiTestRunner'


def run(adb, root, apks, manifest):
    started = time.monotonic()
    baseline = next(p for p in apks if p.name == 'baseline-ui.apk')
    test = next(p for p in apks if 'androidTest' in p.name)
    candidate = next(p for p in apks if p not in (baseline, test))
    stages = {}
    report = dict(schema=1, passed=False, evidence_class='real-app-testnet', network='zcash-testnet',
                  clearnet_fallback=False, personal_wallet_access=False, real_funds_used=False,
                  stages=stages, input_fingerprint=manifest['input_fingerprint'],
                  build_input_fingerprint=manifest['build_input_fingerprint'],
                  build_source_sha=manifest['build_source_sha'],
                  baseline_source_sha=manifest['baseline_source_sha'],
                  baseline_version_code=3234006, candidate_version_code=3234007,
                  **{name: manifest[name] for name in ('candidate_apk_sha256', 'baseline_apk_sha256',
                     'instrumentation_apk_sha256')},
                  harness_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
    current_stage = 'install'

    def stage(name, timeout=700):
        nonlocal current_stage
        current_stage = name
        if time.monotonic() - started > 1600:
            raise RuntimeError('Core acceptance total deadline exhausted')
        result = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                     'co.electriccoin.zcash.app.CoreWalletAcceptance', '-e', 'isolatedTestnet',
                     'valueless-coins-only', '-e', 'stage', name, RUNNER,
                     timeout=min(timeout, max(1, 1700-int(time.monotonic()-started))), check=False)
        output = result.stdout.decode(errors='replace')
        rows = [json.loads(line.split('CORE_RESULT ', 1)[1]) for line in output.splitlines() if 'CORE_RESULT {' in line]
        if 'OK (1 test)' not in output or len(rows) != 1 or rows[0].get('stage') != name or rows[0].get('passed') is not True:
            # Keep only class/line locations and explicit safe assertions; never raw test output.
            safe = [line[:240] for line in output.splitlines() if re.search(r'CoreWalletAcceptance.kt:\d+|^java\.lang\.|^kotlinx\.coroutines\.|Faucet claim HTTP \d+', line)]
            diagnostic=dict(stage=name,lines=safe[-12:])
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
        print('CORE_STAGE '+name+' passed', flush=True)
        return output

    def shell(*args):
        return adb('shell', *args, timeout=20).stdout.decode(errors='replace')

    def uid():
        info = shell('dumpsys', 'package', PACKAGE)
        return int(re.search(r'userId=(\d+)', info)[1])

    def version():
        return int(re.search(r'versionCode=(\d+)', shell('dumpsys', 'package', PACKAGE))[1])

    def rule(binary, *args):
        return shell(binary, *args)

    def clear_wallet():
        private=adb('shell','run-as',PACKAGE,'cat','files/core-private.json',timeout=15).stdout
        if len(private)>16384:
            raise RuntimeError('Private test fixture exceeds bound')
        json.loads(private)
        (root/'core-private.json').write_bytes(private)
        shell('am','force-stop',PACKAGE)
        shell('pm','clear',PACKAGE)
        shell('run-as',PACKAGE,'mkdir','-p','files')
        # Seed bytes are stdin, never argv, output, or retained diagnostics.
        subprocess.run(adb.command_base+['shell',"run-as "+PACKAGE+" sh -c 'cat > files/core-private.json'"],
                       input=private,env=adb.environment,capture_output=True,check=True,timeout=15)

    firewall = False
    try:
        adb('root', timeout=20)
        adb('wait-for-device', timeout=30)
        if shell('id').find('uid=0') < 0:
            raise RuntimeError('Blank AOSP guest root required for network enforcement')
        adb('install', '-r', str(baseline), timeout=90)
        adb('install', '-r', '-t', str(test), timeout=90)
        if version() != 3234006:
            raise RuntimeError('Wrong baseline version')
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
        if peers & targets or not peers.issubset(allowed):
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
            # No UDP DNS or unobserved endpoint/relay is allowed from the app.
            lines.extend(['-A CORE_ACCEPT -j REJECT','COMMIT'])
            local=root/(binary+'-rules.txt');local.write_text('\n'.join(lines)+'\n')
            remote='/data/local/tmp/'+local.name
            adb('push',str(local),remote,timeout=20)
            shell(binary+'-restore --noflush < '+remote)
            rule(binary,'-I','OUTPUT','1','-m','owner','--uid-owner',str(app_uid),'-j','CORE_ACCEPT')
        firewall = True
        stage('recipient',240)
        clear_wallet()
        # App Tor must reconnect through the enforced relay set; refusal is safe.
        stage('fund')
        shell('am','force-stop',PACKAGE)
        adb('install','-r',str(candidate),timeout=90)
        if uid()!=app_uid or version()!=3234007:
            raise RuntimeError('Installed update identity/version mismatch')
        stage('upgrade',240)
        # A controlled transport outage before any send: require observed blocked
        # packets, successful native-Tor request after restoration, and intact funds.
        before = time.monotonic()
        for binary in ('iptables','ip6tables'):
            rule(binary,'-I','CORE_ACCEPT','1','-j','DROP')
        outcome = {}
        def interrupted_request():
            try: stage('transport',120);outcome['passed']=True
            except Exception as error: outcome['error']=type(error).__name__
        worker = threading.Thread(target=interrupted_request)
        worker.start()
        time.sleep(10)
        blocked = 0
        for binary in ('iptables','ip6tables'):
            counters = rule(binary,'-L','CORE_ACCEPT','-n','-v','-x')
            for line in counters.splitlines():
                fields=line.split()
                if len(fields)>2 and fields[2]=='DROP':blocked+=int(fields[0])
            rule(binary,'-D','CORE_ACCEPT','1')
        worker.join(timeout=125)
        if worker.is_alive() or not outcome.get('passed') or blocked==0:
            raise RuntimeError('Tor interruption/recovery not established')
        stages['interruption']=dict(passed=True,blocked_packets=blocked,seconds=time.monotonic()-before)
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
                                           allowed_relay_peers=len(allowed),tor_resolved_targets=len(targets))
        report['passed']=True
    finally:
        report['seconds']=time.monotonic()-started
        report['last_stage']=current_stage
        if firewall:
            for binary in ('iptables','ip6tables'):
                # Retain counter totals only; guard-owned guest disappears next.
                try:
                    rows=rule(binary,'-L','CORE_ACCEPT','-n','-v','-x').splitlines()
                    report[binary+'_counters']=[line for line in rows if len(line.split())>2 and line.split()[2] in ('REJECT','DROP')]
                except subprocess.SubprocessError: pass
        (root/'core-testnet-result.json').write_text(json.dumps(report,indent=2)+'\n')
