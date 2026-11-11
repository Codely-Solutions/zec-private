"""Funding setup cannot bypass SOCKS when the proxy is absent or refuses negotiation."""
import unittest
import hashlib
import io
from itertools import count
import tempfile
import json
import os
from pathlib import Path
from unittest.mock import MagicMock, Mock, patch
from core_faucet import TorHTTPS,FAUCET,verify_unavailable_proxy,bundle_archive
import core_faucet
from contextlib import contextmanager


class ReadinessTest(unittest.TestCase):
    ready=dict(network='testnet',healthy=True,auth='pow',spendableTaz=1,dripTaz=.1)

    def test_status_only_and_safe_result(self):
        with patch('core_faucet.request',return_value=(200,json.dumps({**self.ready,'secret':'private'}).encode())) as request:
            result=core_faucet.status_readiness(object())
        self.assertEqual(request.call_args.args[1:],('GET','/api/status'))
        self.assertEqual(result['claim_requests'],0)
        self.assertNotIn('private',json.dumps(result))

    def test_malformed_unavailable_and_wrong_network_fail_before_claim(self):
        values=[[],{**self.ready,'network':'mainnet'},{**self.ready,'healthy':False},
                {**self.ready,'spendableTaz':'1'},{**self.ready,'spendableTaz':float('nan')},
                {**self.ready,'spendableTaz':.1},{**self.ready,'dripTaz':True}]
        for value in values:
            with self.subTest(value=value),patch('core_faucet.request',return_value=(200,json.dumps(value).encode())),patch('core_faucet.claim') as claim:
                with self.assertRaises((ValueError,RuntimeError)):core_faucet.status_readiness(object())
                claim.assert_not_called()

    def test_shared_tor_lifetime_and_no_claim_for_readiness(self):
        events=[]
        @contextmanager
        def tor(root):
            events.append('start')
            try:yield 12345,True,dict(setup_seconds=1)
            finally:events.append('stop')
        with tempfile.TemporaryDirectory() as root,patch('core_faucet.owned_tor',tor),patch('core_faucet.TorHTTPS'),patch('core_faucet.status_readiness',return_value=dict(passed=True,claim_requests=0)),patch('core_faucet.claim',return_value={}) as claim:
            core_faucet.readiness(root)
            claim.assert_not_called()
            self.assertEqual(events,['start','stop'])
            events.clear()
            with core_faucet.ready_session(root) as (_,fund):
                self.assertEqual(events,['start'])
                fund('utest1'+'a'*50)
                self.assertEqual(events,['start'])
            self.assertEqual(events,['start','stop'])
            claim.assert_called_once()

    def test_readiness_failure_scrubs_exception_and_suppresses_claim(self):
        with tempfile.TemporaryDirectory() as root,patch('core_faucet.owned_tor',side_effect=ConnectionError('private seed address')),patch('core_faucet.claim') as claim:
            with self.assertRaisesRegex(RuntimeError,'ConnectionError') as error:core_faucet.readiness(root)
            record=(Path(root)/'faucet-readiness.json').read_text()
            self.assertNotIn('private',record+str(error.exception))
            self.assertEqual(json.loads(record)['exception_class'],'ConnectionError')
            claim.assert_not_called()

    def test_existing_ledger_stops_before_tor(self):
        with tempfile.TemporaryDirectory() as root,patch.dict(os.environ,{},clear=True),patch('core_faucet.owned_tor') as tor:
            ledger=Path(root)/'funding-claim-ledger.json'
            ledger.write_text('prior uncertain claim')
            with self.assertRaises(RuntimeError):core_faucet.readiness(root)
            tor.assert_not_called()
            self.assertEqual(ledger.read_text(),'prior uncertain claim')

    def test_stuck_bootstrap_restarts_before_exposing_a_proxy(self):
        starts = []
        listener = MagicMock()
        listener.__enter__.return_value.getsockname.side_effect = [('127.0.0.1', 12345), ('127.0.0.1', 12346)]

        def start(command, *, stdout, **_):
            if starts:
                starts[0].terminate.assert_called_once()
                stdout.write(b'Bootstrapped 100%: Done\n')
                stdout.flush()
            process = Mock()
            process.poll.return_value = None
            process.terminate.side_effect = lambda: setattr(process.poll, 'return_value', 0)
            starts.append(process)
            return process

        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            binary = root / 'tor-test'
            binary.write_bytes(b'isolated test binary')
            with patch('core_faucet.tor_binary', return_value=(binary, {})), \
                    patch('core_faucet.verify_unavailable_proxy', return_value=True), \
                    patch('core_faucet.subprocess.check_output', return_value='Tor version 0.4.9.15\n'), \
                    patch('core_faucet.subprocess.Popen', side_effect=start), \
                    patch('core_faucet.socket.socket', return_value=listener), \
                    patch('core_faucet.time.monotonic', side_effect=count(0, 45)), patch('core_faucet.time.sleep'):
                with core_faucet.owned_tor(root) as (port, _, evidence):
                    self.assertEqual(port, 12346)
                    self.assertEqual(evidence['tor_start_attempts'], 2)
                    starts[1].terminate.assert_not_called()
                starts[1].terminate.assert_called_once()

    def test_ready_funding_rejects_non_testnet_before_claim(self):
        @contextmanager
        def tor(root):yield 12345,True,{}
        with tempfile.TemporaryDirectory() as root,patch('core_faucet.owned_tor',tor),patch('core_faucet.TorHTTPS'),patch('core_faucet.status_readiness',return_value=dict(passed=True,claim_requests=0)),patch('core_faucet.claim') as claim:
            with core_faucet.ready_session(root) as (_,fund):
                for address in ('u1'+'a'*50,'utest1short','utest1'+'A'*50,None):
                    with self.subTest(address=address),self.assertRaises(ValueError):fund(address)
            claim.assert_not_called()


