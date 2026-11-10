"""One bounded TAZ faucet claim through task-owned Tor; preserve its claim ledger.

No app keys enter this helper. Native SDK Tor remains enabled in the wallet.
Funding uses pinned owned Tor and a real unavailable-proxy negative check;
an external exit-IP service is not a dependency of wallet acceptance.
The official Windows expert bundle is the latest stable prebuilt distribution
verified 2026-11-05; its tor 0.4.9.15 trails source-only stable 0.4.9.16.
"""
import hashlib
import http.client
import json
import os
import re
import shutil
import socket
import ssl
import subprocess
import tarfile
import time
import urllib.request
from pathlib import Path
from urllib.parse import quote

BUNDLE='https://archive.torproject.org/tor-package-archive/torbrowser/16.0/tor-expert-bundle-windows-x86_64-16.0.tar.gz'
BUNDLE_SHA256='2ee123e9167318a3742778ff704e75f01d4780f05a14522d451fe011011077c4'
FAUCET='zcash-faucet.vercel.app'


def exact(sock,size):
    data=b''
    while len(data)<size:
        part=sock.recv(size-len(data))
        if not part:raise ConnectionError('Tor SOCKS connection closed')
        data+=part
    return data


class TorHTTPS(http.client.HTTPSConnection):
    def __init__(self,host,port):
        if host != FAUCET or not 1<=port<=65535:
            raise ValueError('Only explicit test setup hosts and loopback Tor are allowed')
        super().__init__(host,timeout=90,context=ssl.create_default_context())
        self.socks_port=port

    def connect(self):
        # The target hostname is sent to SOCKS; it is never resolved locally.
        sock=socket.create_connection(('127.0.0.1',self.socks_port),timeout=30)
        try:
            sock.sendall(b'\x05\x01\x00')
            if exact(sock,2)!=b'\x05\x00':raise ConnectionError('Tor SOCKS negotiation failed; direct fallback forbidden')
            host=self.host.encode('idna')
            sock.sendall(b'\x05\x01\x00\x03'+bytes([len(host)])+host+b'\x01\xbb')
            version,status,_,kind=exact(sock,4)
            if version!=5 or status!=0:raise ConnectionError('Tor SOCKS request failed; direct fallback forbidden')
            length={1:4,4:16}.get(kind)
            if kind==3:length=exact(sock,1)[0]
            if length is None:raise ConnectionError('Invalid Tor SOCKS response')
            exact(sock,length+2)
            sock.settimeout(90)
            self.sock=self._context.wrap_socket(sock,server_hostname=self.host)
        except BaseException:
            sock.close();raise


def sanitized_error(value):
    """Retain explicit errors, scrub addresses/hex and bound output."""
    fields={key:value[key] for key in ('code','message','error') if key in value and isinstance(value[key],(str,int,float))}
    text=json.dumps(fields,ensure_ascii=True)
    text=re.sub(r'utest1[a-z0-9]+','[test address]',text,flags=re.I)
    return re.sub(r'[0-9a-fA-F]{32,}','[hex]',text)[:400]


def request(client,method,path,body=None):
    payload=json.dumps(body,separators=(',',':')).encode() if body is not None else None
    client.request(method,path,body=payload,headers={'Connection':'keep-alive','Content-Type':'application/json'})
    reply=client.getresponse()
    raw=reply.read(65537)
    if len(raw)>65536:raise ValueError('Testnet funding response exceeds bound')
    return reply.status,raw


def node_executable():
    installed=Path(r'C:\Program Files\nodejs\node.exe')
    node=str(installed) if installed.is_file() else shutil.which('node')
    if not node:raise RuntimeError('Existing Node runtime required for faucet work')
    feature=subprocess.run([node,'-p',"typeof require('node:crypto').argon2Sync"],
        capture_output=True,timeout=10,creationflags=0x08000000 if os.name=='nt' else 0)
    if feature.returncode or feature.stdout.strip()!=b'function':
        raise RuntimeError('Existing Node 24.7 or newer with built-in Argon2 is required')
    return node


