"""Exercise the notifier's issue persistence and transport consumer together."""
import json
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

import notify
from test_support import patch_environment


class NotifyTest(unittest.TestCase):
    def setUp(self):
        self.issue = dict(number=41, title=notify.TITLE, body='Original diagnosis and operator notes.')
        self.commands = []
        self.messages = []
        self.response = Mock(status=200)
        self.response.__enter__ = Mock(return_value=self.response)
        self.response.__exit__ = Mock(return_value=False)

    def gh(self, *args):
        self.commands.append(args)
        if args[:2] == ('issue', 'list'):
            return json.dumps([self.issue] if self.issue else [])
        if args[:2] in (('issue', 'edit'), ('issue', 'create')):
            body = Path(args[args.index('--body-file')+1]).read_text(encoding='utf-8')
            if self.issue is None:
                self.issue = dict(number=41, title=notify.TITLE)
            self.issue['body'] = body
            return 'https://github.com/example/wallet/issues/41'
        if args[:2] == ('issue', 'close'):
            self.issue = None
            return ''
        raise AssertionError(args)

    def post(self, request, **kwargs):
        self.messages.append(request.data.decode())
        return self.response

    def run_notice(self, message='Blocked at build', **environment):
        values = dict(GITHUB_REPOSITORY='example/wallet', GITHUB_RUN_ID='20', MAKE_ISSUE='true',
                      NTFY_URL='https://secret.invalid/topic', RECOVERY_VERIFIED='', BLOCKER_DIAGNOSIS='build=failure')
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
        self.assertEqual(notify.current_state(self.issue['body'])['delivery'], 'accepted')

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
        self.assertEqual(notify.current_state(self.issue['body'])['delivery'], 'pending')
        self.run_notice(GITHUB_RUN_ID='21')
        self.assertEqual(len(self.messages), 1)

    def test_failed_transport_keeps_current_evidence_and_is_retryable_without_secret(self):
        self.response.status = 503
        with self.assertRaisesRegex(SystemExit, '^ntfy notification failed: RuntimeError$'):
            self.run_notice()
        self.assertIn('/actions/runs/20', self.issue['body'])
        self.assertEqual(notify.current_state(self.issue['body'])['delivery'], 'pending')
        self.response.status = 200
        self.run_notice(GITHUB_RUN_ID='21')
        self.assertEqual(len(self.messages), 2)
        self.assertEqual(notify.current_state(self.issue['body'])['delivery'], 'accepted')

    def test_issue_failure_does_not_deliver_unrecorded_blocker(self):
        def failing(*args):
            if args[:2] == ('issue', 'edit'):
                raise RuntimeError('synthetic GitHub failure')
            return self.gh(*args)
        with patch_environment(dict(GITHUB_REPOSITORY='example/wallet', GITHUB_RUN_ID='20', MAKE_ISSUE='true')), \
                patch.object(notify, 'gh', side_effect=failing), patch.object(notify.urllib.request, 'urlopen') as post:
            with self.assertRaises(RuntimeError):
                notify.main('Blocked')
            post.assert_not_called()

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

    def test_older_recovery_preserves_later_failure(self):
        self.run_notice(GITHUB_RUN_ID='30')
        self.run_notice('Verified earlier publication', MAKE_ISSUE='', RECOVERY_VERIFIED='true', GITHUB_RUN_ID='29')
        self.assertIsNotNone(self.issue)
        self.assertIn('/actions/runs/30', self.issue['body'])

    def test_malformed_saved_state_is_treated_as_undelivered(self):
        for state in ('null', '[]', 'invalid'):
            self.issue['body'] = notify.START+'\n<!-- state: '+state+' -->\nold\n'+notify.END
            self.run_notice()
        self.assertEqual(len(self.messages), 3)


if __name__ == '__main__':
    unittest.main()
