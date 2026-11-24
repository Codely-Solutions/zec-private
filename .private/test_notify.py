"""Exercise the notifier's issue persistence and transport consumer together."""
import json
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

import notify
from test_support import patch_environment


class NotifyTest(unittest.TestCase):
    def setUp(self):
        self.issue = dict(number=41, title=notify.TITLE, body='Original diagnosis and operator notes.', state='open')
        self.closed = []
        self.commands = []
        self.messages = []
        self.response = Mock(status=200)
        self.response.__enter__ = Mock(return_value=self.response)
        self.response.__exit__ = Mock(return_value=False)

    def gh(self, *args):
        self.commands.append(args)
        if args[0] == 'api':
            self.assertIn('state=all', args[1])
            return json.dumps([self.closed, [self.issue] if self.issue else []])
        if args[:2] == ('issue', 'view'):
            return json.dumps(dict(self.issue, state='OPEN') if self.issue else {'state': 'CLOSED'})
        if args[:2] in (('issue', 'edit'), ('issue', 'create')):
            body = Path(args[args.index('--body-file')+1]).read_text(encoding='utf-8')
            if args[1] == 'create':
                self.assertIsNone(self.issue)
                self.issue = dict(number=41 + len(self.closed), title=args[args.index('--title') + 1], state='open')
                issue = self.issue
            else:
                issue = next(row for row in [self.issue, *self.closed] if row and row['number'] == int(args[2]))
            issue['body'] = body
            return f'https://github.com/example/wallet/issues/{issue["number"]}'
        if args[:2] == ('issue', 'close'):
            self.closed.append(dict(self.issue, state='closed'))
            self.issue = None
            return ''
        if args[:2] == ('issue', 'comment'):
            return ''
        raise AssertionError(args)

    def post(self, request, **kwargs):
        self.messages.append(request.data.decode())
        return self.response

    def blockers(self):
        return {scope if scope[-1] else scope[:-1]: state
                for scope, (state, _) in notify.current_blockers(self.issue['body']).items()}

    def run_notice(self, message='Blocked at build', **environment):
        values = dict(GITHUB_REPOSITORY='example/wallet', GITHUB_RUN_ID='20', MAKE_ISSUE='true',
                      NTFY_URL='https://secret.invalid/topic', RECOVERY_VERIFIED='', BLOCKER_DIAGNOSIS='build=failure',
                      GITHUB_RUN_ATTEMPT='1', BLOCKER_OPERATION='release', BLOCKER_CANDIDATE='candidate-a',
                      BLOCKER_ATTESTATION='')
        values.update(environment)
        with patch_environment(values), patch.object(notify, 'gh', side_effect=self.gh), \
                patch.object(notify.urllib.request, 'urlopen', side_effect=self.post), patch('builtins.print'):
            notify.main(message)

    def test_changed_blocker_persists_diagnosis_and_current_run_before_delivery(self):
        original_post = self.post
        def post(request, **kwargs):
            self.assertIn('Blocked at build', self.issue['body'])
            self.assertIn('/actions/runs/20', self.issue['body'])
            self.assertIn('build=failure', self.issue['body'])
            return original_post(request, **kwargs)
        with patch.object(self, 'post', side_effect=post):
            self.run_notice()
        self.assertIn('Original diagnosis and operator notes.', self.issue['body'])
        self.assertIn('/actions/runs/20', self.messages[0])
        self.assertIn('/issues/41', self.messages[0])
        self.assertEqual(self.blockers()['release', 'candidate-a', '']['delivery'], 'accepted')

    def test_same_blocker_updates_run_quietly_and_changed_stage_notifies(self):
        self.run_notice()
        self.run_notice(GITHUB_RUN_ID='21')
        self.assertEqual(len(self.messages), 1)
        self.assertIn('/actions/runs/21', self.issue['body'])
        self.assertEqual(self.issue['body'].count(notify.START), 1)
        self.run_notice(GITHUB_RUN_ID='22', BLOCKER_DIAGNOSIS='core=failure')
        self.assertEqual(len(self.messages), 2)
        self.assertIn('core=failure', self.messages[-1])

    def test_absent_transport_keeps_current_evidence_and_later_delivery_retry(self):
        self.run_notice(NTFY_URL='')
        self.assertEqual(self.messages, [])
        self.assertIn('/actions/runs/20', self.issue['body'])
        self.assertEqual(self.blockers()['release', 'candidate-a', '']['delivery'], 'pending')
        self.run_notice(GITHUB_RUN_ID='21')
        self.assertEqual(len(self.messages), 1)

    def test_failed_transport_keeps_current_evidence_and_is_retryable_without_secret(self):
        self.response.status = 503
        with self.assertRaisesRegex(SystemExit, '^ntfy notification failed: RuntimeError$'):
            self.run_notice()
        self.assertIn('/actions/runs/20', self.issue['body'])
        self.assertEqual(self.blockers()['release', 'candidate-a', '']['delivery'], 'pending')
        self.response.status = 200
        self.run_notice(GITHUB_RUN_ID='21')
        self.assertEqual(len(self.messages), 2)
        self.assertEqual(self.blockers()['release', 'candidate-a', '']['delivery'], 'accepted')

    def test_issue_failure_does_not_deliver_unrecorded_blocker(self):
        def failing(*args):
            if args[:2] == ('issue', 'edit'):
                raise RuntimeError('synthetic GitHub failure')
            return self.gh(*args)
        with patch_environment(dict(GITHUB_REPOSITORY='example/wallet', GITHUB_RUN_ID='20', MAKE_ISSUE='true',
                                    BLOCKER_OPERATION='release', BLOCKER_CANDIDATE='candidate-a')), \
                patch.object(notify, 'gh', side_effect=failing), patch.object(notify.urllib.request, 'urlopen') as post:
            with self.assertRaises(RuntimeError):
                notify.main('Blocked')
            post.assert_not_called()

    def test_interrupted_recovery_at_each_issue_write_can_finish(self):
        verified = dict(MAKE_ISSUE='', RECOVERY_VERIFIED='true', NTFY_URL='')
        for method, applied in (('comment', False), ('edit', False), ('edit', True),
                                ('close', False), ('close', True)):
            with self.subTest(method=method, applied=applied):
                self.setUp()
                self.run_notice(NTFY_URL='')
                call = self.gh

                def interrupt(*args):
                    if args[:2] != ('issue', method):
                        return call(*args)
                    if applied:
                        call(*args)
                    raise RuntimeError('simulated interrupted GH response')

                with patch.object(self, 'gh', side_effect=interrupt), self.assertRaises(RuntimeError):
                    self.run_notice('Verified', GITHUB_RUN_ID='21', **verified)

                if method == 'close' and not applied:
                    self.assertEqual(self.blockers(), {})
                    self.assertIn(notify.CLOSE_PENDING, self.issue['body'])
                    # Different controllers can finish the recorded close.
                    self.run_notice('Discovery passed', GITHUB_RUN_ID='22', BLOCKER_OPERATION='discovery', **verified)
                self.run_notice('Verified retry', GITHUB_RUN_ID='21', GITHUB_RUN_ATTEMPT='2', **verified)
                self.run_notice('Old failure', GITHUB_RUN_ATTEMPT='2')
                self.assertIsNone(self.issue)
                self.assertIn('Original diagnosis and operator notes.', self.closed[0]['body'])
                self.assertNotIn(notify.START, self.closed[0]['body'])
                self.assertEqual(self.messages, [])

    def test_operator_text_written_during_the_recovery_comment_survives(self):
        self.run_notice(NTFY_URL='')
        call = self.gh

        def add_note(*args):
            result = call(*args)
            if args[:2] == ('issue', 'comment'):
                self.issue['body'] += '\n\nNew operator note.'
            return result

        with patch.object(self, 'gh', side_effect=add_note):
            self.run_notice('Verified', GITHUB_RUN_ID='21', MAKE_ISSUE='', RECOVERY_VERIFIED='true', NTFY_URL='')
        self.assertIsNone(self.issue)
        self.assertIn('New operator note.', self.closed[0]['body'])

    def test_first_blocker_creates_one_issue_with_exact_body_file(self):
        self.issue = None
        self.run_notice(message='Blocked\nwith literal `text` and $content')
        self.assertEqual(sum(command[:2] == ('issue', 'create') for command in self.commands), 1)
        self.assertIn('literal `text` and $content', self.issue['body'])

    def test_only_verified_recovery_retires_deduplication(self):
        self.run_notice()
        self.run_notice('Diagnostic success', MAKE_ISSUE='', RECOVERY_VERIFIED='', GITHUB_RUN_ID='21')
        self.assertIsNotNone(self.issue)
        self.run_notice('Public verification and recording passed', MAKE_ISSUE='', RECOVERY_VERIFIED='true', GITHUB_RUN_ID='22')
        self.assertIsNone(self.issue)
        self.run_notice(GITHUB_RUN_ID='23')
        self.assertIsNotNone(self.issue)
        self.assertEqual(len(self.messages), 4)

    def test_old_discovery_failures_stay_retired_in_open_and_closed_issues(self):
        discovery = dict(BLOCKER_OPERATION='discovery', BLOCKER_CANDIDATE='a' * 40)
        scope = ('discovery', 'a' * 40, '')
        self.run_notice('Old discovery failure', **discovery)
        self.run_notice('Validation failed', BLOCKER_OPERATION='validation', GITHUB_RUN_ID='21')
        self.run_notice('Discovery and preparation passed', **discovery, GITHUB_RUN_ID='22',
                        GITHUB_RUN_ATTEMPT='2', MAKE_ISSUE='', RECOVERY_VERIFIED='true', NTFY_URL='')
        for run, attempt in (('20', '7'), ('22', '1'), ('22', '2')):
            self.run_notice('Old discovery failure', **discovery, GITHUB_RUN_ID=run, GITHUB_RUN_ATTEMPT=attempt)
        self.assertEqual(set(self.blockers()), {('validation', 'candidate-a', '')})
        self.assertEqual(len(self.messages), 2)

        self.run_notice('Validation passed', BLOCKER_OPERATION='validation', GITHUB_RUN_ID='23',
                        MAKE_ISSUE='', RECOVERY_VERIFIED='true', NTFY_URL='')
        self.assertIsNone(self.issue)
        self.run_notice('Old discovery failure', **discovery, GITHUB_RUN_ATTEMPT='8')
        self.assertIsNone(self.issue)
        self.assertEqual(len(self.messages), 2)

        self.run_notice('Discovery failed again', **discovery, GITHUB_RUN_ID='22', GITHUB_RUN_ATTEMPT='3')
        self.assertEqual(self.blockers()[scope]['attempt'], 3)
        self.assertEqual(len(self.messages), 3)
        self.run_notice('Old validation failure', BLOCKER_OPERATION='validation', GITHUB_RUN_ID='22')
        self.assertEqual(set(self.blockers()), {scope})
        self.assertEqual(len(self.messages), 3)

    def test_success_first_blocks_older_reruns_with_or_without_an_open_issue(self):
        discovery = dict(BLOCKER_OPERATION='discovery', BLOCKER_CANDIDATE='a' * 40)
        verified = dict(MAKE_ISSUE='', RECOVERY_VERIFIED='true', NTFY_URL='')
        for existing in (False, True):
            with self.subTest(existing=existing):
                self.setUp()
                self.issue = None
                if existing:
                    self.run_notice('Unrelated release failure', GITHUB_RUN_ID='19', NTFY_URL='')
                for run, attempt in (('22', '1'), ('24', '2'), ('23', '9')):
                    self.run_notice('Discovery passed', **discovery, **verified,
                                    GITHUB_RUN_ID=run, GITHUB_RUN_ATTEMPT=attempt)
                if existing:
                    self.assertEqual(set(self.blockers()), {('release', 'candidate-a', '')})
                else:
                    self.assertIsNone(self.issue)
                    self.assertEqual(len(self.closed), 1)
                    self.assertEqual(self.closed[0]['title'], notify.HISTORY_TITLE)
                carrier = self.issue if existing else self.closed[0]
                self.assertEqual([notify.run_order(state) for state in notify.recovery_history(carrier['body'])],
                                 [(24, 2)])
                for run, attempt in (('20', '9'), ('23', '9'), ('24', '1'), ('24', '2')):
                    self.run_notice('Discovery failed', **discovery, GITHUB_RUN_ID=run, GITHUB_RUN_ATTEMPT=attempt)
                self.assertEqual(self.messages, [])
                self.run_notice('Discovery failed', **discovery, GITHUB_RUN_ID='24', GITHUB_RUN_ATTEMPT='3')
                self.assertEqual(self.blockers()['discovery', 'a' * 40, '']['attempt'], 3)
                self.assertEqual(len(self.messages), 1)

    def test_closed_blocker_records_later_verification(self):
        verified = dict(MAKE_ISSUE='', RECOVERY_VERIFIED='true', NTFY_URL='')
        self.run_notice(NTFY_URL='')
        self.run_notice('Release passed', GITHUB_RUN_ID='22', **verified)
        self.assertIsNone(self.issue)
        self.run_notice('Release still verified', GITHUB_RUN_ID='24', **verified)
        self.run_notice('Delayed failure', GITHUB_RUN_ID='23', GITHUB_RUN_ATTEMPT='9')
        self.assertIsNone(self.issue)
        self.assertEqual(self.messages, [])
        self.assertEqual([notify.run_order(state) for state in notify.recovery_history(self.closed[0]['body'])],
                         [(24, 1)])

    def test_clean_history_is_bounded_and_evicted_success_does_not_cover_late_failure(self):
        verified = dict(MAKE_ISSUE='', RECOVERY_VERIFIED='true', NTFY_URL='')
        self.issue = None
        with patch.object(notify, 'DISCOVERY_RECOVERY_LIMIT', 1), patch.object(notify, 'RECOVERY_LIMIT', 1):
            for run, candidate, operation in (('21', 'older-controller', 'discovery'),
                                              ('22', 'older-candidate', 'release'),
                                              ('23', 'retained-controller', 'discovery'),
                                              ('24', 'retained-candidate', 'release')):
                self.run_notice('Verified', **verified, GITHUB_RUN_ID=run, BLOCKER_CANDIDATE=candidate,
                                BLOCKER_OPERATION=operation)
            for candidate, operation in (('retained-controller', 'discovery'), ('retained-candidate', 'release')):
                self.run_notice('Late failure', BLOCKER_CANDIDATE=candidate, BLOCKER_OPERATION=operation)
            self.assertIsNone(self.issue)
            self.run_notice('Late failure', BLOCKER_CANDIDATE='older-controller', BLOCKER_OPERATION='discovery')
            self.run_notice('Late failure', BLOCKER_CANDIDATE='older-candidate')
        self.assertEqual(set(self.blockers()), {('discovery', 'older-controller', ''),
                                                ('release', 'older-candidate', '')})
        self.assertEqual(len(self.messages), 2)

    def test_recovery_history_bounds_distinct_scopes_and_reserves_discovery(self):
        self.run_notice('Keep the issue open', BLOCKER_CANDIDATE='unresolved', NTFY_URL='')

        def recover(operation, candidate, run):
            scope = dict(BLOCKER_OPERATION=operation, BLOCKER_CANDIDATE=candidate, NTFY_URL='')
            self.run_notice('Blocked', **scope, GITHUB_RUN_ID=str(run - 1))
            self.run_notice('Recovered', **scope, GITHUB_RUN_ID=str(run), MAKE_ISSUE='', RECOVERY_VERIFIED='true')

        def retained():
            return [(state['operation'], state['candidate'], notify.run_order(state))
                    for state in notify.recovery_history(self.issue['body'])]

        with patch.object(notify, 'DISCOVERY_RECOVERY_LIMIT', 1), patch.object(notify, 'RECOVERY_LIMIT', 2):
            recover('discovery', 'main-controller', 22)
            recover('release', 'candidate-a', 24)
            recover('validation', 'candidate-b', 26)
            recover('validation', 'candidate-b', 28)
            self.assertEqual(retained(), [('discovery', 'main-controller', (22, 1)),
                                          ('validation', 'candidate-b', (28, 1)), ('release', 'candidate-a', (24, 1))])
            recover('release', 'candidate-c', 30)
            self.assertEqual(retained(), [('discovery', 'main-controller', (22, 1)),
                                          ('release', 'candidate-c', (30, 1)), ('validation', 'candidate-b', (28, 1))])
            self.run_notice('Old discovery failure', BLOCKER_OPERATION='discovery', BLOCKER_CANDIDATE='main-controller',
                            GITHUB_RUN_ID='21')
        self.assertEqual(set(self.blockers()), {('release', 'unresolved', '')})
        self.assertEqual(self.messages, [])

    def test_legacy_issue_closure_does_not_establish_verified_recovery(self):
        self.run_notice(NTFY_URL='')
        self.gh('issue', 'close', '41')
        self.run_notice(GITHUB_RUN_ATTEMPT='2')
        self.assertEqual(self.blockers()['release', 'candidate-a', '']['attempt'], 2)
        self.assertEqual(len(self.messages), 1)

    def test_exact_recovery_retires_only_matching_publisher_selectors(self):
        reconciliation = dict(BLOCKER_OPERATION='reconciliation')
        self.run_notice('Could not resolve the publisher', **reconciliation, BLOCKER_CANDIDATE='publisher:7::')
        self.run_notice('Could not resolve another publisher', **reconciliation, BLOCKER_CANDIDATE='publisher:8::')
        self.run_notice('Other exact archive failed', **reconciliation, BLOCKER_CANDIDATE='publisher:7:2:74',
                        BLOCKER_ATTESTATION='74')
        self.run_notice('Exact archive failed verification', **reconciliation, BLOCKER_CANDIDATE='publisher:7:1:73',
                        BLOCKER_ATTESTATION='73')
        self.run_notice('Reconciliation and integration passed', **reconciliation, GITHUB_RUN_ID='21',
                        MAKE_ISSUE='', RECOVERY_VERIFIED='true', NTFY_URL='',
                        BLOCKER_CANDIDATE='publisher:7:1:73', BLOCKER_ATTESTATION='73')
        expected = {('reconciliation', 'publisher:8::', ''), ('reconciliation', 'publisher:7:2:74', '', '74')}
        self.assertEqual(set(self.blockers()), expected)
        self.run_notice('Could not resolve the publisher', **reconciliation, BLOCKER_CANDIDATE='publisher:7::',
                        GITHUB_RUN_ATTEMPT='2')
        self.run_notice('Exact archive failed verification', **reconciliation, BLOCKER_CANDIDATE='publisher:7:1:73',
                        BLOCKER_ATTESTATION='73', GITHUB_RUN_ATTEMPT='2')
        self.assertEqual(set(self.blockers()), expected)
        self.assertEqual(len(self.messages), 4)

    def test_success_first_keeps_distinct_attestation_for_the_same_candidate(self):
        self.issue = None
        self.run_notice('First archive verified', MAKE_ISSUE='', NTFY_URL='', RECOVERY_VERIFIED='true',
                        GITHUB_RUN_ID='22', BLOCKER_ATTESTATION='73')
        self.run_notice('Delayed first archive failure', BLOCKER_ATTESTATION='73', GITHUB_RUN_ATTEMPT='2')
        self.assertIsNone(self.issue)
        self.run_notice('Another archive failed', BLOCKER_ATTESTATION='74', GITHUB_RUN_ATTEMPT='2')
        self.assertEqual(self.blockers()['release', 'candidate-a', '', '74']['attestation'], '74')
        self.assertEqual(len(self.messages), 1)

    def test_older_recovery_preserves_later_failure(self):
        self.run_notice(GITHUB_RUN_ID='30', GITHUB_RUN_ATTEMPT='2')
        for run, attempt in (('29', '3'), ('30', '1')):
            self.run_notice('Verified earlier publication', MAKE_ISSUE='', RECOVERY_VERIFIED='true',
                            GITHUB_RUN_ID=run, GITHUB_RUN_ATTEMPT=attempt)
        self.assertIsNotNone(self.issue)
        self.assertIn('/actions/runs/30/attempts/2', self.issue['body'])

    def test_late_failure_cannot_replace_a_newer_run_or_attempt(self):
        self.run_notice('Verification failed', GITHUB_RUN_ID='31', GITHUB_RUN_ATTEMPT='2')
        for run, attempt in (('30', '7'), ('31', '1')):
            self.run_notice('Late preflight failure', GITHUB_RUN_ID=run, GITHUB_RUN_ATTEMPT=attempt,
                            BLOCKER_DIAGNOSIS='preflight=failure')
        self.assertIn('Verification failed', self.issue['body'])
        self.assertIn('/actions/runs/31/attempts/2', self.issue['body'])
        self.assertEqual(len(self.messages), 1)

    def test_inflight_acknowledgement_preserves_newer_blocker(self):
        original_post = self.post

        def interleaved_post(request, **kwargs):
            if b'/actions/runs/40/' in request.data:
                self.run_notice('Later core failure', GITHUB_RUN_ID='41', NTFY_URL='',
                                BLOCKER_DIAGNOSIS='core=failure')
            return original_post(request, **kwargs)

        with patch.object(self, 'post', side_effect=interleaved_post):
            self.run_notice(GITHUB_RUN_ID='40')
        self.assertIn('Later core failure', self.issue['body'])
        self.assertIn('/actions/runs/41/', self.issue['body'])
        self.assertEqual(self.blockers()['release', 'candidate-a', '']['delivery'], 'pending')

    def test_inflight_acknowledgement_preserves_operator_edit(self):
        original_post = self.post

        def interleaved_post(request, **kwargs):
            self.issue['body'] += '\n\nNew operator notes.'
            return original_post(request, **kwargs)

        with patch.object(self, 'post', side_effect=interleaved_post):
            self.run_notice()
        self.assertIn('New operator notes.', self.issue['body'])
        self.assertEqual(self.blockers()['release', 'candidate-a', '']['delivery'], 'accepted')

    def test_legacy_release_and_new_diagnostics_keep_independent_evidence_and_recovery(self):
        self.issue['body'] += ('\n\n'+notify.START+'\n<!-- state: '
            +json.dumps(dict(operation='release', candidate='candidate-a', attestation='73',
                             run='https://github.com/example/wallet/actions/runs/20', attempt=1))
            +' -->\nCurrent blocker: Release failed after publication\n'+notify.END)
        self.run_notice('Validation failed', BLOCKER_OPERATION='validation', GITHUB_RUN_ID='21')
        self.issue['body'] += '\n\nRetain these operator notes between diagnoses.'
        self.run_notice('Discovery failed', BLOCKER_OPERATION='discovery', GITHUB_RUN_ID='22')
        self.assertIn('Release failed after publication', self.issue['body'])
        self.assertEqual(self.blockers()['release', 'candidate-a', '', '73']['attestation'], '73')
        self.run_notice('Validation passed', BLOCKER_OPERATION='validation', GITHUB_RUN_ID='23',
                        MAKE_ISSUE='', RECOVERY_VERIFIED='true', NTFY_URL='')
        self.assertNotIn(('validation', 'candidate-a', ''), self.blockers())
        self.assertIn('Retain these operator notes between diagnoses.', self.issue['body'])
        self.run_notice('Discovery passed', BLOCKER_OPERATION='discovery', GITHUB_RUN_ID='24',
                        MAKE_ISSUE='', RECOVERY_VERIFIED='true', NTFY_URL='')
        self.assertEqual(set(self.blockers()), {('release', 'candidate-a', '', '73')})
        self.assertEqual(len(self.messages), 2)
        self.run_notice('Original release verified', BLOCKER_OPERATION='reconciliation', BLOCKER_ATTESTATION='73',
                        BLOCKER_CANDIDATE='publisher:20:1:73', GITHUB_RUN_ID='25', MAKE_ISSUE='',
                        RECOVERY_VERIFIED='true')
        self.assertIsNone(self.issue)

    def test_validation_recovery_requires_matching_candidate_and_requested_emulator(self):
        validation = dict(BLOCKER_OPERATION='validation', NTFY_URL='')
        self.run_notice('Emulator failed', **validation, BLOCKER_VALIDATION_EMULATOR='true')
        self.run_notice('Unsigned failed', **validation, BLOCKER_VALIDATION_EMULATOR='false', GITHUB_RUN_ID='21')
        recovered = dict(validation, MAKE_ISSUE='', RECOVERY_VERIFIED='true', GITHUB_RUN_ID='22')
        self.run_notice('Unsigned passed', **recovered, BLOCKER_VALIDATION_EMULATOR='false')
        self.assertEqual(set(self.blockers()), {('validation', 'candidate-a', 'true')})
        self.run_notice('Different candidate passed', **recovered, BLOCKER_VALIDATION_EMULATOR='true',
                        BLOCKER_CANDIDATE='candidate-b')
        self.assertIsNotNone(self.issue)
        self.run_notice('Emulator passed', **recovered, BLOCKER_VALIDATION_EMULATOR='true')
        self.assertIsNone(self.issue)

    def test_inflight_acknowledgement_updates_only_its_scope(self):
        original_post = self.post

        def interleaved_post(request, **kwargs):
            self.run_notice('Discovery failed', BLOCKER_OPERATION='discovery', GITHUB_RUN_ID='21', NTFY_URL='')
            return original_post(request, **kwargs)

        with patch.object(self, 'post', side_effect=interleaved_post):
            self.run_notice()
        self.assertEqual(self.blockers()['release', 'candidate-a', '']['delivery'], 'accepted')
        self.assertEqual(self.blockers()['discovery', 'candidate-a', '']['delivery'], 'pending')

    def test_later_unrelated_publication_preserves_blocker(self):
        for scope in (dict(BLOCKER_CANDIDATE='candidate-b'), dict(BLOCKER_OPERATION='discovery')):
            self.run_notice(**scope)
            self.run_notice('Unrelated publication verified', MAKE_ISSUE='', RECOVERY_VERIFIED='true',
                            GITHUB_RUN_ID='21')
            self.assertIsNotNone(self.issue)

    def test_later_build_failure_preserves_unresolved_publisher_until_exact_recovery(self):
        self.run_notice('Independent public verification failed', BLOCKER_ATTESTATION='73',
                        BLOCKER_DIAGNOSIS='verify=failure', NTFY_URL='')
        self.run_notice('Later admission failed', GITHUB_RUN_ID='21', NTFY_URL='',
                        BLOCKER_DIAGNOSIS='admission=failure')
        build = ('release', 'candidate-a', '')
        publisher = (*build, '73')
        self.assertEqual(set(self.blockers()), {build, publisher})
        self.run_notice('Build verified', MAKE_ISSUE='', RECOVERY_VERIFIED='true', NTFY_URL='', GITHUB_RUN_ID='22')
        self.assertEqual(set(self.blockers()), {publisher})
        self.run_notice('New build failed', GITHUB_RUN_ID='23', NTFY_URL='')
        self.run_notice('Original publisher verified', MAKE_ISSUE='', RECOVERY_VERIFIED='true', NTFY_URL='',
                        BLOCKER_OPERATION='reconciliation', BLOCKER_CANDIDATE='publisher:20:1:73',
                        BLOCKER_ATTESTATION='73', GITHUB_RUN_ID='24')
        self.assertEqual(set(self.blockers()), {build})
        self.assertIn('/actions/runs/23', self.blockers()[build]['run'])

    def test_verified_original_attestation_retires_its_publication_blocker(self):
        self.run_notice('Recording failed', BLOCKER_ATTESTATION='123')
        self.run_notice('Different output verified', MAKE_ISSUE='', RECOVERY_VERIFIED='true', GITHUB_RUN_ID='21',
                        BLOCKER_OPERATION='reconciliation', BLOCKER_CANDIDATE='publisher:20:1:999',
                        BLOCKER_ATTESTATION='999')
        self.assertIsNotNone(self.issue)
        self.run_notice('Original output verified', MAKE_ISSUE='', RECOVERY_VERIFIED='true', GITHUB_RUN_ID='22',
                        BLOCKER_OPERATION='reconciliation', BLOCKER_CANDIDATE='publisher:20:1:123',
                        BLOCKER_ATTESTATION='123')
        self.assertIsNone(self.issue)

    def test_unrecognized_saved_sections_survive_new_blockers_and_verified_recovery(self):
        for state, end in (('null', notify.END), ('[]', notify.END), ('invalid', notify.END),
                           ('{"operation":"release"}', notify.END),
                           ('{"operation":"unknown","candidate":"candidate-a"}', notify.END),
                           ('{"operation":"release","candidate":"candidate-a"}', '')):
            with self.subTest(state=state, end=end):
                original = notify.START+'\n<!-- state: '+state+' -->\nOriginal diagnosis\n'+end
                self.issue['body'] = original
                self.run_notice()
                self.assertIn(original, self.issue['body'])
                self.assertEqual(self.blockers()['release', 'candidate-a', '']['delivery'], 'accepted')
                self.run_notice('Current release verified', MAKE_ISSUE='', RECOVERY_VERIFIED='true',
                                GITHUB_RUN_ID='21', NTFY_URL='')
                self.assertIn(original, self.issue['body'])
                self.assertEqual(self.blockers(), {})
        self.assertEqual(len(self.messages), 6)

    def test_publication_reconciliation_does_not_claim_the_apk_is_unchanged(self):
        self.run_notice('Publication needs reconciliation: recording failed', BLOCKER_DIAGNOSIS='integration=failure')
        self.assertIn('recording failed', self.issue['body'])
        self.assertNotIn('Last published APK is unchanged', self.issue['body'])


if __name__ == '__main__':
    unittest.main()