def solve_work(challenge):
    node=node_executable()
    result=subprocess.run([node,str(Path(__file__).with_name('faucet_pow.cjs'))],
        input=json.dumps({k:challenge[k] for k in ('seed','difficulty')}).encode(),capture_output=True,timeout=200,
        creationflags=0x08000000 if os.name=='nt' else 0)
    if result.returncode:raise RuntimeError('Bounded faucet work did not complete; no claim')
    proof=json.loads(result.stdout)
    if not re.fullmatch(r'\d{1,15}',str(proof.get('nonce',''))):raise ValueError('Invalid nonce')
    return proof


def verify_unavailable_proxy():
    # Hold a non-listening loopback port so this cannot reach a real proxy or provider.
    with socket.socket() as reserved:
        reserved.bind(('127.0.0.1',0))
        client=TorHTTPS(FAUCET,reserved.getsockname()[1])
        try:
            client.request('GET','/api/status')
        except ConnectionRefusedError:
            return True
        finally:
            client.close()
    raise RuntimeError('Unavailable Tor proxy did not fail closed')


def bundle_archive(root, supplied=None):
    """Verify public cache bytes; supplied archives remain owned by the caller."""
    provided=supplied is not None
    archive=Path(supplied) if provided else Path(root)/'faucet-tor.tar.gz'
    if provided and (not archive.is_file() or archive.is_symlink()):raise ValueError('Supplied Tor bundle must be a regular file')
    digest=hashlib.sha256();total=0
    try:
        source=archive.open('rb') if provided else urllib.request.urlopen(BUNDLE,timeout=30)
        deadline=time.monotonic()+180
        with source:
            target=None if provided else archive.open('wb')
            try:
                while True:
                    if time.monotonic()>=deadline:raise TimeoutError('Tor bundle time budget exhausted')
                    # read1 returns available bytes, allowing deadline checks between network reads.
                    chunk=(source.read1 if hasattr(source,'read1') else source.read)(65536)
                    if time.monotonic()>=deadline:raise TimeoutError('Tor bundle time budget exhausted')
                    if not chunk:break
                    total+=len(chunk)
                    if total>128*1024*1024:raise ValueError('Tor archive exceeds budget')
                    digest.update(chunk)
                    if target is not None:target.write(chunk)
            finally:
                if target is not None:target.close()
        if digest.hexdigest()!=BUNDLE_SHA256:raise ValueError('Official Tor bundle digest mismatch')
        return archive,0 if provided else total
    except BaseException:
        if not provided:archive.unlink(missing_ok=True)
        raise


# Source: https://github.com/anukulpandey/zcash-faucet/blob/main/server/server.js
# Verified server blob: 25e617b8f3f3010e1e5b2b17bc81b9661afadf16 (version 1.3.1).
PARAMS=dict(parallelism=1,tagLength=32,memory=19456,passes=2)