class ClaimTest(unittest.TestCase):
    address='utest1'+'a'*50

    def replies(self):
        return [(200,dict(network='testnet',healthy=True,auth='pow',spendableTaz=1,dripTaz=.1)),
                (200,dict(ok=True,params=core_faucet.PARAMS,difficulty=1,seed='a'*32,sig='b'*64,exp=1000)),
                (202,dict(ok=True,pending=True,id='c'*16)),
                (200,dict(ok=True,txid='d'*64,amountZat=10000000))]

    def run_claim(self,root,replies):
        self.calls=[]
        def request(client,method,path,body=None):
            self.calls.append((method,path,body))
            if method=='POST':
                self.assertEqual(json.loads((Path(root)/'funding-claim-ledger.json').read_text())['status'],'request-starting')
            value=replies.pop(0)
            if isinstance(value,Exception):raise value
            return value[0],json.dumps(value[1]).encode()
        with patch.dict(os.environ,{},clear=True),patch('core_faucet.TorHTTPS') as client,patch('core_faucet.request',side_effect=request),patch('core_faucet.solve_work',return_value=dict(nonce='0',seconds=.1,workers=2)),patch('core_faucet.time.time',return_value=100):
            client.return_value.sock=object()
            return core_faucet.claim(self.address,root,12345,True)

    def test_single_claim_and_broadcast_ledger(self):
        with tempfile.TemporaryDirectory() as root:
            result=self.run_claim(root,self.replies())
            self.assertEqual(result['txid'],'d'*64)
            self.assertEqual([method for method,_,_ in self.calls],['GET','GET','POST','GET'])
            ledger=Path(root)/'funding-claim-ledger.json'
            self.assertEqual(json.loads(ledger.read_text())['status'],'broadcast')
            with patch('core_faucet.TorHTTPS') as client,self.assertRaisesRegex(RuntimeError,'already attempted'):
                core_faucet.claim(self.address,root,12345,True)
            client.assert_not_called()

    def test_post_transport_failure_preserves_uncertainty_without_retry(self):
        with tempfile.TemporaryDirectory() as root:
            replies=self.replies()[:2]+[ConnectionError('closed')]
            with self.assertRaises(ConnectionError):self.run_claim(root,replies)
            state=json.loads((Path(root)/'funding-claim-ledger.json').read_text())
            self.assertEqual(state['status'],'outcome-uncertain')
            self.assertEqual(sum(method=='POST' for method,_,_ in self.calls),1)

    def test_explicit_ledger_blocks_claim_without_transport(self):
        with tempfile.TemporaryDirectory() as root:
            ledger=Path(root)/'retained-ledger.json';ledger.write_text('uncertain prior attempt')
            with patch.dict(os.environ,{'CORE_FAUCET_LEDGER':str(ledger)}),patch('core_faucet.TorHTTPS') as client:
                with self.assertRaisesRegex(RuntimeError,'already attempted'):
                    core_faucet.claim(self.address,root,12345,True)
            client.assert_not_called()
            self.assertEqual(ledger.read_text(),'uncertain prior attempt')

    def test_exclusive_ledger_race_preserves_other_attempt(self):
        with tempfile.TemporaryDirectory() as root:
            ledger=Path(root)/'funding-claim-ledger.json'
            original=Path.open
            def raced(path,mode='r',*args,**kwargs):
                if path==ledger and mode=='x':
                    with original(path,'w') as stream:stream.write('other attempt')
                return original(path,mode,*args,**kwargs)
            with patch.object(Path,'open',raced),self.assertRaises(FileExistsError):
                self.run_claim(root,self.replies())
            self.assertEqual(ledger.read_text(),'other attempt')
            self.assertFalse(any(method=='POST' for method,_,_ in self.calls))

    def test_server_error_scrubs_address_and_hex(self):
        value=core_faucet.sanitized_error(dict(code='REFUSED',message=self.address+' '+('f'*64)+' '+('x'*500),body='private'))
        self.assertLessEqual(len(value),400)
        self.assertIn('REFUSED',value)
        self.assertNotIn(self.address,value)
        self.assertNotIn('f'*64,value)
        self.assertNotIn('private',value)

    @unittest.skipUnless(os.environ.get('CORE_FAUCET_TEST_POW')=='1', 'Explicit local Node/PoW check; native acceptance executes real work')
    def test_bounded_local_pow(self):
        proof=core_faucet.solve_work(dict(seed='0'*32,difficulty=0))
        self.assertEqual(proof['workers'],2)
        self.assertRegex(proof['nonce'],r'^\d{1,15}$')

