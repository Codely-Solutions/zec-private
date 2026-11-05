"""Small negative cases for promotion evidence; no emulator or network."""
import copy
import hashlib
import os
import subprocess
import stat
import tempfile
from contextlib import ExitStack
from pathlib import Path
import unittest
from acceptance_policy import requirements, validate_core, validate_quotes, validate_binding, verify_promotion_revision, CORE_STAGES


class AcceptancePolicyTest(unittest.TestCase):
    def test_receipt_substitution_and_missing_proof_block(self):
        receipt=b'trusted acceptance'
        proof={'core_acceptance':dict(status='passed',input_fingerprint='inputs',
                                      receipt_sha256=hashlib.sha256(receipt).hexdigest())}
        validate_binding(proof,'core_acceptance','inputs',receipt,receipt)
        for changed in ({}, {'core_acceptance':dict(proof['core_acceptance'],status='pending')},
                        {'core_acceptance':dict(proof['core_acceptance'],receipt_sha256='a'*64)}):
            with self.assertRaises(ValueError):validate_binding(changed,'core_acceptance','inputs',receipt,receipt)
        with self.assertRaises(ValueError):validate_binding(proof,'core_acceptance','inputs',receipt,b'other receipt')
        pending={'core_acceptance':dict(status='pending',input_fingerprint='inputs')}
        with self.assertRaises(ValueError):validate_binding(pending,'core_acceptance','inputs',receipt,None)
        validate_binding(pending,'core_acceptance','inputs',receipt,None,allow_late=True)
        with self.assertRaises(ValueError):validate_binding(pending,'core_acceptance','inputs',receipt,b'forged',allow_late=True)

    def test_only_attestations_can_follow_a_validated_candidate(self):
        with ExitStack() as stack:
            guarded=os.environ.get('CODEX_GUARD_OUTPUT')
            root=Path(tempfile.mkdtemp(prefix='promotion-',dir=guarded)) if guarded else Path(stack.enter_context(tempfile.TemporaryDirectory()))
            def allow_fixture_cleanup():
                # Git marks object files read-only on Windows. Leave deletion to
                # the guard, but make this exact task-owned fixture removable.
                for path in (root/'.git/objects').rglob('*'):
                    if path.is_file():
                        assert not path.is_symlink() and path.resolve().is_relative_to(root.resolve())
                        path.chmod(path.stat().st_mode|stat.S_IWRITE)
            stack.callback(allow_fixture_cleanup)
            def git(*args):
                return subprocess.check_output(['git',*args],cwd=root,text=True,stderr=subprocess.DEVNULL).strip()
            def commit():
                git('add','-A');git('-c','user.name=Acceptance test','-c','user.email=test@example.invalid','commit','-qm','Fixture')
                return git('rev-parse','HEAD')
            git('init','-q')
            (root/'app.kt').write_text('immutable runtime')
            candidate=commit()
            (root/'.private').mkdir()
            (root/'.private/core-acceptance.json').write_text('{}')
            attestation=commit()
            self.assertTrue(verify_promotion_revision(candidate,attestation,root=root))
            self.assertFalse(verify_promotion_revision(attestation,attestation,root=root))
            # A prepared child source remains available to the upstream path.
            self.assertFalse(verify_promotion_revision(attestation,candidate,root=root))
            (root/'.private/publish.py').write_text('changed controller')
            changed_controller=commit()
            with self.assertRaises(ValueError):verify_promotion_revision(candidate,changed_controller,root=root)
            (root/'app.kt').write_text('different runtime')
            changed=commit()
            with self.assertRaises(ValueError):verify_promotion_revision(candidate,changed,root=root)

    def receipt(self):
        receipt = dict(schema=1, input_fingerprint='inputs', passed=True,
                       evidence_class='real-app-testnet', network='zcash-testnet',
                       clearnet_fallback=False, personal_wallet_access=False, real_funds_used=False,
                       stages={s: dict(passed=True, txId='a'*64) for s in CORE_STAGES},
                       network_enforcement=dict(passed=True), build_source_sha='a'*40,
                       baseline_source_sha='a0bd21f27bb53af0eeb9b433d0273c6f39b04e19',
                       build_input_fingerprint='d'*64,
                       baseline_version_code=3234006, candidate_version_code=3234007)
        for key in ('candidate_apk_sha256', 'baseline_apk_sha256', 'instrumentation_apk_sha256', 'harness_sha256'):
            receipt[key] = 'b'*64
        receipt['stages']['interruption'].update(sync_restarted=True,synced=True,balance_preserved=True,
                                               history_preserved=True,blocked_packets=1,scanned_height=12345)
        receipt['stages']['fund'].update(funding_txid='c'*64,balance_zatoshi=10000000)
        receipt['funding_setup']=dict(tor_exit_verified=True,persistent_tor_connection=True,txid='c'*64)
        receipt['sync_preflight']=dict(passed=True,mode='advancing-scan',scanned_height=12340,network_height=12345)
        receipt['stages']['send'].update(fee_zatoshi=10000)
        receipt['stages']['restore'].update(mined_height=12346,amount_zatoshi=1000000)
        return receipt

    def test_required_real_evidence(self):
        self.assertTrue(validate_core(self.receipt(), 'inputs'))
        cases = [('network', 'mainnet'), ('input_fingerprint', 'old'),
                 ('evidence_class', 'simulation'), ('clearnet_fallback', True),
                 ('candidate_apk_sha256', None), ('candidate_version_code', 3234006),
                 ('baseline_source_sha', 'c'*40), ('passed', False),('sync_preflight',{})]
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
        for key in ('sync_restarted','synced','balance_preserved','history_preserved'):
            bad=self.receipt();bad['stages']['interruption'][key]=False
            with self.subTest(recovery=key),self.assertRaises(ValueError):validate_core(bad,'inputs')

    def test_conservative_triggers(self):
        self.assertEqual([], requirements(['README.md', '.private/STATUS.md'])['profiles'])
        self.assertEqual([], requirements(['ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/about/view/AboutView.kt'])['profiles'])
        for path in ('unknown/runtime.bin', 'gradle.properties', 'ui-lib/src/main/java/new/Thing.kt',
                     '.private/acceptance_policy.py', '.private/core-wallet/include.gradle'):
            self.assertIn('core-testnet', requirements([path])['profiles'])
        self.assertEqual(['production-quotes'], requirements(['ui-lib/src/main/java/SwapRepository.kt'])['profiles'])
        self.assertIn('production-quotes', requirements(['ui-lib/src/main/java/HttpClientProvider.kt'])['profiles'])
        self.assertEqual(['deterministic-regressions'],requirements(['ui-lib/src/test/java/WalletTest.kt'])['profiles'])

    def test_quotes_need_real_repeated_tor_evidence(self):
        receipt=dict(schema=1,input_fingerprint='inputs',evidence_class='real-app-production-quotes',
                     passed=True,tor_enabled=True,funds_transferred=False,personal_wallet_access=False,
                     quote_requests=3,quote_reviews=3,dismissals=3,executed_apk_sha256='a'*64,build_source_sha='b'*40)
        self.assertTrue(validate_quotes(receipt,'inputs'))
        for key,value in (('dismissals',2),('tor_enabled',False),('input_fingerprint','old'),('funds_transferred',True)):
            bad=dict(receipt);bad[key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):validate_quotes(bad,'inputs')


if __name__ == '__main__': unittest.main()