def claim(address,root,port,negative):
    began=time.monotonic()
    ledger=Path(os.environ.get('CORE_FAUCET_LEDGER',str(Path(root)/'funding-claim-ledger.json')))
    if ledger.exists():raise RuntimeError('Claim already attempted; preserve ledger, no resubmission')
    client=TorHTTPS(FAUCET,port)
    state=None
    owns_ledger=False
    def save():ledger.write_text(json.dumps(state,indent=2)+'\n')
    try:
        client.connect()
        status,raw=request(client,'GET','/api/status')
        ready=json.loads(raw)
        if status!=200 or ready.get('network')!='testnet' or ready.get('healthy') is not True or ready.get('auth')!='pow':
            raise RuntimeError('Faucet not ready: '+sanitized_error(ready)+'; no claim')
        if float(ready.get('spendableTaz',0))<.1001 or ready.get('dripTaz')!=.1:
            raise RuntimeError('Unexpected faucet funding posture')
        status,raw=request(client,'GET','/api/challenge?address='+quote(address,safe=''))
        challenge=json.loads(raw)
        if status!=200 or challenge.get('ok') is not True:raise RuntimeError('Faucet challenge refused: '+sanitized_error(challenge)+'; no claim')
        if challenge.get('params')!=PARAMS or type(challenge.get('difficulty')) is not int or not 1<=challenge['difficulty']<=14:
            raise ValueError('Faucet work exceeds reviewed bounds')
        if not re.fullmatch('[0-9a-f]{32}',str(challenge.get('seed',''))) or not re.fullmatch('[0-9a-f]{64}',str(challenge.get('sig',''))):
            raise ValueError('Invalid signed challenge')
        if not time.time()+200<=challenge.get('exp',0)<=time.time()+905:raise ValueError('Challenge expiry')
        # This faucet binds its signature to the address, not IP or TLS session.
        # Close the challenge connection during CPU work; claim/poll share new TLS.
        client.close()
        proof=solve_work(challenge)
        nonce=proof.get('nonce','')
        if not re.fullmatch(r'\d{1,15}',nonce):raise ValueError('Invalid nonce')
        client=TorHTTPS(FAUCET,port);client.connect();pinned=client.sock
        if pinned is None:raise RuntimeError('Missing claim TLS connection; no claim')
        state=dict(provider='argon-testnet',status='request-starting',created_at=time.time(),claim_requests=1,
                   address_sha256=hashlib.sha256(address.encode()).hexdigest())
        with ledger.open('x') as stream:
            json.dump(state,stream);stream.flush();os.fsync(stream.fileno())
        owns_ledger=True
        payload=dict(address=address,pow={**{k:challenge[k] for k in ('seed','difficulty','exp','sig')},'nonce':nonce},**{'async':True})
        status,raw=request(client,'POST','/api/claim',payload)
        reply=json.loads(raw)
        if status!=202 or reply.get('ok') is not True or reply.get('pending') is not True:
            state.update(status='refused-or-unknown',http_status=status,server_error=sanitized_error(reply));save()
            raise RuntimeError('Faucet sole POST refused or uncertain: '+sanitized_error(reply)+'; no resubmission')
        claim_id=reply.get('id','')
        if not re.fullmatch('[0-9a-f]{16}',claim_id):raise RuntimeError('Unknown claim identity; no resubmission')
        state.update(status='pending',claim_id=claim_id);save()
        stop=time.monotonic()+330
        while time.monotonic()<stop:
            if client.sock is not pinned:raise RuntimeError('Claim TLS changed; preserve outcome, no resubmission')
            status,raw=request(client,'GET','/api/claim/'+claim_id)
            reply=json.loads(raw)
            if status!=200 or reply.get('ok') is not True:
                state.update(status='failed-or-unknown',http_status=status,server_error=sanitized_error(reply));save()
                raise RuntimeError('Claim failed or unknown: '+sanitized_error(reply)+'; no resubmission')
            if reply.get('pending') is True:time.sleep(2);continue
            txid=reply.get('txid','')
            if not re.fullmatch('[0-9a-f]{64}',str(txid)):raise RuntimeError('Claim lacks txid; no resubmission')
            if reply.get('amountZat')!=10000000:raise RuntimeError('Unexpected test funding amount')
            state.update(status='broadcast',txid=txid);save()
            return dict(provider='argon-testnet',txid=txid,claim_id=claim_id,claim_requests=1,
                tor_transport_verified=True,proxy_unavailable_failed_closed=negative,persistent_tor_connection=True,
                challenge_claim_same_tls=False,claim_poll_same_tls=True,challenge_ip_bound=False,
                route_evidence='Pinned owned Tor; unavailable-proxy negative; validated TLS; sole claim and status polling share TLS',
                bundle_sha256=BUNDLE_SHA256,source_blob='25e617b8f3f3010e1e5b2b17bc81b9661afadf16',
                source_url='https://github.com/anukulpandey/zcash-faucet/blob/main/server/server.js',
                proof_seconds=proof['seconds'],proof_workers=proof['workers'],seconds=time.monotonic()-began)
        raise TimeoutError('Claim pending; preserve ledger and never resubmit')
    except BaseException:
        if owns_ledger and state['status'] in ('request-starting','pending'):
            state['status']='outcome-uncertain';save()
        raise
    finally:client.close()

