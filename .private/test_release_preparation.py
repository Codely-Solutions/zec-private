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
from release_metadata import Metadata
from release_inputs import selected_baseline

SHA = 'a' * 40
DIGEST = 'b' * 64
BASELINE = dict(schema=1, release='baseline', source_sha='c'*40, version_code=1,
    package='com.personal.zecprivate.testnet.foss.debug',
    artifact=dict(release_asset_id=1, size_in_bytes=2, digest='sha256:'+DIGEST),
    instrumentation=dict(apk_sha256=DIGEST, artifact=dict(release_asset_id=1, size_in_bytes=2,
        digest='sha256:'+DIGEST, source_sha='c'*40, source_release='baseline')))
ARTIFACT = dict(id=2, digest='sha256:'+DIGEST, size_in_bytes=3)


class FakeMetadata:
    def producer(self, *args):
        return {}, dict(ARTIFACT), {}
    def verify_asset(self, asset):
        if asset.get('digest') != 'sha256:'+DIGEST:
            raise ValueError('Bad fixture digest')


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix='release-preparation-test-', dir=preparation.SOURCE.parent/'work'))
        self.addCleanup(shutil.rmtree, self.root)

    def fake_git(self, source, *args):
        if args[0] == 'rev-parse':
            return SHA if args[1:] == ('HEAD',) else '\n'.join([DIGEST]*6)
        if args[0] in ('diff','ls-files'): return ''
        if args[-1].endswith('maintenance-assets.json'):
            return json.dumps(dict(ui_bridge=BASELINE['artifact']))
        if args[-1].endswith('upgrade-baseline.json'): return json.dumps(BASELINE)
        if args[-1].endswith('gradle.properties'): return 'ZCASH_VERSION_CODE=2'
        raise AssertionError(args)

    def prepare(self, **kwargs):
        with patch.object(preparation, 'git', self.fake_git), patch('release_inputs.git', self.fake_git), \
             patch.object(preparation, 'input_fingerprint', return_value=DIGEST), \
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
             patch('acceptance_status.status',return_value={}):
            result=preparation.prepare(preparation.SOURCE,SHA,1,None,'release-test-owner',self.root/'prepared',
                metadata=metadata,profiles=['quotes'])
        self.assertEqual(len(calls.call_args_list),2)
        self.assertEqual(result['profiles'],['production-quotes'])
        self.assertFalse((self.root/'prepared/core-job.json').exists())

    def quote_receipt(self):
        return dict(schema=1,evidence_class='real-app-production-quotes',passed=True,build_source_sha=SHA,
            input_fingerprint=DIGEST,executed_apk_sha256=DIGEST,quote_requests=3,quote_reviews=3,dismissals=3,
            tor_enabled=True,funds_transferred=False,personal_wallet_access=False,
            recipient_network_mismatch_blocked=True,corrected_without_leaving_form=True,independent_comparisons=1)

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

    def test_assemble_reused_receipts_validates_and_preserves_exact_bytes(self):
        core=json.loads((preparation.SOURCE/'.private/core-acceptance.json').read_text())
        quotes=self.quote_receipt()
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
        original_validate=assembly.validate_core
        def isolated_validate(receipt, expected, root=None):
            return original_validate(receipt,expected,root=policy_root)
        out=self.root/'assembled'
        argv=['assemble_release.py','--source-sha',SHA,'--candidate-run','1','--reuse-core',str(core_path),
              '--reuse-quote',str(quote_path),'--task-root',str(self.root),'--out',str(out)]
        with patch.object(sys,'argv',argv), patch.object(assembly,'verify_revision',return_value=SHA), \
             patch.object(assembly,'git',return_value='ZCASH_VERSION_CODE=2'), \
             patch.object(assembly,'Metadata',return_value=FakeMetadata()), \
             patch.object(assembly,'selected_baseline',return_value=BASELINE), \
             patch.object(assembly,'input_fingerprint',side_effect=fingerprint), \
             patch('acceptance_policy.input_fingerprint',side_effect=fingerprint), \
             patch('acceptance_policy.validate_core',side_effect=isolated_validate):
            assembly.main()
        self.assertEqual(core_raw,(out/'.private/core-acceptance.json').read_bytes())
        self.assertEqual(quote_raw,(out/'.private/quote-acceptance.json').read_bytes())
        summary=json.loads((out/'.private/core-validation-summary.json').read_text())
        self.assertEqual(summary['core_build_source_sha'],core['build_source_sha'])
        self.assertEqual(len(summary['reused_profiles']),2)


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
        with patch.object(sys,'argv',argv),patch.dict(sys.modules,{'admit_emulator_batch':fake}), \
             patch('emulator_session.verify_image',return_value=image):
            self.assertEqual(admit_release.main(),0)
        self.assertEqual(recorded[0]['owner'],'release-test-owner')
        self.assertEqual(Path(recorded[0]['argv'][2]).name,'release_cached_batch.py')
        self.assertEqual(recorded[0]['argv'][-1],str(image))
        self.assertIn(str(image/'.codex-system-image.json'),recorded[0]['sources'])


class MetadataTests(unittest.TestCase):
    def fixtures(self):
        run=dict(id=1,path='.github/workflows/build-release.yml',head_branch='main',event='workflow_dispatch',
                 head_sha=SHA,status='completed',conclusion='success',run_attempt=1)
        job=dict(id=3,name='unsigned',status='completed',conclusion='success',run_id=1,head_sha=SHA)
        artifact=dict(ARTIFACT,name='validated-unsigned-apk',expired=False,workflow_run=dict(id=1,head_sha=SHA))
        return run,job,artifact

    def producer(self, run,job,artifact):
        metadata=Metadata()
        with patch.object(metadata,'api',return_value=run), patch.object(metadata,'pages',side_effect=[[job],[artifact]]):
            return metadata.producer(1,SHA,'build-release.yml','unsigned','validated-unsigned-apk')

    def test_exact_producer_and_rejections(self):
        run,job,artifact=self.fixtures()
        self.assertEqual(self.producer(run,job,artifact)[1],ARTIFACT)
        for target,key,value in ((run,'head_sha','c'*40),(run,'conclusion','failure'),(job,'run_id',2),
                                 (artifact,'digest','sha256:invalid'),(artifact,'expired',True)):
            with self.subTest(key=key):
                original=target[key]; target[key]=value
                with self.assertRaises(ValueError): self.producer(run,job,artifact)
                target[key]=original

    def test_live_baseline_binding_rejected(self):
        metadata=Metadata()
        with patch.object(metadata,'api',return_value=dict(id=1,size=9,digest='sha256:'+DIGEST,state='uploaded')):
            with self.assertRaisesRegex(ValueError,'Live maintenance asset differs'):
                metadata.verify_asset(BASELINE['artifact'])


if __name__ == '__main__':
    unittest.main()