class TransportTest(unittest.TestCase):
    def test_real_unavailable_proxy(self):
        self.assertTrue(verify_unavailable_proxy())

    def test_proxy_failure_never_connects_to_destination(self):
        with patch('core_faucet.socket.create_connection',side_effect=ConnectionRefusedError) as connect:
            client=TorHTTPS(FAUCET,12345)
            with self.assertRaises(ConnectionRefusedError):client.request('GET','/api/pow/challenge')
            client.close()
            connect.assert_called_once_with(('127.0.0.1',12345),timeout=30)

    def test_proxy_rejection_closes_socket_without_fallback(self):
        class Rejected:
            closed=False
            def sendall(self,data):pass
            def recv(self,size):return b'\x05\xff'
            def close(self):self.closed=True
        sock=Rejected()
        with patch('core_faucet.socket.create_connection',return_value=sock) as connect:
            client=TorHTTPS(FAUCET,12345)
            with self.assertRaisesRegex(ConnectionError,'direct fallback forbidden'):client.connect()
            client.close()
            connect.assert_called_once_with(('127.0.0.1',12345),timeout=30)
        self.assertTrue(sock.closed)

    def test_unrelated_setup_hosts_and_ports_rejected(self):
        for host,port in [('check.torproject.org',12345),('example.invalid',12345),(FAUCET,0),(FAUCET,65536)]:
            with self.subTest(host=host,port=port),self.assertRaises(ValueError):TorHTTPS(host,port)


class BundleTest(unittest.TestCase):
    def test_verified_cache_avoids_download_and_preserves_file(self):
        with tempfile.TemporaryDirectory() as directory:
            cache=Path(directory)/'cache';cache.write_bytes(b'public bundle')
            with patch('core_faucet.BUNDLE_SHA256',hashlib.sha256(cache.read_bytes()).hexdigest()),patch('core_faucet.urllib.request.urlopen') as download:
                archive,total=bundle_archive(directory,cache)
            self.assertEqual(archive,cache);self.assertEqual(total,0)
            self.assertEqual(cache.read_bytes(),b'public bundle');download.assert_not_called()

    def test_invalid_cache_is_preserved_without_fallback(self):
        with tempfile.TemporaryDirectory() as directory:
            cache=Path(directory)/'cache';cache.write_bytes(b'corrupt')
            with patch('core_faucet.urllib.request.urlopen') as download:
                with self.assertRaisesRegex(ValueError,'digest mismatch'):bundle_archive(directory,cache)
            self.assertEqual(cache.read_bytes(),b'corrupt');download.assert_not_called()

    def test_default_download_validates_and_counts_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            data=b'public bundle'
            with patch('core_faucet.BUNDLE_SHA256',hashlib.sha256(data).hexdigest()),patch('core_faucet.urllib.request.urlopen',return_value=io.BytesIO(data)) as download:
                archive,total=bundle_archive(directory)
            self.assertEqual(archive.read_bytes(),data);self.assertEqual(total,len(data));download.assert_called_once()

    def test_failed_download_removes_partial_archive(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch('core_faucet.urllib.request.urlopen',return_value=io.BytesIO(b'corrupt')):
                with self.assertRaisesRegex(ValueError,'digest mismatch'):bundle_archive(directory)
            self.assertFalse((Path(directory)/'faucet-tor.tar.gz').exists())

    def test_total_deadline_removes_partial_archive(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch('core_faucet.urllib.request.urlopen',return_value=io.BytesIO(b'bytes')),patch('core_faucet.time.monotonic',side_effect=[0,0,181]):
                with self.assertRaisesRegex(TimeoutError,'time budget'):bundle_archive(directory)
            self.assertFalse((Path(directory)/'faucet-tor.tar.gz').exists())

    def test_missing_cache_never_downloads(self):
        with tempfile.TemporaryDirectory() as directory,patch('core_faucet.urllib.request.urlopen') as download:
            with self.assertRaisesRegex(ValueError,'regular file'):bundle_archive(directory,Path(directory)/'missing')
            download.assert_not_called()

if __name__=='__main__':unittest.main()
