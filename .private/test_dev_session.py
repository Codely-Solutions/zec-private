import json
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
import dev_session as session


class SessionTest(unittest.TestCase):
    def test_java_socket_fallback_preserves_existing_child_options_and_keeps_directory_absent(self):
        from test_support import patch_environment
        options = []
        def runner(command, cwd, seconds, root, name, **kwargs):
            value = kwargs['environment']['JDK_JAVA_OPTIONS']
            self.assertTrue(value.startswith('-Dexisting.option=kept '))
            self.assertIn('"-Djdk.net.unixdomain.tmpdir='+str(root/'unavailable-unix-sockets')+'"', value)
            self.assertFalse((root/'unavailable-unix-sockets').exists())
            options.append(value)
            return dict(exitCode=0)
        with patch_environment({'JDK_JAVA_OPTIONS': '-Dexisting.option=kept'}):
            self.run_session([self.operation(self.first)], runner)
        self.assertEqual(len(options), 1)

    def test_filters_bind_to_each_unit_task_before_other_task_options(self):
        tasks = [':ui-lib:testReleaseUnitTest', ':app:assembleDebug', ':wallet-foundation-lib:test', ':detektAll']
        command = session.gradle(self.root, dict(tasks=tasks, tests=['Fixture*']), session.JDK)
        for task in (tasks[0], tasks[2]):
            index = command.index(task)
            self.assertEqual(command[index + 1:index + 3], ['--tests', 'Fixture*'])
        self.assertEqual(command[-1], ':detektAll')
        self.assertEqual(command.count('--tests'), 2)

    def test_filters_require_a_unit_test_task(self):
        with self.assertRaisesRegex(ValueError, 'explicit JVM test task'):
            session.gradle(self.root, dict(tasks=[':detektAll'], tests=['Fixture*']), session.JDK)

    def test_reports_belong_to_requested_test_tasks(self):
        operation = self.operation(self.first)
        operation['config']['tasks'] = [':ui-lib:testReleaseUnitTest']
        def runner(command, cwd, seconds, root, name, **kwargs):
            (root/(name+'.log')).write_text('> Task :ui-lib:testReleaseUnitTest\n')
            for module, task, suite in (
                    ('ui-lib', 'testReleaseUnitTest', 'current'),
                    ('ui-lib', 'testDebugUnitTest', 'other-variant'),
                    ('feature-viewing-key', 'testReleaseUnitTest', 'other-module')):
                report = root/'builds'/module/'test-results'/task/('TEST-'+suite+'.xml')
                report.parent.mkdir(parents=True, exist_ok=True)
                report.write_text('<testsuite name="'+suite+'"/>')
            return dict(exitCode=0)
        self.run_session([operation], runner)
        receipt = json.loads((self.evidence/'0001.json').read_text())
        self.assertEqual([Path(item['file']).name for item in receipt['reports']], ['TEST-current.xml'])

    def report_operation(self, commit=None):
        operation = self.operation(commit or self.first)
        operation['config']['tasks'] = [':ui-lib:testReleaseUnitTest']
        return operation

    def write_report(self, root, name, outcome='', value='current'):
        (root/(name+'.log')).write_text('> Task :ui-lib:testReleaseUnitTest'+(' '+outcome if outcome else '')+'\n')
        path = root/'builds/ui-lib/test-results/testReleaseUnitTest/TEST-current.xml'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('<testsuite name="'+value+'"/>')
        return path

    def test_first_rejected_pin_records_original_error_without_reports(self):
        with self.assertRaisesRegex(ValueError, 'full commit'):
            self.run_session([self.report_operation('HEAD')], mock.Mock())
        receipt = json.loads((self.evidence/'0001.json').read_text())
        self.assertFalse(receipt['accepted'])
        self.assertIn('ValueError', receipt['error'])
        self.assertEqual(receipt['reports'], [])
        self.assertNotIn('binding', receipt)

    def test_later_rejected_pin_does_not_borrow_config_or_report(self):
        def runner(command, cwd, seconds, root, name, **kwargs):
            self.write_report(root, name)
            return dict(exitCode=0)
        rejected = self.operation('HEAD')
        rejected['config'] = dict(tasks=[':detektAll'])
        with self.assertRaises(ValueError):
            self.run_session([self.report_operation(), rejected], runner)
        receipt = json.loads((self.evidence/'0002.json').read_text())
        self.assertEqual(receipt['config'], rejected['config'])
        self.assertEqual(receipt['reports'], [])
        self.assertEqual(len(list(self.evidence.glob('0001/reports/**/*.xml'))), 1)

    def test_failure_before_test_or_during_evidence_keeps_primary_error(self):
        def runner(command, cwd, seconds, root, name, **kwargs):
            if name == '0001':
                self.write_report(root, name)
                timing = cwd/'artifact/task-timing.jsonl'
                timing.parent.mkdir()
                timing.write_text('first producer\n')
                return dict(exitCode=0)
            (root/(name+'.log')).write_text('configuration failed\n')
            return dict(exitCode=7)
        with self.assertRaisesRegex(RuntimeError, 'Operation failed'):
            self.run_session([self.report_operation(), self.report_operation(self.second)], runner)
        receipt = json.loads((self.evidence/'0002.json').read_text())
        self.assertEqual(receipt['reports'], [])
        self.assertNotIn('timing', receipt)
        self.assertFalse((self.evidence/'0002-task-timing.jsonl').exists())

    def test_incremental_reuse_preserves_report_producer(self):
        def runner(command, cwd, seconds, root, name, **kwargs):
            if name == '0001':
                self.write_report(root, name)
            else:
                (root/(name+'.log')).write_text('> Task :ui-lib:testReleaseUnitTest UP-TO-DATE\n')
            return dict(exitCode=0)
        self.run_session([self.report_operation(), self.report_operation(self.second)], runner)
        first, second = [json.loads((self.evidence/(name+'.json')).read_text()) for name in ('0001', '0002')]
        self.assertEqual(second['reports'][0]['producer'], first['reports'][0]['producer'])
        self.assertEqual(second['reports'][0]['producer']['source'], self.first)
        self.assertEqual(second['reports'][0]['outcome'], 'UP-TO-DATE')
        self.assertNotEqual(first['binding'], second['binding'])

    def test_cache_restoration_records_current_restore_identity(self):
        def runner(command, cwd, seconds, root, name, **kwargs):
            self.write_report(root, name, 'FROM-CACHE')
            return dict(exitCode=0)
        self.run_session([self.report_operation()], runner)
        report = json.loads((self.evidence/'0001.json').read_text())['reports'][0]
        self.assertEqual(report['producer']['outcome'], 'FROM-CACHE')
        self.assertEqual(report['producer']['source'], self.first)

    def test_failed_test_does_not_relabel_unchanged_xml(self):
        def runner(command, cwd, seconds, root, name, **kwargs):
            if name == '0001':
                self.write_report(root, name)
                return dict(exitCode=0)
            (root/(name+'.log')).write_text('> Task :ui-lib:testReleaseUnitTest FAILED\n')
            return dict(exitCode=1)
        with self.assertRaises(RuntimeError):
            self.run_session([self.report_operation(), self.report_operation()], runner)
        self.assertEqual(json.loads((self.evidence/'0002.json').read_text())['reports'], [])

    def test_collection_failure_records_receipt_without_masking_cancellation(self):
        def runner(command, cwd, seconds, root, name, **kwargs):
            self.write_report(root, name)
            raise KeyboardInterrupt()
        with mock.patch.object(session, 'collect_reports', side_effect=OSError('synthetic I/O failure')):
            with self.assertRaises(KeyboardInterrupt):
                self.run_session([self.report_operation()], runner)
        receipt = json.loads((self.evidence/'0001.json').read_text())
        self.assertFalse(receipt['accepted'])
        self.assertEqual(receipt['evidence_error'], 'OSError')
        self.assertIn('KeyboardInterrupt', receipt['error'])

    def test_collection_failure_rejects_otherwise_successful_operation(self):
        with mock.patch.object(session, 'collect_reports', side_effect=OSError()):
            with self.assertRaisesRegex(RuntimeError, 'evidence collection failed'):
                self.run_session([self.report_operation()], lambda *a, **k: dict(exitCode=0))
        self.assertFalse(json.loads((self.evidence/'0001.json').read_text())['accepted'])

    def test_static_analysis_does_not_admit_production_assembly(self):
        command = session.gradle(self.root, dict(tasks=[':detektAll']), Path('verified-jdk'))
        self.assertIn(':detektAll', command)
        with self.assertRaises(ValueError):
            session.gradle(self.root, dict(tasks=[':app:assembleZcashmainnetFossRelease']), Path('verified-jdk'))

    def test_verification_generation_accepts_only_sha256(self):
        config = dict(tasks=[':app:assembleDebug'], write_verification_metadata=['sha256'])
        command = session.gradle(self.root, config, session.JDK)
        index = command.index('--write-verification-metadata')
        self.assertEqual(command[index + 1], 'sha256')
        self.assertLess(index, command.index(':app:assembleDebug'))
        for value in ([], ['pgp'], ['sha256', 'pgp'], 'sha256', True):
            with self.subTest(value=value), self.assertRaises(ValueError):
                session.gradle(self.root, dict(config, write_verification_metadata=value), session.JDK)

    def test_verification_export_preserves_bytes_and_restores_original_or_absence(self):
        metadata = self.root/'src/gradle/verification-metadata.xml'
        metadata.parent.mkdir(parents=True)
        generated = b'generated\r\nmetadata\n'
        for name, original in [('original', b'original\r\nbytes'), ('new', None)]:
            metadata.write_bytes(generated)
            result = session.export_verification_metadata(self.root/'src', self.evidence, name, original)
            self.assertEqual((self.evidence/result['file']).read_bytes(), generated)
            self.assertEqual(result['sha256'], hashlib.sha256(generated).hexdigest())
            if original is None:
                self.assertFalse(metadata.exists())
            else:
                self.assertEqual(metadata.read_bytes(), original)

    def test_verification_export_failure_still_restores_original(self):
        metadata = self.root/'src/gradle/verification-metadata.xml'
        metadata.parent.mkdir(parents=True)
        metadata.write_bytes(b'generated')
        target = self.evidence/'collision/verification-metadata.xml'
        target.parent.mkdir()
        target.write_bytes(b'immutable evidence')
        with self.assertRaises(FileExistsError):
            session.export_verification_metadata(self.root/'src', self.evidence, 'collision', b'original')
        self.assertEqual(metadata.read_bytes(), b'original')
        self.assertEqual(target.read_bytes(), b'immutable evidence')

    def test_verification_size_is_checked_before_open_and_restored_on_rejection(self):
        metadata = self.root/'src/gradle/verification-metadata.xml'
        metadata.parent.mkdir(parents=True)
        with metadata.open('wb') as stream:
            stream.truncate(session.VERIFICATION_METADATA_MAX_BYTES + 1)
        with mock.patch.object(Path, 'open', side_effect=AssertionError('Must reject before reading')):
            with self.assertRaisesRegex(ValueError, 'exceeds 4 MiB'):
                session.read_verification_metadata(metadata)
        with self.assertRaisesRegex(ValueError, 'exceeds 4 MiB'):
            session.export_verification_metadata(self.root/'src', self.evidence, 'oversized', b'original')
        self.assertEqual(metadata.read_bytes(), b'original')
        self.assertFalse((self.evidence/'oversized/verification-metadata.xml').exists())

    def verification_operation(self):
        metadata = self.source/'gradle/verification-metadata.xml'
        metadata.parent.mkdir()
        metadata.write_bytes(b'original')
        self.git('add', '.')
        self.git('commit', '-qm', 'verification original')
        commit = self.git('rev-parse', 'HEAD')
        operation = self.operation(commit)
        operation['config']['write_verification_metadata'] = ['sha256']
        return operation

    def test_verification_generation_keeps_reused_checkout_clean(self):
        operation = self.verification_operation()
        def runner(command, cwd, *args, **kwargs):
            self.assertIn('--write-verification-metadata', command)
            self.assertEqual((cwd/'gradle/verification-metadata.xml').read_bytes(), b'original')
            (cwd/'gradle/verification-metadata.xml').write_bytes(b'generated\n')
            return dict(exitCode=0)
        self.run_session([operation, operation], runner)
        for name in ['0001', '0002']:
            receipt = json.loads((self.evidence/(name + '.json')).read_text())
            self.assertTrue(receipt['accepted'])
            self.assertEqual((self.evidence/receipt['verification_metadata']['file']).read_bytes(), b'generated\n')
            self.assertEqual(receipt['verification_metadata']['outcome'], 'generated')
        self.assertEqual((self.root/'src/gradle/verification-metadata.xml').read_bytes(), b'original')

    def test_generation_does_not_export_untouched_committed_metadata(self):
        operation = self.verification_operation()
        with self.assertRaisesRegex(ValueError, 'did not generate'):
            self.run_session([operation], lambda *args, **kwargs: dict(exitCode=0))
        receipt = json.loads((self.evidence/'0001.json').read_bytes())
        self.assertFalse(receipt['accepted'])
        self.assertIsNone(receipt['verification_metadata'])
        self.assertEqual((self.root/'src/gradle/verification-metadata.xml').read_bytes(), b'original')

    def test_generation_accepts_byte_identical_replacement(self):
        operation = self.verification_operation()

        def runner(command, cwd, *args, **kwargs):
            metadata = cwd/'gradle/verification-metadata.xml'
            replacement = metadata.with_suffix('.new')
            replacement.write_bytes(metadata.read_bytes())
            replacement.replace(metadata)
            return dict(exitCode=0)

        self.run_session([operation], runner)
        receipt = json.loads((self.evidence/'0001.json').read_bytes())
        self.assertTrue(receipt['accepted'])
        metadata = receipt['verification_metadata']
        self.assertEqual(metadata['outcome'], 'regenerated-identical')
        self.assertEqual(metadata['original_sha256'], metadata['sha256'])

    def test_generation_does_not_export_timestamp_only_change(self):
        operation = self.verification_operation()

        def runner(command, cwd, *args, **kwargs):
            metadata = cwd/'gradle/verification-metadata.xml'
            stat = metadata.stat()
            os.utime(metadata, ns=(stat.st_atime_ns, stat.st_mtime_ns + 2_000_000_000))
            self.assertNotEqual(metadata.stat().st_mtime_ns, stat.st_mtime_ns)
            return dict(exitCode=0)

        with self.assertRaisesRegex(ValueError, 'did not generate'):
            self.run_session([operation], runner)
        receipt = json.loads((self.evidence/'0001.json').read_bytes())
        self.assertFalse(receipt['accepted'])
        self.assertIsNone(receipt['verification_metadata'])
        self.assertEqual((self.root/'src/gradle/verification-metadata.xml').read_bytes(), b'original')
        self.assertFalse((self.evidence/'0001/verification-metadata.xml').exists())

    def test_command_interrupt_stays_primary_when_metadata_export_fails(self):
        operation = self.verification_operation()

        def runner(command, cwd, *args, **kwargs):
            with (cwd/'gradle/verification-metadata.xml').open('wb') as stream:
                stream.truncate(session.VERIFICATION_METADATA_MAX_BYTES + 1)
            raise KeyboardInterrupt('synthetic command interruption')

        with self.assertRaisesRegex(KeyboardInterrupt, 'synthetic command interruption'):
            self.run_session([operation], runner)
        receipt = json.loads((self.evidence/'0001.json').read_bytes())
        self.assertFalse(receipt['accepted'])
        self.assertEqual(receipt['error'], 'KeyboardInterrupt: synthetic command interruption')
        self.assertEqual(receipt['verification_metadata_error'], 'ValueError: Verification metadata exceeds 4 MiB')
        self.assertEqual((self.root/'src/gradle/verification-metadata.xml').read_bytes(), b'original')

    def test_missing_generated_metadata_fails_closed(self):
        operation = self.operation(self.first)
        operation['config']['write_verification_metadata'] = ['sha256']
        with self.assertRaisesRegex(ValueError, 'did not generate'):
            self.run_session([operation], lambda *args, **kwargs: dict(exitCode=0))
        receipt = json.loads((self.evidence/'0001.json').read_text())
        self.assertFalse(receipt['accepted'])
        self.assertIsNone(receipt['verification_metadata'])

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
