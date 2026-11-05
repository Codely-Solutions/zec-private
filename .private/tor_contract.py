"""Bounded CI Tor process for unfunded API checks; never permits direct fallback."""
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    tor = Path('/usr/bin/tor')
    if not tor.is_file():
        raise RuntimeError('Managed Tor package is required')
    with tempfile.TemporaryDirectory(prefix='private-contract-', dir=os.environ['RUNNER_TEMP']) as folder:
        data = Path(folder)
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            port = listener.getsockname()[1]
        log = data / 'bootstrap.log'
        process = subprocess.Popen([
            str(tor), '--DataDirectory', str(data / 'state'), '--SocksPort', f'127.0.0.1:{port}',
            '--ClientOnly', '1', '--Log', 'notice file ' + str(log), '--AvoidDiskWrites', '1',
        ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            deadline = time.monotonic() + 180
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError('Tor exited before bootstrap')
                if log.exists():
                    if log.stat().st_size > 262144:
                        raise RuntimeError('Tor bootstrap log exceeded bound')
                    if 'Bootstrapped 100%' in log.read_text():
                        break
                time.sleep(1)
            else:
                raise RuntimeError('Tor bootstrap timed out; direct fallback forbidden')
            env = dict(os.environ, PRIVATE_TOR_SOCKS=f'127.0.0.1:{port}')
            subprocess.run([sys.executable, '-B', str(ROOT / '.private/live_near.py')],
                           cwd=ROOT, env=env, check=True, timeout=600)
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
            if process.poll() is None:
                raise RuntimeError('Task Tor process did not exit')


if __name__ == '__main__':
    main()
