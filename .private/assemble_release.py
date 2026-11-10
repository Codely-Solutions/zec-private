"""Assemble exact guarded acceptance without signing or publication."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import shutil

HERE=Path(__file__).resolve().parent
SOURCE=HERE.parent
sys.path.insert(0,str(SOURCE/'.private'))
from acceptance_policy import ATTESTATION_PATHS, input_fingerprint, validate_core_sources, validate_core, validate_quotes
from release_metadata import Metadata, require
from release_inputs import selected_baseline


def git(*args):
    return subprocess.check_output(['git',*args],cwd=SOURCE,text=True,timeout=30).strip()

def verify_revision(source_sha, allow_late=False):
    require(re.fullmatch(r'[0-9a-f]{40}',source_sha),'Exact stable source SHA required')
    head=git('rev-parse','HEAD')
    if head!=source_sha:
        require(allow_late,'Prepare from exact final source HEAD')
        subprocess.run(['git','merge-base','--is-ancestor',source_sha,head],cwd=SOURCE,
                       check=True,timeout=30,stdout=subprocess.DEVNULL)
        require(set(git('diff','--no-renames','--name-only',source_sha,head).splitlines())<=ATTESTATION_PATHS,
                'Only the five late attestation paths may follow final source')
    dirty=set(git('diff','--name-only','HEAD').splitlines())
    require(dirty<=(ATTESTATION_PATHS if allow_late else set()),'Uncommitted candidate inputs changed')
    return head

def verify_runtime_bindings(manifest, guarded_sources=None):
    bindings=manifest.get('acceptanceRuntimeBindings')
    require(isinstance(bindings,dict) and bindings,'Prepared runtime bindings required')
    for name,digest in bindings.items():
        path=Path(name)
        require(path.is_absolute() and re.fullmatch(r'[0-9a-f]{64}',digest)
            and path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest()==digest,
            'Prepared acceptance helper changed: '+name)
        if guarded_sources is not None:
            require(guarded_sources.get(name)==digest,'Guard did not bind prepared helper: '+name)



def profile(batch,worker):
    batch=batch.resolve()
    raw=(batch/'receipt.json').read_bytes()
    receipt=json.loads(raw)
    require(receipt['accepted'] and receipt['cleanupComplete'] and not receipt['ownedSurvivors'],
            'Closed accepted batch with zero survivors required')
    require(-60<=time.time()-receipt['utc']<=86400,'Acceptance receipt expired')
    require(json.loads((batch/'verified.json').read_text())['verified'],'Original guard verification required')
    for name,digest in receipt['sources'].items():
        p=Path(name)
        require(p.is_file() and hashlib.sha256(p.read_bytes()).hexdigest()==digest,
                'Executed helper/controller changed: '+name)
    retained=receipt['guestCleanup']['retainedEvidence']
    require(retained,'Original retained evidence required')
    for relative,identity in retained.items():
        p=(batch/'evidence'/relative).resolve()
        require(p.is_relative_to(batch/'evidence'),'Evidence path escaped record')
        content=p.read_bytes()
        require(len(content)==identity['bytes'] and hashlib.sha256(content).hexdigest()==identity['sha256'],
                'Retained evidence changed: '+relative)
    require('batch-result.json' in retained,'Retained batch result required')
    result=json.loads((batch/'evidence/batch-result.json').read_text())
    workers=result['workers'];names=[entry['name'] for entry in workers]
    require(result['passed'] is True and len(names)==len(set(names)) and 1<=len(names)<=2
        and worker in names and all(e['status']=='passed' and e['exit_code']==0 for e in workers),
        'Entire admitted batch and selected independent worker must pass')
    def evidence(name):
        relative='workers/'+worker+'/'+name
        require(relative in retained,'Missing accepted evidence: '+relative)
        return json.loads((batch/'evidence'/relative).read_text())
    return dict(receipt=receipt,receipt_sha256=hashlib.sha256(raw).hexdigest(),retained=retained,
                evidence=evidence,worker=worker)


def reusable(path, profile_name, head):
    raw = Path(path).read_bytes()
    receipt = json.loads(raw)
    expected = input_fingerprint(head, root=SOURCE, profile=profile_name)
    if profile_name == 'core-testnet':
        validate_core_sources(receipt, receipt['build_source_sha'], head, root=SOURCE)
    else:
        validate_quotes(receipt, expected)
        require(input_fingerprint(receipt['build_source_sha'], root=SOURCE, profile=profile_name) == expected,
                'Executed quote build inputs differ')
    return receipt, raw


def main():
    global SOURCE
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',type=Path,default=SOURCE)
    parser.add_argument('--baseline',type=Path)
    parser.add_argument('--task-root',type=Path,required=True)
    parser.add_argument('--repo',default='Codely-Solutions/zec-private')
    parser.add_argument('--source-sha',required=True)
    parser.add_argument('--candidate-run',required=True,type=int)
    parser.add_argument('--ui-run',type=int)
    parser.add_argument('--core-run',type=int)
    for name in ('core','quote'):
        group=parser.add_mutually_exclusive_group(required=True)
        group.add_argument('--'+name+'-batch',type=Path)
        group.add_argument('--reuse-'+name,type=Path)
    parser.add_argument('--out',required=True,type=Path)
    args=parser.parse_args()
    SOURCE=args.source.resolve()
    require(SOURCE == HERE.parent, "Run the helper from the selected source checkout")
    head=verify_revision(args.source_sha,allow_late=True)

    require(args.task_root.resolve().is_relative_to(SOURCE.parent/'work') and args.task_root.resolve() != SOURCE.parent/'work',
            'Task root must be beneath project work')
    out=args.out.resolve()
    require(out.is_relative_to(args.task_root.resolve()) and out!=args.task_root.resolve() and not out.exists(),
            'Choose a fresh staging directory beneath this task')
    require(args.reuse_quote or args.ui_run == args.candidate_run, 'Quote APK must come from the production candidate run')
    version=int(re.search(r'^ZCASH_VERSION_CODE=(\d+)$',git('show',args.source_sha+':gradle.properties'),re.M)[1])
    metadata=Metadata(repo=args.repo)
    _,production_artifact,production_proof=metadata.producer(args.candidate_run,args.source_sha,
        'build-release.yml','unsigned','validated-unsigned-apk')
    profiles = {}
    reused_bytes = {}
    if args.core_batch:
        require(args.core_run and args.core_run > 0, 'Fresh core producer run required')
        _,core_artifact,core_proof=metadata.producer(args.core_run,args.source_sha,
            'core-validation.yml','testnet','core-testnet-candidate')
        profiles['core'] = profile(args.core_batch,'core')
    if args.quote_batch:
        _,ui_artifact,ui_proof=metadata.producer(args.ui_run,args.source_sha,
            'build-release.yml','emulator','isolated-ui-repro-apk')
        profiles['quotes'] = profile(args.quote_batch,'quotes')
    baseline = selected_baseline(SOURCE,args.source_sha,args.baseline)
    metadata.verify_asset(baseline['artifact'])
    metadata.verify_asset(baseline['instrumentation']['artifact'])
    for p in profiles.values():
        manifest=p['evidence']('manifest.json')
        verify_runtime_bindings(manifest,p['receipt']['sources'])
        require(manifest['build_source_sha']==args.source_sha and manifest['product_source_sha']==args.source_sha
            and manifest['stable_candidate_run']==args.candidate_run,'Every profile must execute the same stable source')
        require(manifest.get('controller_source_sha') == args.source_sha, 'Attesting controller source changed')
        require(manifest.get('productionUnsignedProducer') == production_proof, 'Production producer provenance changed')
        require(manifest['productionUnsignedArtifact']==production_artifact,
                'Profile final production artifact binding changed')
    if args.reuse_core:
        core, reused_bytes['core-acceptance.json'] = reusable(args.reuse_core,'core-testnet',head)
    else:
        core_manifest = profiles['core']['evidence']('manifest.json')
        core = profiles['core']['evidence']('core-testnet-result.json')
        core_probe = profiles['core']['evidence']('tor-probe-result.json')
        require(core_manifest.get('artifactProducer') == core_proof, 'Core producer provenance changed')
        require(core_manifest['workflow_run_id'] == args.core_run, 'Producer run/source binding mismatch')
        require(core_manifest['candidate_version_code'] == version, 'Expected stable candidate version execution')
        require(all(core_manifest[k] == core_artifact[k] for k in ('id','digest','size_in_bytes')),
                'Current core artifact differs from execution')
        require(core_probe['passed'] and core_probe['probeArtifact']['id'] == core_manifest['id'] and
                core_probe['probeArtifact']['digest'] == core_manifest['digest'], 'Executed artifact binding mismatch')
        require(core['build_source_sha'] == args.source_sha and
                core_manifest['build_input_fingerprint'] ==
                input_fingerprint(args.source_sha, root=SOURCE, profile='core-build') and
                core_manifest['runtime_input_fingerprint'] ==
                input_fingerprint(args.source_sha, root=SOURCE, profile='core-runtime'),
                'Fresh executed core source/compiled/runtime identity mismatch')
        require(all(core[name] == core_probe['probeArtifact'][name] for name in
                    ('candidate_apk_sha256', 'baseline_apk_sha256', 'instrumentation_apk_sha256',
                     'baseline_instrumentation_apk_sha256')),
                'Core result APK identities differ from actual downloaded execution inputs')
        preparations=core_probe['apkPreparation']
        require(len(preparations)==4 and all(p['executionEntriesUnchanged'] and p['abi']=='x86_64'
            and core_probe['apkHashes'][name]==p['testedSha256'] for name,p in preparations.items()),
            'All four core APKs must preserve their executable payloads')
        for role,key in (('baseline','baseline_instrumentation_apk_sha256'),('candidate','instrumentation_apk_sha256')):
            prepared=[p for p in preparations.values() if p['originalSha256']==core[key]]
            require(len(prepared)==1 and prepared[0]['testedSha256']==
                    core['instrumentation_pairing'][role]['executed_sha256'],
                    'Installed instrumentation differs from preserved executable payload: '+role)
        require(core_manifest['baseline_source_sha']==baseline['source_sha'] and
                core_manifest['baseline_version_code']==baseline['version_code'] and
                core_manifest['upgradeBaseline']==dict(baseline['artifact'],source_sha=baseline['source_sha']),
                'Selected upgrade baseline changed')
        require(core_manifest['upgradeBaselineInstrumentation']==dict(baseline['instrumentation']['artifact'],
            source_sha=baseline['source_sha'],apk_sha256=baseline['instrumentation']['apk_sha256']),
            'Selected baseline instrumentation archive changed')
        require(all(core.get('stages', {}).get('interruption', {}).get(key) is True for key in
                    ('update_metadata_valid_before', 'update_metadata_valid_after', 'update_blocked_during_outage')),
                'Real update metadata outage/recovery evidence required')
        validate_core_sources(core, args.source_sha, head, root=SOURCE)
        # Preserve original execution fields; add only guard/producer provenance.
        provenance = dict(guard_job=profiles['core']['receipt']['job'], artifact_id=core_manifest['id'],
                          workflow_run_id=args.core_run, apk_preparation=preparations,
                          guard_receipt_sha256=profiles['core']['receipt_sha256'])
        for key, value in provenance.items():
            require(key not in core or core[key] == value, 'Original execution identity differs: '+key)
            core[key] = value
    if args.reuse_quote:
        quotes, reused_bytes['quote-acceptance.json'] = reusable(args.reuse_quote,'production-quotes',head)
    else:
        ui_manifest = profiles['quotes']['evidence']('manifest.json')
        probe = profiles['quotes']['evidence']('tor-probe-result.json')
        ui = profiles['quotes']['evidence']('tor-ui-result.json')
        require(ui_manifest.get('artifactProducer') == ui_proof, 'UI producer provenance changed')
        require(ui_manifest['workflow_run_id'] == args.ui_run, 'Producer run/source binding mismatch')
        require(ui_manifest['expectedVersionCode'] == version, 'Expected stable candidate version execution')
        require(all(ui_manifest[k] == ui_artifact[k] for k in ('id','digest','size_in_bytes')),
                'Current UI artifact differs from execution')
        require(probe['passed'] and probe['probeArtifact']['id'] == ui_manifest['id'] and
                probe['probeArtifact']['digest'] == ui_manifest['digest'], 'Executed artifact binding mismatch')
        app_names = [name for name in probe['apkPreparation'] if name != 'ui-bridge.apk']
        require(len(app_names) == 1, 'Expected a single quote application APK')
        preparation = probe['apkPreparation'][app_names[0]]
        require(preparation['executionEntriesUnchanged'] and preparation['abi'] == 'x86_64'
                and probe['apkHashes'][app_names[0]] == preparation['testedSha256'], 'Quote execution bytes changed')
        quotes = dict(schema=1, evidence_class='real-app-production-quotes', passed=ui['passed'],
                      build_source_sha=ui_manifest['build_source_sha'],
                      input_fingerprint=ui_manifest['uiBuildInputs']['input_fingerprint'],
                      executed_apk_sha256=preparation['testedSha256'], original_apk_sha256=preparation['originalSha256'],
                      quote_requests=ui['quoteRequests'], quote_reviews=ui['quoteReviewsVerified'], dismissals=ui['dismissals'],
                      tor_enabled=ui['torOptInVerified'], funds_transferred=ui['fundsTransferred'],
                      personal_wallet_access=ui['personalWalletAccess'], artifact_id=ui_manifest['id'],
                      workflow_run_id=args.ui_run, archive_sha256=ui_manifest['digest'].removeprefix('sha256:'),
                      apk_preparation=preparation, guard_job=profiles['quotes']['receipt']['job'],
                      guard_receipt_sha256=profiles['quotes']['receipt_sha256'],
                      provider_timeouts=ui.get('phase1Scenario', {}).get('providerTimeouts', []),
                      recipient_network_mismatch_blocked=ui['phase1Scenario']['recipientNetworkMismatchBlocked'],
                      corrected_without_leaving_form=ui['phase1Scenario']['correctedWithoutLeavingForm'],
                      independent_comparisons=ui['phase1Scenario']['independentComparisons'],
                      reference_states=ui['phase1Scenario']['referenceStates'])
        validate_quotes(quotes, input_fingerprint(head, root=SOURCE, profile='production-quotes'))
    summary = dict(status='accepted-awaiting-signing', scope='Exact guarded core and production quote acceptance', source_sha=args.source_sha,
                   acceptance_source_sha=head, core_build_source_sha=core['build_source_sha'],
                   ui_source_sha=quotes['build_source_sha'], ui_run=quotes.get('workflow_run_id',args.ui_run),
                   candidate_run=args.candidate_run, core_run=core.get('workflow_run_id',args.core_run), baseline=baseline['release'],
                   profiles={name: dict(guard_job=p['receipt']['job'], guard_receipt_sha256=p['receipt_sha256'],
                                        worker=p['worker'])
                             for name, p in profiles.items()},
                   core_seconds=core.get('seconds'), quote_cycles=quotes['quote_reviews'],
                   production_arm_executed=False, physical_biometric_ui_executed=False)

    summary['reused_profiles'] = {name: dict(receipt_sha256=hashlib.sha256(raw).hexdigest(),
        preserved_bytes=True) for name, raw in reused_bytes.items()}
    summary['production_unsigned_artifact'] = production_artifact
    require(not args.out.exists(), 'Choose a fresh staging directory')
    args.out.mkdir(parents=True)
    try:
        staging = args.out / '.private'
        staging.mkdir()
        for name, value in (('core-acceptance.json', core), ('quote-acceptance.json', quotes),
                            ('core-validation-summary.json', summary)):
            if name in reused_bytes:
                (staging / name).write_bytes(reused_bytes[name])
            else:
                (staging / name).write_text(json.dumps(value, indent=2) + '\n')
    except BaseException:
        shutil.rmtree(args.out)
        raise
    print(json.dumps(dict(staged=True, files=3, profiles=len(profiles), source_sha=args.source_sha)))


if __name__=='__main__':main()
