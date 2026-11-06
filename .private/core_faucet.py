"""One honest TAZ faucet claim over one persistent, task-owned Tor connection.

No app keys enter this helper. Native SDK Tor remains enabled in the wallet.
Funding uses pinned owned Tor and a real unavailable-proxy negative check;
an external exit-IP service is not a dependency of wallet acceptance.
The official Windows expert bundle is the latest stable prebuilt distribution
verified 2026-11-05; its tor 0.4.9.15 trails source-only stable 0.4.9.16.
"""
import hashlib
import http.client
import json
import re
import shutil
import socket
import ssl
import subprocess
import tarfile
import time
import urllib.request
from pathlib import Path

BUNDLE='https://dist.torproject.org/torbrowser/16.0/tor-expert-bundle-windows-x86_64-16.0.tar.gz'
BUNDLE_SHA256='2ee123e9167318a3742778ff704e75f01d4780f05a14522d451fe011011077c4'
FAUCET='zcashfaucet.jinolabs.xyz'


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


def response(connection,method,path,body=None):
    payload=None if body is None else json.dumps(body,separators=(',',':')).encode()
    headers={'Connection':'keep-alive','Cache-Control':'no-store','Accept':'application/json'}
    if payload is not None:headers['Content-Type']='application/json'
    connection.request(method,path,body=payload,headers=headers)
    reply=connection.getresponse()
    data=reply.read(65537)
    if len(data)>65536:raise ValueError('Faucet response exceeded bound')
    try:value=json.loads(data)
    except ValueError:raise RuntimeError('Faucet returned non-JSON HTTP '+str(reply.status)) from None
    if reply.status>=400:
        reason=str(value.get('error',value.get('message','request refused')))
        reason=re.sub(r'utest1[a-z0-9]+','[isolated test address]',reason)[:500]
        raise RuntimeError('Faucet HTTP '+str(reply.status)+': '+reason)
    return value


def verify_unavailable_proxy():
    # Hold a non-listening loopback port so this cannot reach a real proxy or provider.
    with socket.socket() as reserved:
        reserved.bind(('127.0.0.1',0))
        client=TorHTTPS(FAUCET,reserved.getsockname()[1])
        try:
            client.request('GET','/api/pow/challenge')
        except ConnectionRefusedError:
            return True
        finally:
            client.close()
    raise RuntimeError('Unavailable Tor proxy did not fail closed')


def fund(address,root):
    if not re.fullmatch('utest1[a-z0-9]{40,505}',address):
        raise ValueError('Only a testnet unified address may be funded')
    proxy_unavailable_failed_closed=verify_unavailable_proxy()
    root=Path(root)
    setup_started=time.monotonic()
    archive=root/'faucet-tor.tar.gz'
    digest=hashlib.sha256();total=0
    with urllib.request.urlopen(BUNDLE,timeout=30) as source,archive.open('wb') as target:
        while chunk:=source.read(1024*1024):
            total+=len(chunk)
            if total>128*1024*1024:raise ValueError('Tor archive exceeds budget')
            digest.update(chunk);target.write(chunk)
    if digest.hexdigest()!=BUNDLE_SHA256:raise ValueError('Official Tor bundle digest mismatch')
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
    archive.unlink()
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
        client=TorHTTPS(FAUCET,port)
        try:
            # Strict custom SOCKS connection; the pinned owned Tor process is fully bootstrapped.
            # An external exit-IP service is not required to establish this route.
            client.connect()
            route=dict(tor_transport_verified=True,proxy_unavailable_failed_closed=proxy_unavailable_failed_closed,
                       tor_exit_verified=False,route_evidence='Pinned owned Tor SOCKS; unavailable-proxy negative; validated faucet TLS')
            challenge=response(client,'GET','/api/pow/challenge')
            pinned=client.sock
            if pinned is None:raise RuntimeError('Faucet closed the IP-bound challenge connection')
            difficulty=int(challenge['difficulty'])
            if not 1<=difficulty<=22:raise RuntimeError('Faucet work exceeds bounded test budget')
            proof_started=time.monotonic();nonce=0
            while True:
                value=hashlib.sha256((challenge['seed']+':'+str(nonce)).encode()).digest()
                if int.from_bytes(value,'big') < (1 << (256-difficulty)):break
                nonce+=1
                if nonce%8192==0 and time.monotonic()-proof_started>30:raise RuntimeError('Faucet work time budget exhausted')
            proof={key:challenge[key] for key in ('seed','difficulty','exp','sig')};proof['nonce']=str(nonce)
            proof_seconds=time.monotonic()-proof_started
            if client.sock is not pinned:raise RuntimeError('Faucet Tor connection changed before proof submission')
            claim_started=time.monotonic()
            claim=response(client,'POST','/api/faucet',dict(address=address,network='taz',pow=proof))
            if not claim.get('ok') or not re.fullmatch('[0-9a-f]{64}',str(claim.get('txid',''))):
                raise RuntimeError('Faucet did not establish a successful testnet payment; no retry')
            return dict(**route,txid=claim['txid'],provider='jinolabs-testnet',
                        persistent_tor_connection=True,bundle_sha256=BUNDLE_SHA256,
                        download_bytes=total,expanded_bytes=expanded,setup_seconds=setup_seconds,
                        bootstrap_seconds=bootstrap_seconds,proof_compute_seconds=proof_seconds,
                        claim_seconds=time.monotonic()-claim_started,
                        tor_binary_sha256=hashlib.sha256(tor.read_bytes()).hexdigest(),tor_version=version,
                        seconds=time.monotonic()-setup_started)
        finally:client.close()
    finally:
        process.terminate()
        try:process.wait(timeout=10)
        except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10)
        if process.poll() is None:raise RuntimeError('Task Tor did not exit')
        console_stream.close()
