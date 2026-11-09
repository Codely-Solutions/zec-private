import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import dev_session as session


class SessionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.source = self.base/'source'
        self.source.mkdir()
        self.root = self.base/'root'
        self.root.mkdir()
        self.evidence = self.base/'evidence'
        self.evidence.mkdir()
        self.git('init', '-q')
        self.git('config', 'user.email', 'test@example.invalid')
        self.git('config', 'user.name', 'Test')
        (self.source/'value').write_text('one')
        self.git('add', '.')
        self.git('commit', '-qm', 'one')
        self.first = self.git('rev-parse', 'HEAD')
        (self.source/'value').write_text('two')
        self.git('commit', '-qam', 'two')
        self.second = self.git('rev-parse', 'HEAD')

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.source), *args], stderr=subprocess.STDOUT).decode().strip()

    def operation(self, commit, **properties):
        return dict(source=commit, config=dict(tasks=[':ui-lib:compileZcashmainnetFossDebugKotlin'], properties=properties))

    def run_session(self, operations, runner, **kw):
        return session.execute(dict(source=str(self.source), operations=operations, **kw), self.root, self.evidence, runner)

    def test_warm_changed_source_and_configuration(self):
        seen = []
        def runner(command, cwd, seconds, root, name, **kwargs):
            cache = root/'project-cache'
            seen.append((cwd, (cwd/'value').read_text(), cache.exists(), command))
            cache.mkdir(exist_ok=True)
            return dict(exitCode=0, ownedSurvivors=[])
        self.run_session([self.operation(self.first), self.operation(self.first), self.operation(self.second),
                          self.operation(self.second, example='changed')], runner)
        self.assertEqual([s[1] for s in seen], ['one', 'one', 'two', 'two'])
        self.assertEqual([s[2] for s in seen], [False, True, True, True])
        receipts = [json.loads(p.read_text()) for p in sorted(self.evidence.glob('*.json'))]
        self.assertEqual(receipts[0]['binding'], receipts[1]['binding'])
        self.assertNotEqual(receipts[1]['binding'], receipts[2]['binding'])
        self.assertTrue(receipts[3]['configuration_changed'])

    def test_failure_and_interruption_stop(self):
        for error in (RuntimeError('fail'), KeyboardInterrupt()):
            with self.subTest(error=type(error).__name__):
                # Each case owns a separate checkout/evidence root.
                if (self.root/'src').exists():
                    self.root = self.base/'second-root'; self.root.mkdir()
                    self.evidence = self.base/'second-evidence'; self.evidence.mkdir()
                def runner(*args, **kwargs):
                    raise error
                with self.assertRaises(type(error)):
                    self.run_session([self.operation(self.first), self.operation(self.second)], runner)
                self.assertEqual(len(list(self.evidence.glob('*.json'))), 1)
                self.assertFalse(json.loads((self.evidence/'0001.json').read_text())['accepted'])

    def test_idle_deadline(self):
        now = [0.0]
        def sleep(seconds): now[0] += seconds
        with self.assertRaisesRegex(TimeoutError, 'idle'):
            session.execute(dict(source=str(self.source), inbox=str(self.base/'inbox'), idle_seconds=.3),
                            self.root, self.evidence, None, clock=lambda: now[0], sleep=sleep)

    def test_reject_unpinned_source_and_release(self):
        with self.assertRaises(ValueError): session.pin(self.source, 'HEAD')
        with self.assertRaises(ValueError):
            session.gradle(self.root, {'tasks':[':app:assembleRelease']}, session.JDK)

    def test_failed_exit_stops_and_preserves_receipt(self):
        calls = []
        def runner(*args, **kwargs):
            calls.append(1)
            return dict(exitCode=7, ownedSurvivors=[])
        with self.assertRaises(RuntimeError):
            self.run_session([self.operation(self.first), self.operation(self.second)], runner)
        self.assertEqual(calls, [1])
        self.assertEqual(json.loads((self.evidence/'0001.json').read_text())['process']['exitCode'], 7)

    def test_safe_unit_tasks(self):
        for task in (':wallet-foundation-lib:test', ':ui-lib:testZcashmainnetFossReleaseUnitTest'):
            self.assertIn(task, session.gradle(self.root, {'tasks':[task]}, session.JDK))

    def test_invalid_configuration(self):
        for config in ({'tasks':[':app:assembleDebug'], 'unknown':1},
                       {'tasks':[':app:assembleDebug'], 'init_scripts':['../escape']},
                       {'tasks':[':app:testDebug'], 'tests':['bad filter']}):
            with self.subTest(config=config), self.assertRaises(ValueError):
                session.gradle(self.root, config, session.JDK)

    def test_tracked_mutation_stops_next_operation(self):
        def runner(command, cwd, *args, **kwargs):
            (cwd/'value').write_text('mutation')
            return dict(exitCode=0)
        with self.assertRaises((ValueError, subprocess.CalledProcessError)):
            self.run_session([self.operation(self.first), self.operation(self.first)], runner)
        self.assertFalse(json.loads((self.evidence/'0002.json').read_text())['accepted'])


if __name__ == '__main__': unittest.main()
