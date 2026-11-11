"""Small negative cases for promotion evidence; no emulator or network."""
import copy
import hashlib
import io
import runpy
import json
import os
import subprocess
import stat
import tempfile
from contextlib import ExitStack, redirect_stdout
from pathlib import Path
import unittest
from unittest.mock import patch
from acceptance_policy import profile_input, changed_inputs, input_fingerprint, requirements, validate_core, validate_quotes, validate_binding, verify_promotion_revision, CORE_STAGES, CORE_TEST_SOURCE, CORE_BINDING, validate_core_sources, BASELINE_INSTRUMENTATION_STAGES, CANDIDATE_INSTRUMENTATION_STAGES


class AcceptancePolicyTest(unittest.TestCase):
    def test_emulator_reuse_helpers_invalidate_acceptance(self):
        for path in ('.private/prepared_apks.py', '.private/ui_capture.py', '.private/ui_step.py', '.private/faucet_pow.cjs'):
            self.assertIn('core-testnet', requirements([path])['profiles'])

    def test_foundation_changes_invalidate_wallet_and_quote_acceptance(self):
        result=requirements(['wallet-foundation-lib/src/main/kotlin/Flow.kt'])
        self.assertEqual({'core-testnet','production-quotes'},set(result['profiles']))

    def test_receipt_substitution_and_missing_proof_block(self):
        receipt=b'trusted acceptance'
        proof={'core_acceptance':dict(status='passed',binding=CORE_BINDING,input_fingerprint='inputs',
                                      receipt_sha256=hashlib.sha256(receipt).hexdigest())}
        validate_binding(proof,'core_acceptance','inputs',receipt,receipt)
        for changed in ({}, {'core_acceptance':dict(proof['core_acceptance'],status='pending')},
                        {'core_acceptance':dict(proof['core_acceptance'],receipt_sha256='a'*64)}):
            with self.assertRaises(ValueError):validate_binding(changed,'core_acceptance','inputs',receipt,receipt)
        with self.assertRaises(ValueError):validate_binding(proof,'core_acceptance','inputs',receipt,b'other receipt')
        pending={'core_acceptance':dict(status='pending',binding=CORE_BINDING,input_fingerprint='inputs')}
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
            harness=root/CORE_TEST_SOURCE
            harness.parent.mkdir(parents=True,exist_ok=True);harness.write_text('dedicated test source')
            candidate=commit()
            (root/'.private').mkdir(exist_ok=True)
            (root/'.private/core-acceptance.json').write_text('{}')
            attestation=commit()
            self.assertTrue(verify_promotion_revision(candidate,attestation,root=root))
            self.assertFalse(verify_promotion_revision(attestation,attestation,root=root))
            # An upstream child cannot supply its own attestation.
            with self.assertRaisesRegex(ValueError, 'changed controller-owned files'):
                verify_promotion_revision(attestation,candidate,root=root)
            (root/'.private/publish.py').write_text('changed controller')
            changed_controller=commit()
            with self.assertRaises(ValueError):verify_promotion_revision(candidate,changed_controller,root=root)
            (root/'app.kt').write_text('different runtime')
            changed=commit()
            with self.assertRaises(ValueError):verify_promotion_revision(candidate,changed,root=root)

    def receipt(self, **identities):
        baseline = json.loads(Path(__file__).with_name('upgrade-baseline.json').read_text())
        receipt = dict(schema=1, input_fingerprint='inputs', passed=True,
                       evidence_class='real-app-testnet', network='zcash-testnet',
                       clearnet_fallback=False, personal_wallet_access=False, real_funds_used=False,
                       stages={s: dict(passed=True, txId='a'*64) for s in CORE_STAGES},
                       network_enforcement=dict(passed=True,metadata_no_direct_fallback_observed=True,direct_bulk_targets=['192.0.2.1']), build_source_sha='a'*40,
                       baseline_source_sha=baseline['source_sha'],
                       build_input_fingerprint='d'*64,runtime_input_fingerprint='e'*64,
                       baseline_version_code=baseline['version_code'], candidate_version_code=baseline['version_code']+1)
        receipt.update(identities)
        for key in ('candidate_apk_sha256', 'baseline_apk_sha256', 'instrumentation_apk_sha256', 'harness_sha256'):
            receipt[key] = 'b'*64
        receipt['baseline_instrumentation_apk_sha256'] = baseline['instrumentation']['apk_sha256']
        pairing = dict(stages={})
        for role, original, source, version in (
                ('baseline', baseline['instrumentation']['apk_sha256'], baseline['source_sha'], baseline['version_code']),
                ('candidate', receipt['instrumentation_apk_sha256'], receipt['build_source_sha'], receipt['candidate_version_code'])):
            pairing[role] = dict(original_sha256=original, executed_sha256=('1' if role=='baseline' else '2')*64,
                                 source_sha=source, app_version_code=version)
        for stage in BASELINE_INSTRUMENTATION_STAGES | CANDIDATE_INSTRUMENTATION_STAGES:
            role = 'baseline' if stage in BASELINE_INSTRUMENTATION_STAGES else 'candidate'
            pairing['stages'][stage] = dict(role=role, **pairing[role], installed_hash_verified=True)
        receipt['instrumentation_pairing'] = pairing
        receipt['stages']['interruption'].update(sync_restarted=True,synced=True,balance_preserved=True,
                                               history_preserved=True,metadata_blocked=True,direct_bulk_reachable=True,
                                               metadata_recovered=True,blocked_packets=1,scanned_height=12345)
        receipt['stages']['fund'].update(funding_txid='c'*64,balance_zatoshi=10000000,app_transaction_state='Confirmed')
        receipt['funding_setup']=dict(tor_exit_verified=False,tor_transport_verified=True,
                                     proxy_unavailable_failed_closed=True,persistent_tor_connection=True,txid='c'*64)
        receipt['sync_preflight']=dict(passed=True,mode='advancing-scan',scanned_height=12340,network_height=12345)
        receipt['stages']['send'].update(fee_zatoshi=10000,ui_review=True,ui_cancel_no_submission=True,ui_approval=True,ui_approval_actions=1,
            shielded_donation=True,multi_recipient_sdk_accepted=True,multi_recipient_wallet_rejected=True,
            automatic_identifying_memo=False,reviewed_memo_verified=True,multi_recipient_submitted=False,
            clipboard_metadata_verified=True,newer_clipboard_preserved=True)
        receipt['stages']['restore'].update(mined_height=12346,amount_zatoshi=1000000,app_transaction_state='Confirmed',reviewed_memo_verified=True)
        return receipt

    def test_required_real_evidence(self):
        self.assertTrue(validate_core(self.receipt(), 'inputs'))
        cases = [('network', 'mainnet'), ('input_fingerprint', 'old'),
                 ('evidence_class', 'simulation'), ('clearnet_fallback', True),
                 ('runtime_input_fingerprint',None), ('candidate_apk_sha256', None), ('candidate_version_code', 3234009),
                 ('baseline_source_sha', 'c'*40), ('passed', False),('sync_preflight',{})]
        for key, value in cases:
            bad = self.receipt(); bad[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_core(bad, 'inputs')

        for stage,key,value in [('send','ui_approval',False),('send','ui_cancel_no_submission',False),
                                ('send','ui_review',False),('send','ui_approval_actions',2),('fund','app_transaction_state','Pending'),
                                ('send','automatic_identifying_memo',True),('send','reviewed_memo_verified',False),
                                ('restore','reviewed_memo_verified',False),('send','clipboard_metadata_verified',False),
                                ('send','newer_clipboard_preserved',False)]:
            bad=self.receipt();bad['stages'][stage][key]=value
            with self.assertRaises(ValueError):validate_core(bad,'inputs')
        for name in CORE_STAGES:
            bad = self.receipt(); del bad['stages'][name]
            with self.subTest(missing=name), self.assertRaises(ValueError):
                validate_core(bad, 'inputs')
        bad = self.receipt(); bad['stages']['restore']['txId'] = 'c'*64
        with self.assertRaises(ValueError): validate_core(bad, 'inputs')
        for key in ('sync_restarted','synced','balance_preserved','history_preserved','metadata_blocked','direct_bulk_reachable','metadata_recovered'):
            bad=self.receipt();bad['stages']['interruption'][key]=False
            with self.subTest(recovery=key),self.assertRaises(ValueError):validate_core(bad,'inputs')
        for key in ('tor_transport_verified','proxy_unavailable_failed_closed','persistent_tor_connection'):
            for value in (None,False):
                bad=self.receipt();bad['funding_setup'][key]=value
                with self.subTest(funding=key,value=value),self.assertRaises(ValueError):validate_core(bad,'inputs')
        bad=self.receipt()
        bad['funding_setup']=dict(tor_exit_verified=True,persistent_tor_connection=True,txid='c'*64)
        with self.assertRaises(ValueError):validate_core(bad,'inputs')

    def test_updater_transport_distinguishes_quota_from_metadata_success(self):
        for status in (200, 403, 429):
            receipt = self.receipt()
            receipt['stages']['interruption'].update(
                update_metadata_valid_before=True, update_http_status_after=status,
                update_metadata_valid_after=status == 200, update_rate_limited_after=status != 200)
            self.assertTrue(validate_core(receipt, 'inputs'))
        for change in ({'update_http_status_after': 404}, {'update_rate_limited_after': False},
                       {'update_metadata_valid_before': False}, {'update_metadata_valid_after': True}):
            bad = self.receipt()
            recovery = dict(update_metadata_valid_before=True, update_http_status_after=403,
                            update_metadata_valid_after=False, update_rate_limited_after=True)
            recovery.update(change)
            bad['stages']['interruption'].update(recovery)
            with self.subTest(change=change), self.assertRaisesRegex(ValueError, 'Updater recovery'):
                validate_core(bad, 'inputs')

    def test_rejects_wrong_baseline_instrumentation_hash_and_stage_pair(self):
        bad = self.receipt(); bad['baseline_instrumentation_apk_sha256'] = 'f'*64
        with self.assertRaises(ValueError): validate_core(bad, 'inputs')
        for stage, role in (('sender', 'candidate'), ('fund', 'candidate'), ('upgrade', 'baseline'), ('send', 'baseline')):
            bad = self.receipt()
            pairing = bad['instrumentation_pairing']
            pairing['stages'][stage] = dict(role=role, **pairing[role], installed_hash_verified=True)
            with self.subTest(stage=stage), self.assertRaises(ValueError): validate_core(bad, 'inputs')
        bad = self.receipt(); bad['instrumentation_pairing']['stages']['sender']['executed_sha256'] = 'f'*64
        with self.assertRaises(ValueError): validate_core(bad, 'inputs')
        bad = self.receipt(); bad['instrumentation_pairing']['stages']['upgrade']['installed_hash_verified'] = False
        with self.assertRaises(ValueError): validate_core(bad, 'inputs')

    def test_conservative_triggers(self):
        self.assertEqual([], requirements(['README.md', '.private/STATUS.md'])['profiles'])
        self.assertEqual([], requirements(['ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/about/view/AboutView.kt'])['profiles'])
        for path in ('unknown/runtime.bin', 'gradle.properties', 'ui-lib/src/main/java/new/Thing.kt',
                     '.private/acceptance_policy.py', '.private/core-wallet/include.gradle'):
            self.assertIn('core-testnet', requirements([path])['profiles'])
        self.assertEqual(['core-testnet', 'production-quotes'], requirements(['ui-lib/src/main/java/SwapRepository.kt'])['profiles'])
        self.assertIn('production-quotes', requirements(['ui-lib/src/main/java/HttpClientProvider.kt'])['profiles'])
        self.assertEqual(['deterministic-regressions'],requirements(['ui-lib/src/test/java/WalletTest.kt'])['profiles'])

    def test_status_import_is_quiet_and_reuse_preserves_artifact_identity(self):
        output = io.StringIO()
        with redirect_stdout(output):
            module = runpy.run_path(str(Path(__file__).with_name('acceptance_status.py')))
        self.assertEqual('', output.getvalue())
        receipt = {'build_source_sha': 'a'*40, 'candidate_apk_sha256': 'b'*64,
                   'executed_apk_sha256': 'c'*64}
        namespace = module['status'].__globals__
        with ExitStack() as stack:
            stack.enter_context(patch.object(Path, 'read_bytes', return_value=json.dumps(receipt).encode()))
            fingerprint = stack.enter_context(patch.dict(namespace, {
                'input_fingerprint': lambda revision, **kwargs: 'same-inputs',
                'validate_core_sources': lambda value, **kwargs: True,
                'validate_quotes': lambda value, inputs: True,
            }))
            report = module['status'](root=Path(__file__).parents[1], revision='d'*40)
        self.assertTrue(report['publication_permitted'])
        core = report['reusable_acceptance']['core-testnet']
        quote = report['reusable_acceptance']['production-quotes']
        self.assertEqual('a'*40, core['build_source_sha'])
        self.assertEqual('b'*64, core['executed_apk_sha256'])
        self.assertEqual('c'*64, quote['executed_apk_sha256'])
        self.assertEqual(hashlib.sha256(json.dumps(receipt).encode()).hexdigest(), core['receipt_sha256'])

    def test_status_reports_required_profiles_and_changed_inputs(self):
        receipt = json.dumps({'build_source_sha': 'a'*40})
        with ExitStack() as stack:
            stack.enter_context(patch.dict(os.environ, {'VALIDATION_ONLY': 'true'}))
            stack.enter_context(patch.object(Path, 'read_bytes', return_value=receipt.encode()))
            stack.enter_context(patch('acceptance_policy.input_fingerprint', return_value='current'))
            stack.enter_context(patch('acceptance_policy.validate_core_sources', side_effect=ValueError('stale core')))
            stack.enter_context(patch('acceptance_policy.validate_quotes', side_effect=ValueError('stale quotes')))
            stack.enter_context(patch('acceptance_policy.changed_inputs', return_value=['shared/Runtime.kt']))
            output = stack.enter_context(redirect_stdout(io.StringIO()))
            runpy.run_path(str(Path(__file__).with_name('acceptance_status.py')), run_name='__main__')
        status = json.loads(output.getvalue())
        self.assertFalse(status['publication_permitted'])
        self.assertEqual(['core-testnet', 'production-quotes'], status['pending_acceptance'])
        for detail in status['acceptance_diagnostics'].values():
            self.assertEqual(['shared/Runtime.kt'], detail['changed_inputs'])
            self.assertEqual('a'*40, detail['compared_source_sha'])
            self.assertIn('stale', detail['reason'])

    def test_selection_matches_profile_boundaries(self):
        cases = {
            'ui-lib/src/main/java/SwapRepository.kt': {'core-testnet', 'production-quotes'},
            'wallet-foundation-lib/src/main/kotlin/Storage.kt': {'core-testnet', 'production-quotes'},
            'app/src/main/res/xml/network.xml': {'core-testnet', 'production-quotes'},
            'build.gradle.kts': {'core-testnet', 'production-quotes'},
            'unknown/runtime.bin': {'core-testnet', 'production-quotes'},
            '.private/core_faucet.py': {'core-testnet'},
            '.private/upgrade-baseline.json': {'core-testnet'},
            CORE_TEST_SOURCE: {'core-testnet'},
            'ui-lib/src/main/java/screen/theme/Colors.kt': set(),
            'docs/release.md': set(),
            'ui-lib/src/androidTest/java/WalletTest.kt': {'deterministic-regressions'},
            'ui-lib/src/test/java/WalletTest.kt': {'deterministic-regressions'},
        }
        for path, expected in cases.items():
            with self.subTest(path=path):
                selection = requirements([path])
                self.assertEqual(expected, set(selection['profiles']))
                for profile in ('core-testnet', 'production-quotes'):
                    self.assertEqual(profile in expected, profile_input(path, profile))
                    if profile in expected:
                        self.assertEqual([path], selection['profile_reasons'][profile])

    def test_fingerprints_track_shared_and_unknown_inputs_and_ignore_version_labels(self):
        # Exercise committed trees, including deletion/rename, rather than just path classifiers.
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            baseline_path = root / '.private/upgrade-baseline.json'
            baseline_path.parent.mkdir(parents=True, exist_ok=True)
            baseline_path.write_bytes((Path(__file__).parent / 'upgrade-baseline.json').read_bytes())
            def git(*args):
                return subprocess.check_output(['git',*args],cwd=root,text=True,stderr=subprocess.DEVNULL).strip()
            git('init','-q')
            fixture={
                'gradle.properties':'ZCASH_VERSION_CODE=1\nSDK=stable\n',
                'ui-lib/src/main/java/SwapRepository.kt':'provider and funding',
                'wallet-foundation-lib/src/main/kotlin/Flow.kt':'shared composition',
                'app/src/main/res/xml/network.xml':'network rules',
                'unknown/runtime.bin':'unclassified',
                '.private/core-wallet/Flow.kt':'unknown test source',
                CORE_TEST_SOURCE:'dedicated instrumentation',
                '.private/upgrade-baseline.json':'baseline identity',
                '.private/emulator_session.py':'session lifecycle',
                '.private/emulator_batch.py':'batch scheduler',
                '.private/admit_emulator_batch.py':'batch admission',
                '.private/faucet_pow.cjs':'bounded funding work',
                'README.md':'documentation',
                'ui-lib/src/test/java/Test.kt':'test-only',
            }
            try:
                for name,value in fixture.items():
                    path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(value)
                git('add','-A');base=git('write-tree')
                original={profile:input_fingerprint(base,root=root,profile=profile)
                          for profile in ('core-testnet','production-quotes','core-build','core-runtime')}
                for name in fixture:
                    path=root/name;path.write_text(fixture[name]+' changed')
                    if name=='gradle.properties':path.write_text(fixture[name].replace('CODE=1','CODE=2'))
                    git('add','-A');tree=git('write-tree')
                    for profile in original:
                        changed = changed_inputs(base, tree, root=root, profile=profile)
                        self.assertEqual(profile_input(name, profile) and name != 'gradle.properties', name in changed)
                        expected=(name not in ('README.md','ui-lib/src/test/java/Test.kt','gradle.properties')
                                  and not (profile=='production-quotes' and name.startswith('.private/'))
                                  and not (profile=='core-build' and name in ('.private/upgrade-baseline.json',
                                      '.private/emulator_session.py', '.private/emulator_batch.py',
                                      '.private/admit_emulator_batch.py', '.private/faucet_pow.cjs'))
                                  and not (profile=='core-runtime' and name==CORE_TEST_SOURCE))
                        with self.subTest(path=name,profile=profile):
                            self.assertEqual(expected,input_fingerprint(tree,root=root,profile=profile)!=original[profile])
                    path.write_text(fixture[name]);git('add','-A')
                path=root/'unknown/runtime.bin';path.rename(root/'unknown/renamed.bin')
                git('add','-A');tree=git('write-tree')
                for profile in original:
                    self.assertNotEqual(original[profile],input_fingerprint(tree,root=root,profile=profile))
                (root/'unknown/renamed.bin').unlink();git('add','-A');tree=git('write-tree')
                self.assertNotEqual(original['core-testnet'],input_fingerprint(tree,root=root))
            finally:
                for path in (root/'.git').rglob('*'):
                    if path.is_file():path.chmod(path.stat().st_mode|stat.S_IWRITE)

    def test_test_source_can_follow_candidate_without_changing_product_binding(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            baseline_path = root / '.private/upgrade-baseline.json'
            baseline_path.parent.mkdir(parents=True, exist_ok=True)
            baseline_path.write_bytes((Path(__file__).parent / 'upgrade-baseline.json').read_bytes())
            def git(*args):
                return subprocess.check_output(['git',*args],cwd=root,text=True,stderr=subprocess.DEVNULL).strip()
            def commit():
                git('add','-A');git('-c','user.name=Fixture','-c','user.email=test@example.invalid','commit','-qm','Fixture')
                return git('rev-parse','HEAD')
            git('init','-q');(root/'app.kt').write_text('unchanged product')
            harness=root/CORE_TEST_SOURCE;harness.parent.mkdir(parents=True,exist_ok=True);harness.write_text('old test')
            try:
                candidate=commit();harness.write_text('test now waits for enabled control');accepted=commit()
                self.assertTrue(verify_promotion_revision(candidate,accepted,root=root))
                receipt=self.receipt(build_source_sha=accepted);receipt.update(
                    input_fingerprint=input_fingerprint(accepted,root=root),
                    build_input_fingerprint=input_fingerprint(accepted,root=root,profile='core-build'),
                    runtime_input_fingerprint=input_fingerprint(candidate,root=root,profile='core-runtime'))
                self.assertEqual(receipt['runtime_input_fingerprint'],validate_core_sources(receipt,candidate,accepted,root=root))
                for key,value in [('runtime_input_fingerprint','f'*64),('input_fingerprint','old'),
                                  ('build_input_fingerprint','f'*64),('build_source_sha',candidate)]:
                    bad=dict(receipt);bad[key]=value
                    with self.subTest(stale=key),self.assertRaises(ValueError):
                        validate_core_sources(bad,candidate,accepted,root=root)
                # A controller-prepared upstream child owns its changed app inputs.
                (root/'app.kt').write_text('reviewed upstream product change')
                (root/'.private/candidate.json').write_text('{}')
                (root/'.private/source-state.json').write_text('{}')
                child=commit()
                child_receipt=self.receipt(build_source_sha=child);child_receipt.update(
                    input_fingerprint=input_fingerprint(child,root=root),
                    build_input_fingerprint=input_fingerprint(child,root=root,profile='core-build'),
                    runtime_input_fingerprint=input_fingerprint(child,root=root,profile='core-runtime'))
                validate_core_sources(child_receipt,child,root=root)
                self.assertFalse(verify_promotion_revision(child,accepted,root=root))
                for name in ('.private/publish.py', '.private/quote-acceptance.json',
                             '.github/workflows/build-release.yml', 'LICENSE'):
                    path = root / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text('untrusted replacement')
                    with self.subTest(upstream_protected=name), self.assertRaisesRegex(
                            ValueError, 'changed controller-owned files'):
                        verify_promotion_revision(commit(), accepted, root=root)
                    path.unlink()
                (root/'app.kt').write_text('unchanged product');commit()
                forbidden=['app.kt','build.gradle.kts','.private/core-wallet/include.gradle',
                           '.private/core-wallet/src/Other.kt','.private/core_testnet_driver.py',
                           '.private/acceptance_policy.py','.github/workflows/core-validation.yml']
                for name in forbidden:
                    path=root/name;path.parent.mkdir(parents=True,exist_ok=True)
                    old=path.read_bytes() if path.exists() else None;path.write_text('changed trust or runtime input')
                    changed=commit()
                    with self.subTest(forbidden=name),self.assertRaises(ValueError):
                        verify_promotion_revision(candidate,changed,root=root)
                    if old is None:path.unlink()
                    else:path.write_bytes(old)
                    commit()
                harness.unlink();git('add','-A');deleted=git('write-tree')
                with self.assertRaises(ValueError):input_fingerprint(deleted,root=root,profile='core-runtime')
                harness.write_text('test now waits for enabled control');git('add','-A')
                git('update-index','--chmod=+x',CORE_TEST_SOURCE);mode=git('write-tree')
                with self.assertRaises(ValueError):input_fingerprint(mode,root=root,profile='core-runtime')
            finally:
                for path in (root/'.git').rglob('*'):
                    if path.is_file():path.chmod(path.stat().st_mode|stat.S_IWRITE)

    def test_fresh_host_acceptance_can_reuse_only_matching_compiled_inputs(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            baseline_path = root / '.private/upgrade-baseline.json'
            baseline_path.parent.mkdir(parents=True, exist_ok=True)
            baseline_path.write_bytes((Path(__file__).parent / 'upgrade-baseline.json').read_bytes())
            def git(*args):
                return subprocess.check_output(['git',*args],cwd=root,text=True,stderr=subprocess.DEVNULL).strip()
            def commit():
                git('add','-A');git('-c','user.name=Fixture','-c','user.email=test@example.invalid','commit','-qm','Fixture')
                return git('rev-parse','HEAD')
            def receipt_for(revision,producer):
                receipt=self.receipt(build_source_sha=producer)
                receipt.update(
                    input_fingerprint=input_fingerprint(revision,root=root),
                    runtime_input_fingerprint=input_fingerprint(revision,root=root,profile='core-runtime'),
                    build_input_fingerprint=input_fingerprint(producer,root=root,profile='core-build'))
                return receipt
            git('init','-q')
            fixture={'app.kt':'product runtime','build.gradle.kts':'build configuration',
                     CORE_TEST_SOURCE:'compiled instrumentation',
                     '.private/core-wallet/include.gradle':'test build wiring',
                     '.github/workflows/core-validation.yml':'producer workflow',
                     '.private/core_faucet.py':'old host setup',
                     '.private/acceptance_policy.py':'old host policy'}
            try:
                for name,value in fixture.items():
                    path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(value)
                producer=commit()
                old_receipt=receipt_for(producer,producer)
                (root/'.private/core_faucet.py').write_text('corrected host setup')
                (root/'.private/acceptance_policy.py').write_text('corrected host policy')
                current=commit()
                receipt=receipt_for(current,producer)
                self.assertNotEqual(old_receipt['input_fingerprint'],receipt['input_fingerprint'])
                self.assertNotEqual(old_receipt['runtime_input_fingerprint'],receipt['runtime_input_fingerprint'])
                self.assertEqual(receipt['build_input_fingerprint'],
                                 input_fingerprint(current,root=root,profile='core-build'))
                validate_core_sources(receipt,current,current,root=root)
                # An old full-input receipt is never evidence for the corrected host run.
                with self.assertRaises(ValueError):validate_core_sources(old_receipt,current,current,root=root)
                for key in ('input_fingerprint','runtime_input_fingerprint'):
                    bad=dict(receipt);bad[key]=old_receipt[key]
                    with self.subTest(stale=key),self.assertRaises(ValueError):
                        validate_core_sources(bad,current,current,root=root)
                # Host/controller corrections still require a new production candidate.
                with self.assertRaises(ValueError):validate_core_sources(receipt,producer,current,root=root)
                with self.assertRaises(ValueError):verify_promotion_revision(producer,current,root=root)
                for name in ('app.kt','build.gradle.kts',CORE_TEST_SOURCE,
                             '.private/core-wallet/include.gradle','.github/workflows/core-validation.yml'):
                    path=root/name;old=path.read_text();path.write_text(old+' changed')
                    changed=commit()
                    # Fresh execution inputs cannot relabel an APK with stale compiled inputs.
                    fresh=receipt_for(changed,producer)
                    with self.subTest(compiled=name),self.assertRaises(ValueError):
                        validate_core_sources(fresh,changed,changed,root=root)
                    path.write_text(old);commit()
            finally:
                for path in (root/'.git').rglob('*'):
                    if path.is_file():path.chmod(path.stat().st_mode|stat.S_IWRITE)

    def test_unknown_profile_and_unversioned_core_proof_are_rejected(self):
        with self.assertRaises(ValueError):input_fingerprint(profile='core-runtmie')
        raw=b'accepted';proof={'core_acceptance':dict(status='pending',input_fingerprint='inputs')}
        with self.assertRaises(ValueError):validate_binding(proof,'core_acceptance','inputs',raw,None,allow_late=True)

    def test_quotes_need_real_repeated_tor_evidence(self):
        receipt=dict(schema=1,input_fingerprint='inputs',evidence_class='real-app-production-quotes',
                     passed=True,tor_enabled=True,funds_transferred=False,personal_wallet_access=False,
                     quote_requests=3,quote_reviews=3,dismissals=3,executed_apk_sha256='a'*64,build_source_sha='b'*40,
                     recipient_network_mismatch_blocked=True,corrected_without_leaving_form=True,independent_comparisons=1)
        self.assertTrue(validate_quotes(receipt,'inputs'))
        for key,value in (('dismissals',2),('tor_enabled',False),('input_fingerprint','old'),('funds_transferred',True),
                          ('recipient_network_mismatch_blocked',False),('corrected_without_leaving_form',False),
                          ('independent_comparisons',0)):
            bad=dict(receipt);bad[key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):validate_quotes(bad,'inputs')


if __name__ == '__main__': unittest.main()
