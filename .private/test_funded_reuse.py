"""Focused selection checks for funded evidence and its original bindings."""
import json
import unittest
from unittest.mock import Mock, patch

import funded_reuse


class FundedReuseTests(unittest.TestCase):
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
