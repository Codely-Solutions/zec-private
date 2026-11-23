import json
import re
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from test_support import patch_environment

from maintenance_watch import (WRAPPER_PROPERTIES, classify_paths, main, pending_recovery, trusted_proposal,
                               verified_wrapper, wrapper_patch)


def workflow_text(workflow):
    return (Path(__file__).resolve().parents[1]/'.github/workflows'/workflow).read_text(encoding='utf-8')


def workflow_blocks(workflow):
    header, jobs = workflow_text(workflow).split('\njobs:\n', maxsplit=1)
    return header+'\n', dict(re.findall(r'^  ([a-z_-]+):\n(.*?)(?=^  [a-z_-]+:|\Z)', jobs, re.M | re.S))


def workflow_job(workflow, name):
    """Read the maintained block-style job; refuse missing or duplicate fields."""
    block = workflow_blocks(workflow)[1][name]
    fields = {}
    for key in ('if', 'needs'):
        values = re.findall(r'^    '+key+r': (.+)$', block, re.M)
        if len(values) != 1:
            raise ValueError('Expected one inline job '+key)
        fields[key] = values[0]
    fields['needs'] = [item.strip() for item in fields['needs'].strip('[]').split(',')]
    fields['block'] = block
    return fields


def evaluate_expression(expression, values):
    expression = expression.removeprefix('${{').removesuffix('}}').strip()
    expression = expression.replace('always()', 'True').replace('cancelled()', repr(values.get('workflow_cancelled', False)))
    expression = expression.replace('needs.*.result', repr(values.get('needs.*.result', [])))
    expression = re.sub(r'\b(?:github|inputs|needs|vars)(?:\.[a-z_A-Z0-9]+)+\b',
                        lambda match: repr(values.get(match[0], 'skipped')), expression)
    expression = expression.replace('&&', ' and ').replace('||', ' or ')
    expression = re.sub(r'!(?!=)', ' not ', expression)
    return eval(expression.strip(), {'__builtins__': {}, 'contains': lambda items, value: value in items}, {})


def job_activates(job, results, **overrides):
    # Evaluate the checked-in expression, including branch/input guards. A missing
    # result is skipped, matching a failed prerequisite's downstream jobs.
    values = {'github.ref': 'refs/heads/main', 'github.run_attempt': 1,
              'inputs.preflight_only': False, 'inputs.hosted': True,
              'inputs.preclaim_run': '', 'inputs.recover_run': '7',
              'inputs.validation_only': False, 'inputs.reconciliation_run': '',
              'inputs.resume_draft': False, 'inputs.build_emulator': False,
              'vars.PRODUCTION_SIGNING_READY': 'true',
              'needs.admit_hosted.outputs.same_controller_retry': 'true',
              'needs.admit_hosted.outputs.quote_receipt_id': '',
              'needs.admit_hosted.outputs.core_receipt_id': '74',
              'needs.preflight.outputs.signing_changed': 'false'}
    values.update({f'needs.{name}.result': result for name, result in results.items()})
    values['needs.*.result'] = [results.get(name, 'skipped') for name in job['needs']]
    values.update(overrides)
    return bool(evaluate_expression(job['if'], values))


def concurrency_settings(block, indent=0):
    match = re.search(r'^ {'+str(indent)+r'}concurrency:\n((?:^ {'+str(indent + 2)+r'}[^ ].*\n)+)', block, re.M)
    return dict(line.strip().split(': ', 1) for line in match[1].splitlines()) if match else {}


