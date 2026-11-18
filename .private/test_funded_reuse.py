"""Focused selection checks for funded evidence and its original bindings."""
import json
import unittest
from unittest.mock import Mock, patch

import funded_reuse
import hosted_claim
import published_history


class FundedReuseTests(unittest.TestCase):
    def test_history_migration_requires_exact_source_tree(self):
        original, rewritten, tree = 'a' * 40, 'b' * 40, 'c' * 40
        migration = {'sources': {original: {'commit': rewritten, 'tree': tree}}}
        with patch.object(published_history, 'migration', return_value=migration), \
                patch.object(published_history.subprocess, 'check_output', side_effect=(tree, 'd' * 40)):
            self.assertEqual(rewritten, published_history.source_revision(original))
            self.assertEqual('HEAD', published_history.source_revision('HEAD'))
            with self.assertRaisesRegex(ValueError, 'tree changed'):
                published_history.source_revision(original)

    def test_history_migration_preserves_exact_claim_and_rules(self):
        original, rewritten = 'a' * 40, 'b' * 40
        ref = 'refs/heads/testnet-claims/release-27'
        binding = {'version': 27}
        migration = {'claims': {original: {'ref': ref, 'commit': rewritten}}}
        metadata = Mock()
        results = [{'object': {'sha': rewritten}}, {'message': json.dumps(binding)},
                   [{'type': 'update'}, {'type': 'deletion'}]]
        with patch.object(published_history, 'migration', return_value=migration):
            metadata.api.side_effect = results
            hosted_claim.verify_ref(ref, binding, original, metadata)
            metadata.api.side_effect = results[:2] + [[{'type': 'update'}]]
            with self.assertRaisesRegex(ValueError, 'missing.*protections'):
                hosted_claim.verify_ref(ref, binding, original, metadata)
            with self.assertRaisesRegex(ValueError, 'Invalid migrated reservation'):
                hosted_claim.verify_ref(ref + '-other', binding, original, metadata)
            metadata.api.side_effect = [{'object': {'sha': 'c' * 40}}]
            with self.assertRaisesRegex(ValueError, 'different inputs'):
                hosted_claim.verify_ref(ref, binding, original, metadata)

    def test_history_migration_matches_only_the_pinned_release_and_asset(self):
        source, rewritten, tree = 'a' * 40, 'b' * 40, 'c' * 40
        tag, apk, release_id = 'v3.19.0-private.27', 'd' * 64, 27
        record = {'release': {'tag': tag, 'source': source, 'id': release_id,
                              'commit': rewritten, 'tree': tree, 'apk_sha256': apk}}
        api = Mock(return_value={'tree': {'sha': tree}})
        ref = {'type': 'commit', 'sha': rewritten}
        with patch.object(published_history, 'migration', return_value=record):
            matches = published_history.release_tag_matches
            self.assertTrue(matches(tag, source, apk, release_id, ref, api))
            self.assertFalse(matches(tag, source, 'e' * 64, release_id, ref, api))
            self.assertFalse(matches(tag, source, apk, release_id + 1, ref, api))
            self.assertFalse(matches(tag + '-other', source, apk, release_id, ref, api))
            self.assertFalse(matches(tag, source, apk, release_id, {'type': 'commit', 'sha': '0' * 40}, api))
            api.return_value = {'tree': {'sha': 'e' * 40}}
            self.assertFalse(matches(tag, source, apk, release_id, ref, api))
            self.assertTrue(matches('future', source, apk, release_id, {'type': 'commit', 'sha': source}, api))

    def test_unfunded_lane_cannot_provision_or_reserve_a_claim(self):
        workflow = (funded_reuse.ROOT / '.github/workflows/hosted-core.yml').read_text()
        provision = workflow.split('  provision-tor:\n', 1)[1].split('  reserve:\n', 1)[0]
        reserve = workflow.split('  reserve:\n', 1)[1].split('  wallet:\n', 1)[0]
        wallet = workflow.split('  wallet:\n', 1)[1]
        self.assertIn('!inputs.reuse_funded', provision)
        self.assertIn('!inputs.reuse_funded', reserve)
        self.assertIn('name: hosted-upgrade-receipt', wallet)
        self.assertNotIn('contents: write', wallet)

    def test_only_exact_reviewed_presentation_transitions_carry_forward(self):
        before = {path: '100644 blob ' + old for path, (old, _) in funded_reuse.REVIEWED_TRANSITIONS.items()}
        after = {path: '100644 blob ' + new for path, (_, new) in funded_reuse.REVIEWED_TRANSITIONS.items()}
        self.assertTrue(self.match(before, after))
        self.assertTrue(self.match(after, after))
        for path in (*before, 'gradle.properties', 'app/build.gradle.kts', 'wallet/src/Recovery.kt'):
            with self.subTest(path=path):
                self.assertFalse(self.match(before, {**after, path: 'different'}))
        self.assertFalse(self.match(after, before))

    def match(self, before, after):
        with patch.object(funded_reuse, 'input_manifest', side_effect=(before, after)):
            return funded_reuse.wallet_inputs_match('a' * 40, 'b' * 40)

    def test_provenance_and_signing_changes_stop_reuse(self):
        tag = 'v3.19.0-private.27'
        origin_tag = 'v3.19.0-private.26'
        origin = dict(release=origin_tag, source='a' * 40, sha256='e' * 64)
        old = {'core': {}, 'controller_sha': 'f' * 40}
        pair = dict(candidate_apk_sha256='b' * 64, instrumentation_apk_sha256='c' * 64)
        fixture = dict(pair, release=tag, source_sha='b' * 40, version_code=27)
        latest = dict(source_sha=fixture['source_sha'], versionCode=27, core=old['core'],
                      funded_reuse=origin, installed_upgrade=pair)
        metadata = Mock()
        with patch.object(funded_reuse, 'git', return_value=json.dumps(fixture).encode()), \
                patch.object(funded_reuse, 'read_record', side_effect=[latest_record := (latest, {}), (old, origin)]), \
                patch.object(funded_reuse, 'wallet_inputs_match', return_value=True), \
                patch.object(funded_reuse, 'signing_identity', side_effect=('original', 'changed')):
            self.assertIsNone(funded_reuse.select('c' * 40, 'd' * 40, metadata))
        latest['funded_reuse'] = dict(origin, sha256='1' * 64)
        with patch.object(funded_reuse, 'git', return_value=json.dumps(fixture).encode()), \
                patch.object(funded_reuse, 'read_record', side_effect=[latest_record, (old, origin)]):
            with self.assertRaisesRegex(ValueError, 'original integrated receipt'):
                funded_reuse.select('c' * 40, 'd' * 40, metadata)
        metadata.api.assert_not_called()


if __name__ == '__main__':
    unittest.main()
