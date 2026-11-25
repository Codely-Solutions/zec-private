"""Offline preparation, producer binding and guarded evidence rejection checks."""
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile
import time
import types
import admit_release
import unittest
from unittest.mock import patch
import assemble_release as assembly
import prepare_release as preparation
from release_metadata import Metadata, verify_direct_assembly
from release_inputs import selected_baseline

SHA = 'a' * 40
DIGEST = 'b' * 64
BASELINE = dict(schema=1, release='baseline', source_sha='c'*40, version_code=1,
    package='com.personal.zecprivate.testnet.foss.debug', apk_sha256='c' * 64,
    artifact=dict(release_asset_id=1, size_in_bytes=2, digest='sha256:'+DIGEST),
    instrumentation=dict(apk_sha256=DIGEST, artifact=dict(release_asset_id=1, size_in_bytes=2,
        digest='sha256:'+DIGEST, source_sha='c'*40, source_release='baseline')))
ARTIFACT = dict(id=2, digest='sha256:'+DIGEST, size_in_bytes=3)
PRODUCTION_PRODUCER = dict(producer_job_id=17, run_attempt=1, run_id=1)


class FakeMetadata:
    def producer(self, *args):
        return {}, dict(ARTIFACT), dict(PRODUCTION_PRODUCER)
    def verify_asset(self, asset):
        if asset.get('digest') != 'sha256:'+DIGEST:
            raise ValueError('Bad fixture digest')


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix='release-preparation-test-', dir=preparation.SOURCE.parent/'work'))
        self.addCleanup(shutil.rmtree, self.root)
        bridge_inputs = patch('acceptance_policy.direct_bridge_inputs', return_value=DIGEST)
        bridge_inputs.start()
        self.addCleanup(bridge_inputs.stop)

    def fake_git(self, source, *args):
        if args[0] == 'rev-parse':
            return SHA if args[1:] == ('HEAD',) else '\n'.join([DIGEST] * len(args[1:]))
        if args[0] in ('diff','ls-files'): return ''
        if args[-1].endswith('maintenance-assets.json'):
            return json.dumps(dict(ui_bridge=BASELINE['artifact']))
        if args[-1].endswith('upgrade-baseline.json'): return json.dumps(BASELINE)
        if args[-1].endswith('gradle.properties'): return 'ZCASH_VERSION_CODE=2'
        raise AssertionError(args)

    def prepare(self, *, fixture=None, **kwargs):
        kwargs.setdefault('bridge_run', 3)
        if fixture is None:
            fixture = dict(BASELINE, candidate_apk_sha256=BASELINE['apk_sha256'],
                           instrumentation_apk_sha256=BASELINE['instrumentation']['apk_sha256'])
        with patch.object(preparation, 'git', self.fake_git), patch('release_inputs.git', self.fake_git), \
             patch.object(preparation, 'input_fingerprint', return_value=DIGEST), \
             patch.object(preparation, 'direct_bridge_inputs', return_value=DIGEST), \
             patch('hosted_release.require_current_hosted_upgrade', return_value=fixture), \
             patch('acceptance_status.status',return_value={}):
            return preparation.prepare(preparation.SOURCE, SHA, 1, 2, 'release-test-owner', self.root/'prepared',
                                       metadata=FakeMetadata(), **kwargs)

    def test_prepare_jobs_and_sequential_plan(self):
        result = self.prepare()
        self.assertEqual(result['execution_plan']['mode'], 'sequential')
        for name in ('core','quotes'):
            manifest = json.loads((self.root/'prepared'/(name+'-manifest.json')).read_text())
            job = json.loads((self.root/'prepared'/(name+'-job.json')).read_text())
            self.assertEqual(job['owner'], 'release-test-owner')
            self.assertEqual(Path(job['argv'][2]).name, 'run_tor_emulator.py')
            assembly.verify_runtime_bindings(manifest)
        with self.assertRaisesRegex(ValueError, 'Fresh task-output'):
            self.prepare()

    def test_wrong_source_baseline_and_failed_write_leave_no_output(self):
        with patch.object(preparation, 'git', return_value='wrong'):
            with self.assertRaisesRegex(ValueError, 'Clean exact'):
                preparation.prepare(preparation.SOURCE,SHA,1,2,'release-test-owner',self.root/'prepared')
        baseline = self.root/'wrong-baseline.json'
        baseline.write_text('{}')
        with self.assertRaisesRegex(ValueError, 'Selected baseline differs'):
            self.prepare(baseline_path=baseline)
        original = Path.write_text
        def fail(path, *args, **kwargs):
            if path.name == 'quotes-job.json': raise OSError('injected write failure')
            return original(path,*args,**kwargs)
        with patch.object(Path,'write_text',fail):
            with self.assertRaisesRegex(OSError, 'injected'):
                self.prepare()
        self.assertFalse((self.root/'prepared').exists())

    def test_funded_preparation_refuses_a_same_tag_alternate_pair_before_writing_a_job(self):
        fixture = dict(BASELINE, candidate_apk_sha256='0' * 64, instrumentation_apk_sha256=DIGEST)
        with self.assertRaisesRegex(ValueError, 'exact paired fixture'):
            self.prepare(fixture=fixture)
        self.assertFalse((self.root/'prepared').exists())
        self.prepare()
        manifest = json.loads((self.root/'prepared/core-manifest.json').read_text())
        self.assertEqual(manifest['upgradeBaseline']['apk_sha256'], BASELINE['apk_sha256'])
        self.assertEqual(manifest['upgradeBaselineInstrumentation']['apk_sha256'], DIGEST)

    def test_baseline_pairing_rejected(self):
        malformed=json.loads(json.dumps(BASELINE))
        malformed['instrumentation']['artifact']['source_sha']='d'*40
        with patch('release_inputs.git',return_value=json.dumps(malformed)):
            with self.assertRaisesRegex(ValueError,'Paired baseline instrumentation'):
                selected_baseline(preparation.SOURCE,SHA)

    def batch(self, retained=True):
        batch=self.root/'batch'
        (batch/'evidence').mkdir(parents=True)
        result=dict(passed=True,workers=[dict(name='core',status='passed',exit_code=0)])
        raw=(json.dumps(result)+'\n').encode()
        (batch/'evidence/batch-result.json').write_bytes(raw)
        receipt=dict(accepted=True,cleanupComplete=True,ownedSurvivors=[],utc=time.time(),sources={},
                     guestCleanup=dict(retainedEvidence={'batch-result.json':dict(bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest())} if retained else {}))
        (batch/'receipt.json').write_text(json.dumps(receipt))
        (batch/'verified.json').write_text('{"verified":true}')
        return batch

    def test_closed_batch_and_missing_or_tampered_evidence(self):
        batch=self.batch()
        self.assertEqual(assembly.profile(batch,'core')['worker'],'core')
        with self.assertRaisesRegex(ValueError,'selected independent worker'):
            assembly.profile(batch,'quotes')
        (batch/'evidence/batch-result.json').write_text('{}')
        with self.assertRaisesRegex(ValueError,'Retained evidence changed'):
            assembly.profile(batch,'core')

    def test_missing_evidence_and_changed_runtime_rejected(self):
        batch=self.batch(False)
        with self.assertRaisesRegex(ValueError,'Original retained evidence'):
            assembly.profile(batch,'core')
        helper=self.root/'helper.py'; helper.write_text('first')
        manifest=dict(acceptanceRuntimeBindings={str(helper):hashlib.sha256(helper.read_bytes()).hexdigest()})
        assembly.verify_runtime_bindings(manifest)
        with self.assertRaisesRegex(ValueError,'Guard did not bind'):
            assembly.verify_runtime_bindings(manifest,{})
        helper.write_text('changed')
        with self.assertRaisesRegex(ValueError,'helper changed'):
            assembly.verify_runtime_bindings(manifest)


    def test_selected_quotes_does_not_fetch_core_producer(self):
        metadata=FakeMetadata()
        with patch.object(metadata,'producer',wraps=metadata.producer) as calls, \
             patch.object(preparation,'git',self.fake_git), patch('release_inputs.git',self.fake_git), \
             patch.object(preparation,'input_fingerprint',return_value=DIGEST), \
             patch.object(preparation,'direct_bridge_inputs',return_value=DIGEST), \
             patch('acceptance_status.status',return_value={}):
            result=preparation.prepare(preparation.SOURCE,SHA,1,None,'release-test-owner',self.root/'prepared',
                metadata=metadata,profiles=['quotes'],bridge_run=3)
        self.assertEqual(len(calls.call_args_list),3)
        self.assertEqual(result['profiles'],['production-quotes'])
        self.assertFalse((self.root/'prepared/core-job.json').exists())

    def quote_receipt(self, source=SHA):
        return dict(schema=1,evidence_class='real-app-production-quotes',passed=True,build_source_sha=source,
            input_fingerprint=DIGEST,executed_apk_sha256=DIGEST,quote_requests=3,quote_reviews=3,dismissals=3,
            tor_enabled=True,funds_transferred=False,personal_wallet_access=False,
            recipient_network_mismatch_blocked=True,corrected_without_leaving_form=True,independent_comparisons=1,
            ui_bridge=dict(artifact=dict(ARTIFACT, source_sha=source),
                producer=dict(workflow='ui-bridge.yml', head_branch='main', event='workflow_dispatch', head_sha=source,
                              producer_job_name='probe', producer_job_conclusion='success',
                              artifact_name='android-ui-bridge', run_id=3, run_attempt=1, producer_job_id=20),
                apk_preparation=dict(executionEntriesUnchanged=True, originalSha256=DIGEST, testedSha256=DIGEST),
                input_fingerprint=DIGEST))

    def test_local_quote_reuse_rejects_legacy_or_mismatched_bridge_evidence(self):
        from copy import deepcopy
        from acceptance_policy import validate_direct_quotes

        receipt = self.quote_receipt()
        self.assertTrue(validate_direct_quotes(receipt, DIGEST, SHA))
        for component, field, value in (
            ('artifact', 'source_sha', 'a'*39+'f'), ('producer', 'head_branch', 'review'),
            ('apk_preparation', 'executionEntriesUnchanged', False),
        ):
            mismatched = deepcopy(receipt)
            mismatched['ui_bridge'][component][field] = value
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'verified bridge'):
                validate_direct_quotes(mismatched, DIGEST, SHA)
        with self.assertRaisesRegex(ValueError, 'verified bridge'):
            validate_direct_quotes({key: value for key, value in receipt.items() if key != 'ui_bridge'}, DIGEST, SHA)
        with patch('acceptance_policy.direct_bridge_inputs', return_value='a'*64):
            with self.assertRaisesRegex(ValueError, 'inputs differ'):
                validate_direct_quotes(receipt, DIGEST, SHA)

    def test_quote_reuse_preserves_bytes_and_rejects_stale_build_or_fingerprint(self):
        path=self.root/'quotes.json'
        raw=json.dumps(self.quote_receipt(),indent=3).encode()+b'\n'
        path.write_bytes(raw)
        with patch.object(assembly,'input_fingerprint',return_value=DIGEST):
            receipt,preserved=assembly.reusable(path,'production-quotes',SHA)
            self.assertEqual(raw,preserved)
            self.assertEqual(receipt['build_source_sha'],SHA)
        with patch.object(assembly,'input_fingerprint',side_effect=[DIGEST,'different']):
            with self.assertRaisesRegex(ValueError,'Executed quote build inputs differ'):
                assembly.reusable(path,'production-quotes',SHA)
        with patch.object(assembly,'input_fingerprint',return_value='different'):
            with self.assertRaisesRegex(ValueError,'Missing real unfunded'):
                assembly.reusable(path,'production-quotes',SHA)

    def test_successful_prepared_core_manifest_reaches_assembly_with_the_bound_pair(self):
        from copy import deepcopy

        self.prepare()
        prepared = json.loads((self.root / 'prepared/core-manifest.json').read_bytes())
        quotes_path = self.root / 'quotes.json'
        quotes_path.write_text(json.dumps(self.quote_receipt()))
        apks = dict(candidate_apk_sha256='f' * 64, instrumentation_apk_sha256='e' * 64,
                    baseline_apk_sha256=BASELINE['apk_sha256'], baseline_instrumentation_apk_sha256=DIGEST)
        staged_hash = {role: hashlib.sha256(role.encode()).hexdigest() for role in apks}
        core = dict(build_source_sha=SHA, candidate_version_code=2, **apks,
                    instrumentation_pairing={
                        'baseline': {'executed_sha256': staged_hash['baseline_instrumentation_apk_sha256']},
                        'candidate': {'executed_sha256': staged_hash['instrumentation_apk_sha256']}},
                    stages={'interruption': dict(update_metadata_valid_before=True, update_metadata_valid_after=True,
                                                 update_blocked_during_outage=True)})
        probe = dict(passed=True, probeArtifact=dict(ARTIFACT, **apks), apkHashes=staged_hash,
                     apkPreparation={role: dict(executionEntriesUnchanged=True, abi='x86_64', originalSha256=original,
                                                testedSha256=staged_hash[role]) for role, original in apks.items()})
        profile = dict(receipt={'job': 'owned', 'sources': prepared['acceptanceRuntimeBindings']},
                       receipt_sha256='c' * 64, worker='core')
        out = self.root / 'assembled-fresh'
        argv = ['assemble_release.py', '--source-sha', SHA, '--candidate-run', '1', '--core-run', '2',
                '--core-batch', str(self.root / 'closed'), '--reuse-quote', str(quotes_path),
                '--task-root', str(self.root), '--out', str(out)]

        def assemble(manifest, result=core, downloaded=probe):
            evidence = {'manifest.json': manifest, 'core-testnet-result.json': deepcopy(result),
                        'tor-probe-result.json': downloaded}
            with patch.object(sys, 'argv', argv), patch.object(assembly, 'verify_revision', return_value=SHA), \
                    patch.object(assembly, 'git', return_value='ZCASH_VERSION_CODE=2'), \
                    patch.object(assembly, 'Metadata', return_value=FakeMetadata()), \
                    patch.object(assembly, 'selected_baseline', return_value=BASELINE), \
                    patch.object(assembly, 'input_fingerprint', return_value=DIGEST), \
                    patch.object(assembly, 'validate_core_sources') as validate_wallet, \
                    patch.object(assembly, 'profile', return_value=dict(profile, evidence=evidence.__getitem__)):
                assembly.main()
                validate_wallet.assert_called_once()

        changed = deepcopy(prepared)
        changed['upgradeBaseline']['apk_sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'Selected upgrade baseline changed'):
            assemble(changed)
        self.assertFalse(out.exists())
        changed_probe = dict(probe, probeArtifact=dict(probe['probeArtifact'], baseline_apk_sha256='0' * 64))
        with self.assertRaisesRegex(ValueError, 'Executed baseline app differs'):
            assemble(prepared, dict(core, baseline_apk_sha256='0' * 64), changed_probe)
        self.assertFalse(out.exists())
        assemble(prepared)
        self.assertEqual(BASELINE['apk_sha256'],
                         json.loads((out / '.private/core-acceptance.json').read_bytes())['baseline_apk_sha256'])

    def test_assemble_reused_receipts_validates_and_preserves_exact_bytes(self):
        core=json.loads((preparation.SOURCE/'.private/core-acceptance.json').read_text())
        source=core['build_source_sha']
        version=core['candidate_version_code']
        quotes=self.quote_receipt(source)
        core_path=self.root/'core.json'; quote_path=self.root/'quotes.json'
        core_raw=json.dumps(core,indent=3).encode()+b'\n'; quote_raw=json.dumps(quotes,indent=3).encode()+b'\n'
        core_path.write_bytes(core_raw); quote_path.write_bytes(quote_raw)
        def fingerprint(revision,root=None,profile='core-testnet'):
            return {'core-testnet':core['input_fingerprint'], 'core-runtime':core['runtime_input_fingerprint'],
                    'core-build':core['build_input_fingerprint'], 'production-quotes':DIGEST}[profile]
        policy_root=self.root/'policy-fixture'
        (policy_root/'.private').mkdir(parents=True)
        (policy_root/'.private/upgrade-baseline.json').write_text(json.dumps(dict(source_sha=core['baseline_source_sha'],
            version_code=core['baseline_version_code'],instrumentation=dict(apk_sha256=core['baseline_instrumentation_apk_sha256']))))
        from acceptance_policy import validate_core as original_validate
        def isolated_validate(receipt, expected, root=None, **kwargs):
            return original_validate(receipt,expected,root=policy_root,**kwargs)
        out=self.root/'assembled'
        argv=['assemble_release.py','--source-sha',source,'--candidate-run','1','--reuse-core',str(core_path),
              '--reuse-quote',str(quote_path),'--task-root',str(self.root),'--out',str(out)]
        with patch.object(sys,'argv',argv), patch.object(assembly,'verify_revision',return_value=source), \
             patch.object(assembly,'git',return_value=f'ZCASH_VERSION_CODE={version}'), \
             patch.object(assembly,'Metadata',return_value=FakeMetadata()), \
             patch.object(assembly,'selected_baseline', return_value={k: v for k, v in BASELINE.items() if k != 'apk_sha256'}), \
             patch.object(assembly,'input_fingerprint',side_effect=fingerprint), \
             patch('acceptance_policy.input_fingerprint',side_effect=fingerprint), \
             patch('acceptance_policy.validate_core',side_effect=isolated_validate):
            assembly.main()
        self.assertEqual(core_raw,(out/'.private/core-acceptance.json').read_bytes())
        self.assertEqual(quote_raw,(out/'.private/quote-acceptance.json').read_bytes())
        summary=json.loads((out/'.private/core-validation-summary.json').read_text())
        self.assertEqual(summary['core_build_source_sha'],core['build_source_sha'])
        self.assertEqual(len(summary['reused_profiles']),2)
        receipts = {'core-acceptance.json': core_raw, 'quote-acceptance.json': quote_raw}
        binding = dict(production_artifact=ARTIFACT, production_job_id=17)
        self.assertEqual(verify_direct_assembly(out, source, 1, ARTIFACT, PRODUCTION_PRODUCER), binding)
        self.assertEqual(verify_direct_assembly(
            out, source, 1, ARTIFACT, PRODUCTION_PRODUCER, receipts=receipts), binding)
        for run, archive, producer in (
            (2, dict(ARTIFACT, id=3), dict(PRODUCTION_PRODUCER, run_id=2, producer_job_id=18)),
            (1, dict(ARTIFACT, digest='sha256:' + 'e' * 64), PRODUCTION_PRODUCER),
            (1, ARTIFACT, dict(PRODUCTION_PRODUCER, run_attempt=2)),
        ):
            with self.subTest(run=run, archive=archive, producer=producer):
                with self.assertRaisesRegex(ValueError, 'artifact or producer differs'):
                    verify_direct_assembly(out, source, run, archive, producer, receipts=receipts)
        with self.assertRaisesRegex(ValueError, 'receipts differ'):
            verify_direct_assembly(out, source, 1, ARTIFACT, PRODUCTION_PRODUCER,
                                   receipts=dict(receipts, **{'core-acceptance.json': b'different'}))
        (out/'.private/quote-acceptance.json').write_bytes(b'different')
        with self.assertRaisesRegex(ValueError, 'receipts differ'):
            verify_direct_assembly(out, source, 1, ARTIFACT, PRODUCTION_PRODUCER)


    def test_repeat_local_funded_admission_stops_on_the_retained_version_ledger(self):
        from release_inputs import available_local_funding_ledger

        self.prepare()
        fixture_source = self.root / 'source'
        ledger = available_local_funding_ledger(fixture_source, 2)
        ledger.parent.mkdir(parents=True)
        ledger.write_text('{"status": "request-starting", "claim_requests": 1}')
        prepared = self.root / 'prepared'
        argv = ['admit_release.py', '--prepared', str(prepared), '--owner', 'release-test-owner', '--profile', 'core']
        with patch.object(sys, 'argv', argv), \
                patch.object(admit_release, 'available_local_funding_ledger',
                             side_effect=lambda source, version: available_local_funding_ledger(fixture_source, version)), \
                patch.dict(sys.modules, {'admit_emulator_batch': None}):
            with self.assertRaisesRegex(ValueError, 'reconcile the original ledger'):
                admit_release.main()
        self.assertEqual('request-starting', json.loads(ledger.read_bytes())['status'])
        self.assertFalse(available_local_funding_ledger(fixture_source, 3).exists())

    def test_admission_owner_and_parallel_refusal_before_launch(self):
        self.prepare()
        prepared=self.root/'prepared'
        argv=['admit_release.py','--prepared',str(prepared),'--owner','other-owner','--profile','core']
        fake=types.SimpleNamespace(PARENT=None,EVIDENCE=set(),guard=types.SimpleNamespace(run=lambda *a,**k:None),main=lambda:0)
        with patch.object(sys,'argv',argv),patch.dict(sys.modules,{'admit_emulator_batch':fake}):
            with self.assertRaisesRegex(ValueError,'owner differs'):
                admit_release.main()
        argv=['admit_release.py','--prepared',str(prepared),'--owner','release-test-owner','--profile','core','--profile','quotes']
        with patch.object(sys,'argv',argv):
            with self.assertRaisesRegex(ValueError,'sequential admission'):
                admit_release.main()

    def test_delayed_funded_admission_refuses_a_superseded_pair_without_launching(self):
        self.prepare()
        prepared = self.root / 'prepared'
        argv = ['admit_release.py', '--prepared', str(prepared), '--owner', 'release-test-owner', '--profile', 'core']
        newer = dict(BASELINE, release='newer', version_code=2, candidate_apk_sha256='e' * 64,
                     instrumentation_apk_sha256=DIGEST)
        with patch.object(sys, 'argv', argv), patch('release_inputs.git', self.fake_git), \
                patch('hosted_release.require_current_hosted_upgrade', return_value=newer), \
                patch.dict(sys.modules, {'admit_emulator_batch': None}):
            with self.assertRaisesRegex(ValueError, 'exact paired fixture for the latest wallet release'):
                admit_release.main()

    def test_verified_image_adapter_binds_image_and_owner(self):
        self.prepare()
        image=self.root/'image';image.mkdir()
        (image/'.codex-system-image.json').write_text('{}')
        recorded=[]
        fake=types.SimpleNamespace(PARENT=None,EVIDENCE=set(),guard=types.SimpleNamespace(
            run=lambda spec,*a,**k:recorded.append(spec.copy())))
        def launch():
            fake.guard.run(dict(owner='old',argv=[sys.executable,'-B',str(preparation.SOURCE/'.private/emulator_batch.py'),'plan'],sources=[]))
            return 0
        fake.main=launch
        argv=['admit_release.py','--prepared',str(self.root/'prepared'),'--owner','release-test-owner','--profile','core','--image',str(image)]
        fixture = dict(BASELINE, candidate_apk_sha256=BASELINE['apk_sha256'],
                       instrumentation_apk_sha256=BASELINE['instrumentation']['apk_sha256'])
        with patch.object(sys,'argv',argv),patch.dict(sys.modules,{'admit_emulator_batch':fake}), \
             patch('emulator_session.verify_image',return_value=image), patch('release_inputs.git', self.fake_git), \
             patch('hosted_release.require_current_hosted_upgrade', return_value=fixture) as current:
            self.assertEqual(admit_release.main(),0)
        current.assert_called_once()
        self.assertEqual(recorded[0]['owner'],'release-test-owner')
        self.assertEqual(Path(recorded[0]['argv'][2]).name,'release_cached_batch.py')
        self.assertEqual(recorded[0]['argv'][-1],str(image))
        self.assertIn(str(image/'.codex-system-image.json'),recorded[0]['sources'])


class MetadataTests(unittest.TestCase):
    def fixtures(self):
        run=dict(id=1,path='.github/workflows/build-release.yml',head_branch='main',event='workflow_dispatch',
                 head_sha=SHA,status='completed',conclusion='success',run_attempt=1,
                 run_started_at='2026-11-23T08:00:00Z')
        job=dict(id=3,name='unsigned',status='completed',conclusion='success',run_id=1,head_sha=SHA,
                 run_attempt=1, runner_id=17, steps=[{'name': 'upload'}],
                 started_at='2026-11-23T08:05:00Z', completed_at='2026-11-23T08:10:00Z')
        artifact=dict(ARTIFACT,name='validated-unsigned-apk',expired=False,workflow_run=dict(id=1,head_sha=SHA),
                      created_at='2026-11-23T08:08:00Z')
        return run,job,artifact

    def producer(self, run, jobs, artifact, original=None):
        metadata=Metadata()
        responses = {
            '/actions/runs/1': run,
            '/actions/runs/1/artifacts?per_page=100': [{'artifacts': [artifact]}],
            '/actions/runs/1/jobs?filter=all&per_page=100': [{'jobs': jobs}],
            '/actions/runs/1/attempts/1': original,
        }
        with patch.object(metadata, 'api', side_effect=lambda path, paginated=False: responses[path]):
            return metadata.producer(1,SHA,'build-release.yml','unsigned','validated-unsigned-apk')

    def test_exact_producer_and_rejections(self):
        run,job,artifact=self.fixtures()
        self.assertEqual(self.producer(run,[job],artifact)[1],ARTIFACT)
        for target,key,value in ((run,'head_sha','c'*40),(run,'conclusion','failure'),(job,'run_id',2),
                                 (artifact,'digest','sha256:invalid'),(artifact,'expired',True)):
            with self.subTest(key=key):
                original=target[key]; target[key]=value
                with self.assertRaises(ValueError): self.producer(run,[job],artifact)
                target[key]=original

    def test_retained_artifact_proof_stays_bound_to_original_job_after_successful_rerun(self):
        first, job, artifact = self.fixtures()
        later = dict(first, run_attempt=2, run_started_at='2026-11-23T09:00:00Z')
        copied = dict(job, id=4, run_attempt=2)
        _, original_artifact, initial_proof = self.producer(first, [job], artifact)
        completed, retained_artifact, retained_proof = self.producer(later, [copied, job], artifact, first)
        self.assertEqual(completed['run_attempt'], 2)
        self.assertEqual(retained_artifact, original_artifact)
        self.assertEqual(retained_proof, initial_proof)
        self.assertEqual((retained_proof['run_attempt'], retained_proof['producer_job_id']), (1, 3))
        with self.assertRaisesRegex(ValueError, 'Copied unsigned producer does not match'):
            self.producer(later, [dict(copied, runner_id=18), job], artifact, first)
        with self.assertRaisesRegex(ValueError, 'Expected one successful unsigned producer'):
            self.producer(later, [copied], artifact, first)
        with self.assertRaisesRegex(ValueError, 'Artifact producer attempt identity mismatch'):
            self.producer(later, [copied, job], artifact, dict(first, head_sha='c'*40))
        with self.assertRaisesRegex(ValueError, 'Producer run must pass'):
            self.producer(dict(later, conclusion='failure'), [copied, job], artifact, first)

    def test_live_baseline_binding_rejected(self):
        metadata=Metadata()
        with patch.object(metadata,'api',return_value=dict(id=1,size=9,digest='sha256:'+DIGEST,state='uploaded')):
            with self.assertRaisesRegex(ValueError,'Live maintenance asset differs'):
                metadata.verify_asset(BASELINE['artifact'])


if __name__ == '__main__':
    unittest.main()