class MaintenanceWatchTest(unittest.TestCase):
    def test_each_release_prerequisite_failure_and_independent_cancellation_notifies(self):
        failed = workflow_job('build-release.yml', 'failed')
        self.assertIn('reserve_quote_retry', failed['needs'])
        for state in ('failure', 'cancelled'):
            for name in failed['needs']:
                with self.subTest(job=name, result=state):
                    results = {name: state}
                    self.assertTrue(job_activates(failed, results))
                    self.assertFalse(job_activates(failed, results, workflow_cancelled=True))
                    self.assertFalse(job_activates(failed, results, **{'github.ref': 'refs/heads/review/test'}))
                    self.assertFalse(job_activates(failed, results, **{'inputs.preflight_only': True}))
        for state in ('success', 'skipped'):
            self.assertFalse(job_activates(failed, dict.fromkeys(failed['needs'], state)))
        block = failed['block']
        self.assertIn('QUOTE_RESERVATION: ${{ needs.reserve_quote_retry.result }}', block)
        self.assertIn('The protected one-use retry reservation failed', block)

    def test_cancellation_after_publication_keeps_recovery_evidence_and_blocks_success_notice(self):
        workflow = 'build-release.yml'
        failed = workflow_job(workflow, 'failed')
        for stage in ('verify_publication', 'record_integration'):
            results = {'publish': 'success', stage: 'cancelled'}
            self.assertTrue(job_activates(failed, results))
            self.assertFalse(job_activates(workflow_job(workflow, 'notify'), results))
        self.assertIn('"$PUBLIC_VERIFICATION" == cancelled', failed['block'])
        self.assertIn('"$INTEGRATION" == cancelled', failed['block'])
        self.assertIn('needs.publish.outputs.attestation_id || needs.reconcile.outputs.attestation_id', failed['block'])

    def test_diagnostic_runs_cannot_displace_pending_production_runs(self):
        release = concurrency_settings(workflow_blocks('build-release.yml')[0])
        self.assertEqual(release['queue'], 'max')
        self.assertNotEqual(release.get('cancel-in-progress'), 'true')
        defaults = {'inputs.validation_only': False, 'inputs.preflight_only': False,
                    'github.ref': 'refs/heads/main'}
        production = evaluate_expression(release['group'], defaults)
        self.assertEqual(production, evaluate_expression(release['group'], dict(defaults, **{
            'inputs.reconciliation_run': '123'})))
        for change in ({'inputs.validation_only': True}, {'inputs.preflight_only': True},
                       {'github.ref': 'refs/heads/review/test'}):
            self.assertNotEqual(production, evaluate_expression(release['group'], dict(defaults, **change)))
        for workflow in ('upgrade-acceptance.yml', 'verify-signing-environment.yml'):
            diagnostic = concurrency_settings(workflow_blocks(workflow)[0])
            self.assertNotEqual(production, diagnostic.get('group'))

    def test_discovery_dispatch_routes_non_main_away_from_the_main_pending_slot(self):
        header, _ = workflow_blocks('sync-upstream.yml')
        settings = concurrency_settings(header)
        self.assertEqual(settings.get('queue', 'single'), 'single')
        self.assertEqual(settings['cancel-in-progress'], 'false')
        self.assertIn('  schedule:\n', header)
        self.assertIn('    branches: [main]', header)
        self.assertIn('  workflow_dispatch:\n', header)
        routes = [evaluate_expression(settings['group'], {'github.ref': ref, 'github.event_name': event})
                  for ref, event in [('refs/heads/main', 'schedule'), ('refs/heads/main', 'push'),
                                     ('refs/heads/main', 'workflow_dispatch'),
                                     ('refs/heads/review/test', 'workflow_dispatch'),
                                     ('refs/tags/example', 'workflow_dispatch')]]
        self.assertEqual(routes[:3], ['zec-private-sync'] * 3)
        self.assertEqual(routes[3:], ['zec-private-sync-diagnostic'] * 2)

    def test_all_protected_signing_jobs_share_one_non_replacing_queue(self):
        jobs = {}
        for workflow in ('build-release.yml', 'upgrade-acceptance.yml', 'verify-signing-environment.yml'):
            header, blocks = workflow_blocks(workflow)
            self.assertNotEqual(concurrency_settings(header).get('group'), 'zec-private-release')
            for name, block in blocks.items():
                if 'environment: production-signing' in block:
                    jobs[(workflow, name)] = concurrency_settings(block, indent=4)
        self.assertEqual(set(jobs), {('build-release.yml', 'signing_smoke'), ('build-release.yml', 'publish'),
                                    ('build-release.yml', 'resume_draft'), ('upgrade-acceptance.yml', 'sign'),
                                    ('verify-signing-environment.yml', 'verify-key')})
        self.assertTrue(all(settings == {'group': 'zec-private-release', 'queue': 'max'}
                            for settings in jobs.values()))

    def test_issue_writers_share_one_non_replacing_queue(self):
        for workflow, name in (('build-release.yml', 'failed'), ('build-release.yml', 'notify'),
                               ('build-release.yml', 'validation_recovered'), ('sync-upstream.yml', 'blocked'),
                               ('sync-upstream.yml', 'discovery_recovered')):
            block = workflow_job(workflow, name)['block']
            self.assertEqual(concurrency_settings(block, indent=4), {'group': 'zec-private-blocker', 'queue': 'max'})
            self.assertIn('BLOCKER_OPERATION:', block)
            self.assertIn('BLOCKER_CANDIDATE:', block)

    def test_failed_retry_reservation_notifies_without_opening_quote_or_publication_gates(self):
        results = dict(admit_hosted='success', preflight='success', signing_smoke='skipped',
                       quote_bridge='success', reserve_quote_retry='failure')
        self.assertTrue(job_activates(workflow_job('build-release.yml', 'failed'), results))
        self.assertFalse(job_activates(workflow_job('build-release.yml', 'hosted_quotes'), results))
        self.assertFalse(job_activates(workflow_job('build-release.yml', 'publish'), results))
        results['reserve_quote_retry'] = 'success'
        self.assertTrue(job_activates(workflow_job('build-release.yml', 'hosted_quotes'), results))
        self.assertFalse(job_activates(workflow_job('build-release.yml', 'publish'), results))
        results['hosted_quotes'] = 'success'
        self.assertTrue(job_activates(workflow_job('build-release.yml', 'publish'), results))

    def test_watcher_and_preparation_failures_notify_independently(self):
        blocked = workflow_job('sync-upstream.yml', 'blocked')
        self.assertEqual(['maintenance', 'prepare'], blocked['needs'])
        for results in ({'maintenance': 'failure', 'prepare': 'skipped'},
                        {'maintenance': 'success', 'prepare': 'failure'},
                        {'maintenance': 'cancelled', 'prepare': 'skipped'},
                        {'maintenance': 'success', 'prepare': 'cancelled'}):
            with self.subTest(results=results):
                self.assertTrue(job_activates(blocked, results))
                self.assertFalse(job_activates(blocked, results,
                                              **{'github.ref': 'refs/heads/review/test'}))
                self.assertFalse(job_activates(blocked, results, workflow_cancelled=True))
        for state in ('success', 'skipped'):
            self.assertFalse(job_activates(blocked, dict(maintenance=state, prepare='skipped')))
        self.assertIn('MAINTENANCE: ${{ needs.maintenance.result }}', blocked['block'])
        self.assertIn('The watcher failed before candidate preparation', blocked['block'])

    def test_read_only_watcher_has_no_shared_cancellation_group(self):
        text = (Path(__file__).resolve().parents[1]/'.github/workflows/maintenance-watch.yml').read_text()
        self.assertNotRegex(text, r'(?m)^\s*concurrency:')
        self.assertIn("github.event.pull_request.user.login == 'dependabot[bot]'", text)
        self.assertIn('contents: read', text)
        self.assertNotIn('contents: write', text)

    def test_verified_recovery_notification_follows_integration_only(self):
        notification = workflow_job('build-release.yml', 'notify')
        for state in ('failure', 'cancelled', 'skipped'):
            self.assertFalse(job_activates(notification, dict(record_integration=state)))
        self.assertTrue(job_activates(notification, dict(record_integration='success')))
        self.assertFalse(job_activates(notification, dict(record_integration='success'), workflow_cancelled=True))
        self.assertIn("RECOVERY_VERIFIED: 'true'", notification['block'])

    def test_discovery_recovery_requires_watcher_and_preparation_success(self):
        recovery = workflow_job('sync-upstream.yml', 'discovery_recovered')
        passed = dict(maintenance='success', prepare='success')
        self.assertTrue(job_activates(recovery, passed))
        self.assertFalse(job_activates(recovery, passed, workflow_cancelled=True))
        self.assertFalse(job_activates(recovery, passed, **{'github.ref': 'refs/heads/review/test'}))
        for job in recovery['needs']:
            for result in ('failure', 'cancelled', 'skipped'):
                self.assertFalse(job_activates(recovery, dict(passed, **{job: result})))
        self.assertIn("RECOVERY_VERIFIED: 'true'", recovery['block'])
        self.assertIn('BLOCKER_OPERATION: discovery', recovery['block'])
        self.assertIn('BLOCKER_CANDIDATE: ${{ github.sha }}', recovery['block'])
        self.assertNotIn('NTFY_URL', recovery['block'])

    def test_validation_recovery_requires_the_unsigned_and_requested_emulator_jobs(self):
        recovery = workflow_job('build-release.yml', 'validation_recovered')
        validation = {'inputs.validation_only': True}
        both = dict(unsigned='success', emulator='success')
        unsigned = dict(unsigned='success', emulator='skipped')
        self.assertTrue(job_activates(recovery, both, **validation, **{'inputs.build_emulator': True}))
        self.assertTrue(job_activates(recovery, unsigned, **validation))
        self.assertFalse(job_activates(recovery, unsigned, **validation, **{'inputs.build_emulator': True}))
        self.assertFalse(job_activates(recovery, both, **validation, **{'inputs.preflight_only': True}))
        self.assertFalse(job_activates(recovery, both, **validation, **{'github.ref': 'refs/heads/review/test'}))
        self.assertFalse(job_activates(recovery, both, **validation, workflow_cancelled=True))
        self.assertFalse(job_activates(recovery, both))
        for result in ('failure', 'cancelled'):
            self.assertFalse(job_activates(recovery, dict(both, emulator=result), **validation))
            self.assertFalse(job_activates(recovery, dict(both, unsigned=result), **validation))
        self.assertIn("RECOVERY_VERIFIED: 'true'", recovery['block'])
        self.assertIn('BLOCKER_OPERATION: validation', recovery['block'])
        self.assertIn('BLOCKER_VALIDATION_EMULATOR: ${{ inputs.build_emulator }}', recovery['block'])
        self.assertNotIn('NTFY_URL', recovery['block'])

    def test_delayed_timer_is_quiet_without_blocking_live_discovery(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            environment = {'GITHUB_OUTPUT': str(root/'output'), 'GITHUB_STEP_SUMMARY': str(root/'summary')}
            repository = {'has_pull_requests': True, 'pull_request_creation_policy': 'collaborators_only'}
            with patch_environment(environment), patch('maintenance_watch.pending_recovery', return_value=None), \
                    patch('maintenance_watch.api', side_effect=[repository, {'state': 'active'},
                          {'workflow_runs': []}, []]) as api, patch('builtins.print') as emit:
                main()
                self.assertEqual(api.call_count, 4)
                self.assertIn('schedule is delayed', emit.call_args.args[0])
                self.assertNotIn('::warning::', emit.call_args.args[0])
                self.assertFalse((root/'output').exists())

    def test_wrapper_patch_requires_exact_official_coordinates_and_settings(self):
        base = ('distributionUrl=https\\://services.gradle.org/distributions/gradle-9.9.1-bin.zip\n'
                'distributionSha256Sum=' + 'a'*64 + '\nnetworkTimeout=10000\n')
        proposed = base.replace('9.9.1', '9.9.2').replace('a'*64, 'b'*64)
        versions = []

        def checksum(version):
            versions.append(version)
            return 'b'*64

        self.assertEqual(wrapper_patch(base, proposed, checksum), '9.9.2')
        self.assertEqual(versions, ['9.9.2'])
        for untrusted in (proposed.replace('services.gradle.org', 'example.invalid'),
                          proposed.replace('9.9.2', '9.10.1'),
                          proposed.replace('10000', '20000'),
                          proposed + ' distributionUrl=http://example.invalid\n',
                          proposed.replace('b'*64, 'c'*64)):
            with self.subTest(untrusted=untrusted), self.assertRaises(ValueError):
                wrapper_patch(base, untrusted, checksum)

    def test_controller_file_cannot_enter_the_dependency_queue(self):
        files = [{'filename': 'gradle.properties', 'status': 'modified'}]
        self.assertIn('needs provenance', classify_paths(files, 1))
        self.assertEqual('incomplete diff', classify_paths(files, 2))
        files[0].update(status='renamed', previous_filename='.private/publish.py')
        self.assertIn('controller change', classify_paths(files, 1))
        files[0].update(filename='.github/workflows/build-release.yml', previous_filename='gradle.properties')
        self.assertIn('controller change', classify_paths(files, 1))

    def test_repository_and_bot_identity_are_required(self):
        pr = {'state': 'open', 'draft': False, 'user': {'login': 'dependabot[bot]', 'type': 'Bot'},
              'head': {'repo': {'id': 7}, 'ref': 'dependabot/gradle/update', 'sha': 'a' * 40},
              'base': {'repo': {'id': 7}, 'ref': 'main'}}
        self.assertTrue(trusted_proposal(pr, 7))
        self.assertFalse(trusted_proposal(dict(pr, user={'login': 'dependabot[bot]', 'type': 'User'}), 7))
        self.assertFalse(trusted_proposal(dict(pr, head=dict(pr['head'], repo={'id': 8})), 7))

    def test_verified_wrapper_rechecks_the_proposal_after_resolving_the_checksum(self):
        base, head = 'a'*40, 'b'*40
        pr = {'state': 'open', 'draft': False, 'changed_files': 1,
              'user': {'login': 'dependabot[bot]', 'type': 'Bot'},
              'head': {'repo': {'id': 7}, 'ref': 'dependabot/gradle/update', 'sha': head},
              'base': {'repo': {'id': 7}, 'ref': 'main', 'sha': base}}
        files = [{'filename': WRAPPER_PROPERTIES, 'status': 'modified'}]
        with patch('maintenance_watch.wrapper_source', return_value='exact properties'), \
                patch('maintenance_watch.wrapper_patch', return_value='9.9.2'):
            with patch('maintenance_watch.api', side_effect=[pr, files, pr, {'sha': base}]):
                self.assertEqual(verified_wrapper('repos/fork/wallet', 1, base, head, 7),
                                 ('9.9.2', 'exact properties'))
            with patch('maintenance_watch.api', side_effect=[pr, files, dict(pr, state='closed')]):
                with self.assertRaisesRegex(ValueError, 'changed during verification'):
                    verified_wrapper('repos/fork/wallet', 1, base, head, 7)
            with patch('maintenance_watch.api', side_effect=[pr, [{'filename': '.private/publish.py',
                                                                  'status': 'modified'}]]):
                with self.assertRaisesRegex(ValueError, 'Wrapper-only'):
                    verified_wrapper('repos/fork/wallet', 1, base, head, 7)

    def test_prepare_builds_a_reusable_branch_from_trusted_coordinates(self):
        import prepare_maintenance as prepare

        with tempfile.TemporaryDirectory() as temp:
            scratch = Path(temp)
            root = scratch/'controller'
            root.mkdir()

            def git(*args):
                return subprocess.check_output(['git', *args], cwd=root, text=True, timeout=20).strip()

            git('init', '-q')
            (root/'.private').mkdir()
            (root/WRAPPER_PROPERTIES).parent.mkdir(parents=True)
            old_properties = ('distributionUrl=https\\://services.gradle.org/distributions/gradle-9.9.1-bin.zip\n'
                              'distributionSha256Sum=' + 'a'*64 + '\n')
            new_properties = old_properties.replace('9.9.1', '9.9.2').replace('a'*64, 'b'*64)
            (root/WRAPPER_PROPERTIES).write_text(old_properties, encoding='ascii', newline='\n')
            (root/'gradle.properties').write_bytes(b'ZCASH_VERSION_CODE=3234021\nZCASH_VERSION_NAME=3.19.0\n')
            git('add', '.')
            git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'upstream')
            upstream = git('rev-parse', 'HEAD')
            (root/'.private/release-revision.json').write_text('{"private_revision":21}')
            (root/'.private/upstream.json').write_text(json.dumps(dict(tag='3.19.0-3234', versionCode=3234021, sha=upstream)))
            (root/'.private/excluded-features.json').write_text('{"forbidden_projects":[]}')
            (root/'.private/fork.patch').touch()
            (root/'.private/source-state.json').touch()
            git('add', '.')
            git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'controller')
            base, head = git('rev-parse', 'HEAD'), 'b'*40
            environment = dict(GITHUB_ACTIONS='true', GITHUB_REF='refs/heads/main',
                               GITHUB_REPOSITORY=prepare.REPOSITORY)
            remote = {}

            def remote_git(*args):
                if args[:2] == ('ls-remote', 'origin'):
                    return remote.get(args[2], '')
                if args[:2] == ('fetch', '--no-tags'):
                    return ''
                if args[:2] == ('push', 'origin'):
                    sha, ref = args[2].split(':')
                    self.assertNotIn(ref, remote)
                    remote[ref] = sha
                    return ''
                return git(*args)

            def refresh():
                from refresh_source import generate_patch, patch_paths
                (root/'.private/fork.patch').write_bytes(generate_patch(root, upstream, patch_paths([])))

            with patch_environment(environment), patch.object(prepare, 'ROOT', root), \
                    patch.object(prepare, 'git', side_effect=remote_git), \
                    patch.object(prepare.Metadata, 'api', side_effect=lambda path, **_: (
                        {'id': 7} if not path else [[{'tag_name': 'v3.19.0-private.21'}]])), \
                    patch.object(prepare, 'verified_wrapper', return_value=('9.9.2', new_properties)), \
                    patch.object(prepare, 'api', side_effect=lambda path: (
                        {'state': 'open', 'head': {'sha': head}} if '/pulls/' in path else {'sha': base})), \
                    patch.object(prepare, 'refresh_source', side_effect=refresh), patch('builtins.print') as output:
                original_run = subprocess.run

                def run(command, *args, **kwargs):
                    if command[:3] == ['gh', 'auth', 'setup-git']:
                        return subprocess.CompletedProcess(command, 0)
                    return original_run(command, *args, **kwargs)

                with patch.object(subprocess, 'run', side_effect=run):
                    prepare.prepare('2', head, base)
                    first = json.loads(output.call_args.args[0])
                    git('reset', '-q', '--hard', base)
                    prepare.prepare('2', head, base)
                    self.assertEqual(first, json.loads(output.call_args.args[0]))
            sha = first['source_sha']
            self.assertEqual(base, git('rev-parse', sha+'^'))
            self.assertEqual(new_properties.strip(), git('show', f'{sha}:{WRAPPER_PROPERTIES}'))
            self.assertEqual(3234022, first['version_code'])
            from acceptance_policy import verify_promotion_revision
            with patch('maintenance_watch.official_checksum', return_value='b'*64):
                self.assertFalse(verify_promotion_revision(sha, base, root=root))
            (root/'candidate-code.kt').touch()
            git('add', 'candidate-code.kt')
            modified = git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
                           'commit-tree', git('write-tree'), '-p', base, '-m', 'candidate with extra source')
            with self.assertRaisesRegex(ValueError, 'outside the exact wrapper update'):
                verify_promotion_revision(modified, base, root=root)

    def test_recovery_pages_past_newer_runs_and_ignores_expired_attestations(self):
        tag = 'v3.19.0-private.27'
        latest = dict(tag_name=tag)
        ordinary = dict(name='unrelated-build', expired=False, workflow_run={'id': 5})
        expired = dict(name='release-attestation-1', expired=True, workflow_run={'id': 6})
        publisher = dict(id=40, name='release-attestation-2', expired=False, workflow_run={'id': 4})
        with tempfile.TemporaryDirectory() as temp:
            with patch('maintenance_watch.api', side_effect=[latest, {'artifacts': [ordinary]*99+[expired]},
                                                            {'artifacts': [publisher]}]) as api:
                resolved = []
                def resolve(run, attempt, artifact, *, target):
                    resolved.append((run, attempt, artifact))
                    self.assertEqual(latest, target)
                    return {'release_tag': tag}
                self.assertEqual(dict(reconciliation_run='4', reconciliation_attempt='2', reconciliation_artifact='40'),
                                 pending_recovery('repos/fork/wallet', Path(temp), resolve))
                self.assertEqual([('4', '2', '40')], resolved)
                self.assertIn('page=2', api.call_args.args[0])
            with patch('maintenance_watch.api', side_effect=[latest]+[{'artifacts': [ordinary]*100}]*10):
                with self.assertRaisesRegex(ValueError, '1000 artifacts'):
                    pending_recovery('repos/fork/wallet', Path(temp), resolve)

    def test_original_publisher_survives_more_than_eight_newer_attestations(self):
        tag = 'v3.19.0-private.27'
        artifacts = [dict(id=run * 10, name='release-attestation-1', expired=False, workflow_run={'id': run})
                     for run in range(20, 10, -1)]
        with tempfile.TemporaryDirectory() as temp:
            with patch('maintenance_watch.api', side_effect=[{'tag_name': tag}, {'artifacts': artifacts}]):
                def resolve(run, attempt, artifact, *, target):
                    if run == '12':
                        raise ValueError('Untrusted publisher')
                    return {'release_tag': tag if run == '11' else 'v3.19.0-private.26'}
                self.assertEqual(dict(reconciliation_run='11', reconciliation_attempt='1', reconciliation_artifact='110'),
                                 pending_recovery('repos/fork/wallet', Path(temp), resolve))

    def test_recovery_quote_failure_selects_the_blocker_notification(self):
        failed = workflow_job('build-release.yml', 'failed')
        for job in ('hosted_quotes', 'recovery_quotes'):
            self.assertTrue(job_activates(failed, {job: 'failure'}))

    def test_automatic_recovery_requires_the_latest_exact_attestation(self):
        tag = 'v3.19.0-private.21'
        digest = 'a'*64
        latest = dict(tag_name=tag, published_at='2026-11-11T12:00:00Z',
                      assets=[dict(name='zec-private-latest.apk', digest='sha256:'+digest)])
        artifact = dict(id=42, name='release-attestation-1', expired=False, workflow_run={'id': 4})
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            with patch('maintenance_watch.api', side_effect=[latest, {'artifacts': [artifact]}]):
                self.assertEqual(dict(reconciliation_run='4', reconciliation_attempt='1', reconciliation_artifact='42'),
                                 pending_recovery('repos/fork/wallet', directory,
                                                  lambda *_args, **_kwargs: {'release_tag': tag}))
            with patch('maintenance_watch.api', side_effect=[latest, {'artifacts': [artifact]}]):
                with self.assertRaisesRegex(ValueError, 'one live matching publication attestation'):
                    pending_recovery('repos/fork/wallet', directory,
                                     lambda *_args, **_kwargs: {'release_tag': 'v3.19.0-private.20'})
            (directory/(tag+'.json')).write_text(json.dumps({'apk_sha256': digest}))
            with patch('maintenance_watch.api', return_value=latest):
                self.assertIsNone(pending_recovery('repos/fork/wallet', directory))


if __name__ == '__main__':
    unittest.main()
