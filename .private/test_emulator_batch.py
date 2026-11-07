"""Bounded scheduler checks; child processes never start an emulator."""
import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

import emulator_batch as batch


class BatchTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=os.environ.get('CODEX_GUARD_OUTPUT'))
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.adapter = self.root / 'adapter.py'
        self.adapter.write_text('''import json,os,time
from pathlib import Path
r=Path(os.environ['CODEX_GUARD_OUTPUT']);s=int(os.environ['CODEX_EMULATOR_SLOT'])
m=Path(os.environ['CODEX_EMULATOR_MANIFEST'])
(r/'identity.json').write_text(json.dumps(dict(slot=s,root=str(r),manifest=json.loads(m.read_text()))))
''')
        self.manifest = self.root / 'manifest.json'
        self.manifest.write_text('{"probeKind":"fixture"}')
        self.workers = [dict(name=n, adapter=str(self.adapter), manifest=str(self.manifest),
            manifest_sha256=hashlib.sha256(self.manifest.read_bytes()).hexdigest(), seconds=3)
            for n in ('first', 'second')]
        self.plan = self.root / 'plan.json'

    def read(self, workers):
        self.plan.write_text(json.dumps(dict(schema=1, workers=workers)))
        with patch.object(batch, 'PROJECT', self.root):
            return batch.read_plan(self.plan)

    def execute(self, workers):
        output = self.root / 'output'
        output.mkdir()
        with patch.object(batch, 'prepare_image', return_value=self.root), \
                patch.object(batch, 'verify_image', return_value=self.root), patch.object(batch, 'PROJECT', self.root):
            return batch.execute(workers, output)

    def test_rejects_extra_workers_duplicate_names_and_stale_manifests(self):
        for workers in (self.workers * 2, [self.workers[0], self.workers[0]]):
            with self.assertRaises(ValueError):
                self.read(workers)
        changed = copy.deepcopy(self.workers)
        changed[0]['manifest_sha256'] = '0' * 64
        with self.assertRaises(ValueError):
            self.read(changed)

    def test_rejects_unbounded_deadlines_and_external_adapters(self):
        for value in (0, 3001, True):
            changed = copy.deepcopy(self.workers)
            changed[0]['seconds'] = value
            with self.assertRaises(ValueError):
                self.read(changed)
        changed = copy.deepcopy(self.workers)
        changed[0]['adapter'] = str(Path(__file__).resolve())
        with self.assertRaises(ValueError):
            self.read(changed)

    def test_success_keeps_separate_slots_roots_and_manifest_copies(self):
        result = self.execute(self.read(self.workers))
        self.assertTrue(result['passed'])
        identities = [json.loads((Path(w['root']) / 'identity.json').read_text()) for w in result['workers']]
        self.assertEqual([0, 1], [i['slot'] for i in identities])
        self.assertEqual(2, len({i['root'] for i in identities}))
        self.assertTrue(all(i['manifest'] == {'probeKind': 'fixture'} for i in identities))

    def test_peer_failure_cancels_running_worker(self):
        with self.adapter.open('a') as f:
            f.write('''
if s==0:
 deadline=time.monotonic()+2
 while not (r.parent/'second'/'identity.json').exists():
  assert time.monotonic()<deadline
  time.sleep(.02)
 raise SystemExit(7)
time.sleep(20)
''')
        started = time.monotonic()
        result = self.execute(self.read(self.workers))
        self.assertFalse(result['passed'])
        self.assertEqual('failed', result['workers'][0]['status'])
        self.assertEqual('cancelled-after-peer-failure', result['workers'][1]['status'])
        self.assertLess(time.monotonic() - started, 5)

    def test_worker_deadline_cancels_the_batch(self):
        with self.adapter.open('a') as f:
            f.write('\ntime.sleep(20)\n')
        self.workers[0]['seconds'] = 1
        result = self.execute(self.read(self.workers))
        self.assertFalse(result['passed'])
        self.assertEqual('timed-out', result['workers'][0]['status'])
        self.assertEqual('cancelled-after-peer-failure', result['workers'][1]['status'])


if __name__ == '__main__':
    unittest.main()
