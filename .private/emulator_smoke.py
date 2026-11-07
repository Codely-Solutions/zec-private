"""Blank-guest isolation/cancellation diagnostic: no APK, wallet, faucet or payment."""
import json
import os
from pathlib import Path
import time

from emulator_session import EmulatorSession

root = Path(os.environ['CODEX_GUARD_OUTPUT'])
slot = int(os.environ['CODEX_EMULATOR_SLOT'])
name = os.environ['CODEX_EMULATOR_WORKER']
barrier = Path(os.environ['CODEX_EMULATOR_BATCH_BARRIER'])
with EmulatorSession(root, guest_memory=3072, slot=slot) as session:
    adb = session.adb
    token = 'zec-private-isolation-' + name
    token_file = root / 'isolation-token.txt'
    token_file.write_text(token)
    adb('push', str(token_file), '/data/local/tmp/zec-private-isolation')
    boot_id = adb('shell', 'cat', '/proc/sys/kernel/random/boot_id').stdout.decode().strip()
    serial = adb('get-serialno').stdout.decode().strip()
    marker = adb('shell', 'cat', '/data/local/tmp/zec-private-isolation').stdout.decode().strip()
    bridge_port = session.ports.bridge
    adb('forward', 'tcp:' + str(bridge_port), 'tcp:8777')
    forwards = adb('forward', '--list').stdout.decode().splitlines()
    assert [serial, f'tcp:{bridge_port}', 'tcp:8777'] in [line.split() for line in forwards]
    proof = dict(slot=slot, serial=serial, boot_id=boot_id, marker=marker,
                 guest_memory_mib=3072, wallet_access=False, apk_installs=0,
                 faucet_claims=0, valid_transaction_submissions=0)
    proof.update(private_bridge_port=bridge_port, grpc_port=session.ports.grpc, private_forward_verified=True)
    disk = session.guest_data_before.splitlines()[-1].split()
    proof['guest_data_kib'] = dict(total=int(disk[1]), used=int(disk[2]), available=int(disk[3]))
    (root / 'smoke-observation.json').write_text(json.dumps(proof, indent=2))
    assert marker == token and serial == 'emulator-' + str(5580 + slot * 2)
    pending = barrier / (name + '.pending')
    pending.write_text(json.dumps(proof))
    pending.replace(barrier / (name + '.json'))
    deadline = time.monotonic() + 180
    while len(list(barrier.glob('*.json'))) < 2:
        if time.monotonic() >= deadline:
            raise TimeoutError('Second isolated guest did not become ready')
        time.sleep(.2)
    peer = [json.loads(p.read_text()) for p in barrier.glob('*.json')]
    assert len({p['serial'] for p in peer}) == len({p['boot_id'] for p in peer}) == 2
    assert adb('shell', 'cat', '/data/local/tmp/zec-private-isolation').stdout.decode().strip() == token
    proof['concurrent_isolation_passed'] = True
    pending = root / 'smoke-result.pending'
    pending.write_text(json.dumps(proof, indent=2))
    pending.replace(root / 'smoke-result.json')
    if os.environ['CODEX_EMULATOR_SMOKE'] == 'expected-failure':
        # Give the peer time to complete its independent read before cancelling it.
        deadline = time.monotonic() + 20
        other = root.parent / ('second' if name == 'first' else 'first') / 'smoke-result.json'
        while not other.exists():
            if time.monotonic() >= deadline:
                raise TimeoutError('Peer isolation check incomplete')
            time.sleep(.1)
        session.close()
        assert session.cleanup.get('emulatorExited') and session.cleanup.get('privatePortsClosed')
        proof.update(expected_failure=True, graceful_cleanup=session.cleanup)
        pending = root / 'smoke-result.pending'
        pending.write_text(json.dumps(proof, indent=2))
        pending.replace(root / 'smoke-result.json')
        raise RuntimeError('Intentional batch cancellation probe')
    time.sleep(120)
    raise AssertionError('Fail-fast cancellation did not stop the peer')
