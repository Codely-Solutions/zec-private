"""Small negative cases for promotion evidence; no emulator or network."""
import copy
import unittest
from acceptance_policy import requirements, validate_core, CORE_STAGES


class AcceptancePolicyTest(unittest.TestCase):
    def receipt(self):
        receipt = dict(schema=1, input_fingerprint='inputs', passed=True,
                       evidence_class='real-app-testnet', network='zcash-testnet',
                       clearnet_fallback=False, personal_wallet_access=False, real_funds_used=False,
                       stages={s: dict(passed=True, txId='a'*64) for s in CORE_STAGES},
                       network_enforcement=dict(passed=True), build_source_sha='a'*40,
                       baseline_source_sha='a0bd21f27bb53af0eeb9b433d0273c6f39b04e19',
                       baseline_version_code=3234006, candidate_version_code=3234007)
        for key in ('candidate_apk_sha256', 'baseline_apk_sha256', 'instrumentation_apk_sha256', 'harness_sha256'):
            receipt[key] = 'b'*64
        return receipt

    def test_required_real_evidence(self):
        self.assertTrue(validate_core(self.receipt(), 'inputs'))
        cases = [('network', 'mainnet'), ('input_fingerprint', 'old'),
                 ('evidence_class', 'simulation'), ('clearnet_fallback', True),
                 ('candidate_apk_sha256', None), ('candidate_version_code', 3234006),
                 ('baseline_source_sha', 'c'*40), ('passed', False)]
        for key, value in cases:
            bad = self.receipt(); bad[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_core(bad, 'inputs')
        for name in CORE_STAGES:
            bad = self.receipt(); del bad['stages'][name]
            with self.subTest(missing=name), self.assertRaises(ValueError):
                validate_core(bad, 'inputs')
        bad = self.receipt(); bad['stages']['restore']['txId'] = 'c'*64
        with self.assertRaises(ValueError): validate_core(bad, 'inputs')

    def test_conservative_triggers(self):
        self.assertEqual([], requirements(['README.md', '.private/STATUS.md'])['profiles'])
        for path in ('unknown/runtime.bin', 'gradle.properties', 'ui-lib/src/main/java/new/Thing.kt',
                     '.private/acceptance_policy.py', '.private/core-wallet/include.gradle'):
            self.assertIn('core-testnet', requirements([path])['profiles'])
        self.assertEqual(['production-quotes'], requirements(['ui-lib/src/main/java/SwapRepository.kt'])['profiles'])
        self.assertIn('production-quotes', requirements(['ui-lib/src/main/java/HttpClientProvider.kt'])['profiles'])


if __name__ == '__main__': unittest.main()