def fund(address,root):
    if not re.fullmatch('utest1[a-z0-9]{40,505}',address):
        raise ValueError('Only a testnet unified address may be funded')
    root=Path(root)
    ledger=Path(os.environ.get('CORE_FAUCET_LEDGER',str(root/'funding-claim-ledger.json')))
    if ledger.exists():raise RuntimeError('Claim already attempted; preserve ledger, no resubmission')
    proxy_unavailable_failed_closed=verify_unavailable_proxy()
    setup_started=time.monotonic()
    supplied_bundle=os.environ.get('CORE_FAUCET_TOR_BUNDLE')
    archive,total=bundle_archive(root,supplied_bundle)
    folder=root/'faucet-tor';folder.mkdir()
    expanded=0
    with tarfile.open(archive,'r:gz') as bundle:
        members=bundle.getmembers()
        if len(members)>2000:raise ValueError('Tor bundle member count exceeds budget')
        for member in members:
            parts=Path(member.name).parts
            if 'tor' not in parts:continue
            relative=Path(*parts[parts.index('tor'):])
            if len(relative.parts)!=2 or relative.suffix.lower() not in ('.exe','.dll'):continue
            if not member.isfile() or member.issym() or member.islnk():raise ValueError('Tor executable must be regular data')
            target=(folder/relative).resolve()
            if not target.is_relative_to(folder.resolve()):raise ValueError('Tor member escaped output')
            expanded+=member.size
            if expanded>128*1024*1024:raise ValueError('Tor executable expansion exceeds budget')
            target.parent.mkdir(exist_ok=True)
            with bundle.extractfile(member) as source,target.open('wb') as output:shutil.copyfileobj(source,output,1024*1024)
    if supplied_bundle is None:archive.unlink()
    tor=folder/'tor/tor.exe'
    version=subprocess.check_output([str(tor),'--version'],text=True,timeout=10,creationflags=0x08000000).splitlines()[0]
    if 'Tor version 0.4.9.15' not in version:raise ValueError('Unexpected stable Tor binary version')
    with socket.socket() as listener:
        listener.bind(('127.0.0.1',0));port=listener.getsockname()[1]
    torrc=root/'faucet-torrc'
    torrc.write_bytes(b'')
    console=root/'faucet-tor-console.log'
    log=console
    console_stream=console.open('wb')
    process=subprocess.Popen([str(tor),'-f',str(torrc),'--ClientOnly','1','--DataDirectory',str(root/'faucet-tor-data'),
                              '--SocksPort','127.0.0.1:'+str(port),'--AvoidDiskWrites','1',
                              '--Log','notice stdout'],
                             stdout=console_stream,stderr=subprocess.STDOUT,creationflags=0x08000000)
    started=time.monotonic()
    setup_seconds=started-setup_started
    try:
        deadline=started+180
        while time.monotonic()<deadline:
            if process.poll() is not None:
                detail=log.read_text(errors='replace')[-1800:] if log.exists() else console.read_text(errors='replace')[-1800:]
                raise RuntimeError('Task Tor exited before bootstrap: '+detail)
            if log.exists():
                if log.stat().st_size>131072:raise RuntimeError('Task Tor log exceeded bound')
                if 'Bootstrapped 100%' in log.read_text(errors='replace'):break
            if console.stat().st_size>131072:raise RuntimeError('Task Tor console exceeded bound')
            time.sleep(.5)
        else:raise RuntimeError('Task Tor bootstrap timed out; direct fallback forbidden')
        bootstrap_seconds=time.monotonic()-started
        result=claim(address,root,port,proxy_unavailable_failed_closed)
        result.update(bundle_sha256=BUNDLE_SHA256,download_bytes=total,bundle_cache_hit=supplied_bundle is not None,
                      expanded_bytes=expanded,setup_seconds=setup_seconds,bootstrap_seconds=bootstrap_seconds,
                      tor_binary_sha256=hashlib.sha256(tor.read_bytes()).hexdigest(),tor_version=version,
                      seconds=time.monotonic()-setup_started)
        return result
    finally:
        process.terminate()
        try:process.wait(timeout=10)
        except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10)
        if process.poll() is None:raise RuntimeError('Task Tor did not exit')
        console_stream.close()
