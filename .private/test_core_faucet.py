"""Funding setup cannot bypass SOCKS when the proxy is absent or refuses negotiation."""
import unittest
import hashlib
import io
import tempfile
from pathlib import Path
from unittest.mock import patch
from core_faucet import TorHTTPS,FAUCET,verify_unavailable_proxy,bundle_archive

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
