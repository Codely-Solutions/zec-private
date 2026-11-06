"""Funding setup cannot bypass SOCKS when the proxy is absent or refuses negotiation."""
import unittest
from unittest.mock import patch
from core_faucet import TorHTTPS,FAUCET,verify_unavailable_proxy

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

if __name__=='__main__':unittest.main()
