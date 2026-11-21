"""Resume an unpublished hosted release on its original build."""
import hashlib
import io
import json
import os
import re
import subprocess
import zipfile

from custom_release import OPERATOR_ID, REPOSITORY, WORKFLOW, require_dispatch, verify_custom_child
from maintenance_cadence import MARKER as MAINTENANCE_MARKER, changed_marker, git
from release_metadata import require

RECOVERY_PATHS = {
    '.github/workflows/build-release.yml', '.github/workflows/custom-release.yml',
    '.github/workflows/quote-bridge.yml',
    '.github/workflows/hosted-acceptance-probe.yml', '.github/workflows/sync-upstream.yml', '.private/quote_recovery.py',
    '.private/hosted_release.py', '.private/hosted_quote_probe.py', '.private/custom_release.py',
    '.private/publish.py', '.private/integrate_release.py', '.private/verify_public_release.py',
    '.private/tor_resident_ui_driver.py', '.private/size_workflow_ui_scenario.py',
    '.private/test_packaging.py', '.private/test_quote_recovery.py', '.private/RELEASE-WORKFLOW.md',
    '.github/workflows/hosted-core.yml', '.private/hosted_claim.py', '.private/hosted_core.py', '.private/hosted_fixture.py',
}
# The protected controller may prepare fresh direct-mode test instrumentation
# while retaining the exact previously built product APKs.
DIRECT_RECOVERY_PATHS = {
    '.private/acceptance_policy.py', '.private/core_testnet_driver.py',
    '.private/core-wallet/src/co/electriccoin/zcash/app/CoreWalletAcceptance.kt',
    '.private/prepare_direct_core.py', '.private/test_acceptance_policy.py',
    '.private/test_hosted_release.py', '.private/core-coverage.md',
}
FUNDED_RECOVERY_PATHS = {
    '.github/workflows/build-release.yml', '.github/workflows/hosted-core.yml',
    '.private/funded_reuse.py', '.private/test_funded_reuse.py', '.private/acceptance_policy.py',
    '.private/hosted_release.py', '.private/hosted_core.py', '.private/core_testnet_driver.py',
    '.private/publish.py', '.private/integrate_release.py', '.private/test_hosted_release.py', '.private/hosted_fixture.py',
    '.private/prepare_direct_core.py', '.private/preserve_upgrade_fixture.py',
    '.private/test_hosted_fixture.py', '.private/test_core_instrumentation_pairing.py',
    '.private/quote_recovery.py', '.private/test_quote_recovery.py', '.private/test_packaging.py',
    '.private/RELEASE-WORKFLOW.md', '.private/core-coverage.md',
}

MAINTENANCE_WORKFLOW = '.github/workflows/sync-upstream.yml'
PUBLICATION_PATHS = {
    '.github/workflows/build-release.yml', '.github/workflows/custom-release.yml', MAINTENANCE_WORKFLOW,
    '.github/workflows/maintenance-watch.yml', '.github/workflows/verify-public-release.yml',
    '.private/custom_release.py', '.private/quote_recovery.py', '.private/hosted_release.py', '.private/publish.py',
    '.private/release_metadata.py', '.private/signing_wiring.py', '.private/reconcile_release.py', '.private/verify_public_release.py',
    '.private/integrate_release.py', '.private/maintenance_watch.py', '.private/test_maintenance_watch.py',
    '.private/test_publish.py', '.private/test_packaging.py', '.private/test_quote_recovery.py',
    '.private/RELEASE-WORKFLOW.md', '.private/MAINTENANCE.md',
}

ARTIFACTS = {
    'production_id': 'validated-unsigned-apk', 'core_id': 'core-testnet-candidate',
    'quote_id': 'isolated-ui-repro-apk', 'core_receipt_id': 'hosted-core-receipt',
}
TRANSIENT_FAILURE = 'HOSTED_QUOTE_FAILURE transient'


def require_transient_quote_failure(metadata, job):
    log = subprocess.check_output([metadata.gh, 'api', '--allow-escape-sequences',
                                   metadata.repo + f'/actions/jobs/{int(job["id"])}/logs'], timeout=30)
    marker = rb'(?:[0-9TZ:.-]+ )?' + re.escape(TRANSIENT_FAILURE.encode()) + rb' (?:tor_http|market_timeout)'
    require(len(log) <= 1024**2 and sum(bool(re.fullmatch(marker, line)) for line in log.splitlines()) == 1,
            'Original quote job did not prove a transient failure')


def same_controller_retry(source, controller, original_run, metadata, *, signing=False):
    from hosted_claim import post_ref, reserve_ref, verify_ref, verify_ref_rules

    require(re.fullmatch(r'[1-9][0-9]*', original_run) and os.environ['GITHUB_RUN_ATTEMPT'] == '1',
            'Same-controller quote retry requires the initial dispatch and exact original run')
    run = int(os.environ['GITHUB_RUN_ID'])
    require(run > 0 and run != int(original_run), 'Expected a separate quote recovery run')
    ref = 'refs/heads/testnet-claims/quote-recovery-' + original_run
    value = dict(kind='quote-recovery', source=source, controller=controller,
                 original_run=int(original_run), run=run)
    if signing:
        commit = metadata.api('/git/ref/' + ref.removeprefix('refs/'))['object']['sha']
        verify_ref(ref, value, commit, metadata)
    else:
        verify_ref_rules(ref, metadata)
        reserve_ref(ref, value, metadata, lambda path, data: post_ref(metadata, path, data))


def trusted_producer(run, run_id, *, maintenance=False):
    custom = run['path'] == WORKFLOW and run['event'] == 'workflow_dispatch' and run['actor']['id'] == OPERATOR_ID
    automatic = (maintenance and run['path'] == MAINTENANCE_WORKFLOW
                 and run['event'] in ('schedule', 'workflow_dispatch', 'push'))
    return ((custom or automatic) and run['id'] == int(run_id) and run['head_branch'] == 'main'
            and run['head_repository']['full_name'] == REPOSITORY and run['status'] == 'completed'
            and re.fullmatch(r'[a-f0-9]{40}', run['head_sha']))


def verify_build_candidate(root, source, original):
    from acceptance_policy import verify_promotion_revision
    from custom_release import MARKER as CUSTOM_MARKER

    require(source != original, 'Recovery requires the original hosted candidate child')
    if changed_marker(root, source, original, CUSTOM_MARKER):
        verify_custom_child(source, original, root)
        return
    require(git(root, 'rev-parse', source + '^1').decode().strip() == original
            and (changed_marker(root, source, original, MAINTENANCE_MARKER)
                 or changed_marker(root, source, original, '.private/candidate.json')),
            'Expected the exact original maintenance candidate')
    verify_promotion_revision(source, original, root)


def verify_maintenance_recovery(root, source, original, run, metadata):
    from custom_release import MARKER as CUSTOM_MARKER
    from maintenance_cadence import admit_cadence
    from maintenance_watch import api

    require(not changed_marker(root, source, original, CUSTOM_MARKER),
            'Maintenance producers cannot recover a custom candidate')
    maintenance = changed_marker(root, source, original, MAINTENANCE_MARKER)
    require(run['event'] != 'push' or maintenance.get('kind') == 'security',
            'Security review push cannot recover routine maintenance')
    admit_cadence(root, source, original, metadata, api)


def verify_delta(root, source, original, controller, *, funded=False):
    verify_build_candidate(root, source, original)
    require(original != controller and git(root, 'merge-base', original, controller).decode().strip() == original,
            'Quote recovery requires a descendant controller')
    delta = git(root, 'diff', '--no-renames', '--name-only', '-z', original, controller)
    paths = set(filter(None, delta.decode().split('\0')))
    if funded:
        require(paths <= FUNDED_RECOVERY_PATHS | PUBLICATION_PATHS, 'Controller changed outside funded recovery paths')
        return
    changed = paths - RECOVERY_PATHS - PUBLICATION_PATHS
    if changed:
        require(changed <= DIRECT_RECOVERY_PATHS, 'Controller changed outside quote recovery paths')
        from acceptance_policy import needs_live_tor_probe

        require(not needs_live_tor_probe(source, root), 'Controller changed outside quote recovery paths for a Tor candidate')


def resolve(root, source, controller, run_id, metadata, quote_run='', *, signing=False, preclaim_run=''):
    from hosted_release import artifact

    require(re.fullmatch(r'[1-9][0-9]*', run_id), 'Exact original run required')
    require_dispatch(source, controller, metadata, publish=True, recover_run=run_id, quote_run=quote_run,
                     preclaim_run=preclaim_run)
    run = metadata.api('/actions/runs/' + run_id)
    original = run['head_sha']
    require(trusted_producer(run, run_id, maintenance=True)
            and run['conclusion'] in ('cancelled', 'failure', 'success'),
            'Expected the original trusted custom run or maintenance run before publication')
    if run['path'] == MAINTENANCE_WORKFLOW:
        verify_maintenance_recovery(root, source, original, run, metadata)
    same = original == controller
    if same:
        require(run['run_attempt'] == 1 and os.environ['GITHUB_RUN_ATTEMPT'] == '1'
                and not preclaim_run and not quote_run,
                'Same-controller recovery requires the initial dispatch and exact original run')
        verify_build_candidate(root, source, original)

    jobs = metadata.pages(f'/actions/runs/{run_id}/attempts/{run["run_attempt"]}/jobs', 'jobs')

    def state(name, allowed=None):
        matching = [job for job in jobs if job['name'] == 'build / ' + name]
        if not matching and allowed is None:
            return None
        require(len(matching) == 1 and matching[0]['run_id'] == int(run_id)
                and matching[0]['head_sha'] == original and
                (allowed is None or matching[0]['conclusion'] in allowed),
                'Original job did not reach the required boundary: build / ' + name)
        return matching[0]['conclusion']

    for name in ('admit_hosted', 'unsigned', 'emulator', 'testnet_apk / testnet'):
        state(name, ('success',))
    wallet = state('hosted_core / wallet')
    reserve = state('hosted_core / reserve')
    prepared = state('hosted_core / prepare-direct')
    publisher = state('publish')
    quote_result = state('hosted_quotes / quote-cycles')
    original_preclaim = preclaim_run == run_id
    require(publisher in ('skipped', 'failure', 'cancelled', None),
            'Original job did not reach the required boundary: build / publish')
    artifacts = metadata.pages(f'/actions/runs/{run_id}/artifacts', 'artifacts')
    require(not any(row['name'].startswith('release-attestation-') for row in artifacts),
            'Resolve the retained publication attestation before another signing attempt')
    approved_upgrade = wallet == 'success' and reserve == 'skipped' and any(
        row['name'] == 'hosted-upgrade-receipt' for row in artifacts)
    funded = False
    if approved_upgrade:
        from funded_reuse import select

        require(not preclaim_run and select(source, controller, metadata, root=root) is not None,
                'Passing installed upgrade requires matching funded provenance')
        state('hosted_core / prepare-direct', ('success',))
        funded = True
    if (not same and not preclaim_run and not quote_run and run['run_attempt'] == 1
            and (run['conclusion'] == 'cancelled' or (run['conclusion'] == 'failure'
                 and (wallet == 'failure' or (wallet == 'skipped' and prepared == 'failure'))))):
        from funded_reuse import select

        funded = select(source, controller, metadata, root=root) is not None
    require(funded or run['conclusion'] != 'cancelled', 'Cancelled original requires qualified funded reuse')
    if not same:
        verify_delta(root, source, original, controller, funded=funded)
    outputs = {'original_controller': original}
    expected_artifacts = dict(ARTIFACTS)
    if approved_upgrade:
        require(publisher in ('skipped', 'failure', 'cancelled') and quote_result in ('success', 'failure'),
                'Recovery requires a passing upgrade and a verified quote or publisher boundary')
        expected_artifacts.update(core_receipt_id='hosted-upgrade-receipt', prepared_id='core-direct-candidate')
        outputs['reuse_funded'] = 'true'
    elif funded:
        expected_artifacts.pop('core_receipt_id')
        expected_artifacts.update(funded_recovery_artifacts(root, source, run, jobs, artifacts, metadata))
        outputs['reuse_funded'] = 'true'
    elif wallet == 'success':
        require(not preclaim_run and quote_result in ('success', 'failure')
                and ((publisher == 'skipped' and quote_result == 'failure')
                     or (publisher in ('skipped', 'failure', 'cancelled') and quote_result == 'success')),
                'Recovery requires a passing wallet and a verified quote or publisher boundary')
    else:
        missing_core = state('hosted_core') == 'skipped'
        stopped_before_wallet = (wallet == 'skipped' and reserve == 'skipped' and prepared == 'failure')
        require(not same and (missing_core or original_preclaim or stopped_before_wallet),
                'Original job did not reach the required boundary: wallet')
        require(publisher == 'skipped' and quote_result in ('success', 'failure', 'skipped')
                and not any(row['name'] in ('hosted-core-receipt', 'hosted-upgrade-receipt') for row in artifacts),
                'Wallet recovery requires a stopped publisher and no previous wallet receipt')
        if missing_core and not original_preclaim:
            require(not any(job['name'].startswith('build / hosted_core /') for job in jobs),
                    'Skipped-wallet recovery requires no previous wallet execution')
        from hosted_claim import PREFIX
        from release_inputs import version_code

        ref = PREFIX + 'release-' + str(version_code(root, source))
        if preclaim_run:
            outputs['previous_reservation'] = unused_reservation(root, source, controller, run_id, original,
                                                                 preclaim_run, metadata)
        elif not signing:
            reservations = metadata.api('/git/matching-refs/' + ref.removeprefix('refs/'))
            require(not any(row['ref'] in (ref, ref + '-preclaim-recovery') for row in reservations),
                    'This release already has a protected funding reservation')
        expected_artifacts.pop('core_receipt_id')
        outputs['run_missing_core'] = 'true'
    if same and quote_result == 'failure':
        original_quote = next(job for job in jobs if job['name'] == 'build / hosted_quotes / quote-cycles')
        require_transient_quote_failure(metadata, original_quote)
        outputs['same_controller_retry'] = 'true'
    if quote_result == 'success':
        require(not quote_run, 'The original run already has a passing quote receipt')
        expected_artifacts['quote_receipt_id'] = 'hosted-quote-receipt'
        outputs.update(quote_controller=original, quote_run=int(run_id))
    for key, name in expected_artifacts.items():
        matching = [row for row in artifacts if row['name'] == name]
        require(len(matching) == 1, 'Expected one original artifact: ' + name)
        outputs[key] = str(artifact(metadata, matching[0]['id'], name, run_id, original)['id'])
    if quote_run:
        if preclaim_run:
            outputs.update(resolve_preclaim_quotes(root, source, controller, original, run_id, quote_run,
                                                   outputs, metadata))
        else:
            outputs.update(resolve_quotes(root, source, controller, original, quote_run, metadata))
    if signing and outputs.get('same_controller_retry'):
        same_controller_retry(source, controller, run_id, metadata, signing=True)
    return outputs


def funded_recovery_artifacts(root, source, run, jobs, artifacts, metadata):
    from hosted_claim import binding, ref_for, verify
    from release_inputs import version_code

    def state(name, allowed, *, optional=False):
        matching = [job for job in jobs if job['name'] == 'build / ' + name]
        if optional and not matching:
            return None
        require(len(matching) == 1 and matching[0]['run_id'] == run['id']
                and matching[0]['head_sha'] == run['head_sha'] and matching[0]['conclusion'] in allowed,
                'Original funded recovery job did not reach the required boundary: ' + name)
        return matching[0]['conclusion']

    cancelled = run['conclusion'] == 'cancelled'
    wallet = state('hosted_core / wallet', ('failure', 'cancelled', 'skipped') if cancelled else ('failure', 'skipped'),
                   optional=cancelled)
    reserve = state('hosted_core / reserve', ('success', 'skipped', 'cancelled') if cancelled else ('success', 'skipped'),
                    optional=cancelled)
    state('publish', ('skipped', 'cancelled') if cancelled else ('skipped',), optional=cancelled)
    state('hosted_quotes / quote-cycles', ('success',) if cancelled else ('success', 'failure', 'skipped'))
    prepared = state('hosted_core / prepare-direct', ('success', 'failure', 'cancelled', 'skipped'), optional=True)
    require(not any(row['name'] in ('hosted-core-receipt', 'hosted-upgrade-receipt') for row in artifacts),
            'Funded recovery requires no previous wallet receipt')
    require(cancelled or wallet != 'skipped' or (prepared == 'failure' and reserve == 'skipped'),
            'Skipped wallet requires a verified preparation failure')
    require(reserve != 'skipped' or wallet != 'failure' or prepared == 'success',
            'Unfunded wallet failure requires a passing direct preparation')

    value = binding(source, run['head_sha'], version_code(root, source), run['id'], False)
    ref = ref_for(value)
    reservations = metadata.api('/git/matching-refs/' + ref.removeprefix('refs/'))
    require(not any(row['ref'] == ref + '-preclaim-recovery' for row in reservations),
            'This release already has a linked funding reservation')
    existing = [row for row in reservations if row['ref'] == ref]
    if reserve == 'success' or existing:
        require(len(existing) == 1, 'Expected the original protected funding reservation')
        verify(value, existing[0]['object']['sha'], metadata)

    result = {}
    candidates = [row for row in artifacts if row['name'] == 'core-direct-candidate']
    require(len(candidates) <= 1, 'Expected one original direct preparation artifact')
    if prepared == 'success' and candidates and candidates[0].get('expired') is False:
        result['prepared_id'] = 'core-direct-candidate'
    return result


def unused_reservation(root, source, controller, original_run, original, prior_run, metadata):
    from hosted_claim import binding, ref_for, verify
    from release_inputs import version_code
    import subprocess

    require(re.fullmatch(r'[1-9][0-9]*', prior_run),
            'Exact prior pre-claim run required')
    run = metadata.api('/actions/runs/' + prior_run)
    prior = run['head_sha']
    require(trusted_producer(run, prior_run, maintenance=prior_run == original_run)
            and run['conclusion'] == 'failure' and run['run_attempt'] == 1 and prior != controller
            and git(root, 'merge-base', prior, controller).decode().strip() == prior,
            'Prior pre-claim run does not match the trusted recovery controller')
    if prior_run == original_run:
        require(prior == original, 'Original pre-claim run has a different controller')
    else:
        verify_delta(root, source, original, prior)
    jobs = metadata.pages(f'/actions/runs/{prior_run}/attempts/1/jobs', 'jobs')
    wallet = None
    for name, conclusion in (('build / admit_hosted', 'success'), ('build / hosted_core / reserve', 'success'),
                             ('build / hosted_core / wallet', 'failure'), ('build / publish', 'skipped')):
        matching = [job for job in jobs if job['name'] == name]
        require(len(matching) == 1 and matching[0]['conclusion'] == conclusion
                and matching[0]['run_id'] == int(prior_run) and matching[0]['head_sha'] == prior,
                'Prior run lacks the required pre-claim job state: ' + name)
        if name.endswith('/ wallet'):
            wallet = matching[0]['id']
    log = subprocess.check_output([metadata.gh, 'api', '--allow-escape-sequences',
                                   metadata.repo + f'/actions/jobs/{int(wallet)}/logs'], timeout=30)
    startup_failed = (b'Hosted lifecycle failed at networkTargets, claim state no-POST' in log
                      and b'CORE_STAGE recipient passed' not in log and b'CORE_STAGE sender passed' not in log)
    readiness_failed = (b'Hosted lifecycle failed at preclaim_readiness, claim state no-POST' in log
                        and b'CORE_STAGE sender passed' in log)
    require(len(log) <= 1024**2 and (startup_failed or readiness_failed) and b'CORE_STAGE fund passed' not in log,
            'Prior wallet did not prove failure before any funding request')
    value = binding(source, prior, version_code(root, source), int(prior_run), False)
    commit = metadata.api('/git/ref/' + ref_for(value).removeprefix('refs/'))['object']['sha']
    verify(value, commit, metadata)
    return commit


def resolve_quotes(root, source, controller, original, quote_run, metadata):
    from hosted_release import artifact

    require(re.fullmatch(r'[1-9][0-9]*', quote_run), 'Exact quote recovery run required')
    run = metadata.api('/actions/runs/' + quote_run)
    quote_controller = run['head_sha']
    require(run['id'] == int(quote_run) and run['path'] == WORKFLOW and run['event'] == 'workflow_dispatch'
            and run['head_branch'] == 'main' and run['head_repository']['full_name'] == REPOSITORY
            and run['actor']['id'] == OPERATOR_ID and run['status'] == 'completed'
            and run['conclusion'] == 'failure' and re.fullmatch(r'[a-f0-9]{40}', quote_controller),
            'Expected the exact trusted recovery run with a signing failure')
    verify_delta(root, source, original, quote_controller)
    require(git(root, 'merge-base', quote_controller, controller).decode().strip() == quote_controller,
            'Quote receipt controller is not an ancestor of the signer')
    delta = git(root, 'diff', '--no-renames', '--name-only', '-z', quote_controller, controller)
    require(set(filter(None, delta.decode().split('\0'))) <= PUBLICATION_PATHS,
            'Quote execution inputs changed after the passing receipt')
    jobs = metadata.pages(f'/actions/runs/{quote_run}/attempts/{run["run_attempt"]}/jobs', 'jobs')
    for name, conclusion in (('build / admit_hosted', 'success'),
                             ('build / hosted_quotes / quote-cycles', 'success'), ('build / publish', 'failure')):
        matching = [job for job in jobs if job['name'] == name]
        require(len(matching) == 1 and matching[0]['conclusion'] == conclusion
                and matching[0]['run_id'] == int(quote_run) and matching[0]['head_sha'] == quote_controller,
                'Prior quote recovery job did not reach the required boundary: ' + name)
    artifacts = metadata.pages(f'/actions/runs/{quote_run}/artifacts', 'artifacts')
    matching = [row for row in artifacts if row['name'] == 'hosted-quote-receipt']
    require(len(matching) == 1 and not any(row['name'].startswith('release-attestation-') for row in artifacts),
            'Prior recovery must have one passing quote receipt and no publication attestation')
    quote_receipt = artifact(metadata, matching[0]['id'], 'hosted-quote-receipt', quote_run, quote_controller)
    return {'quote_receipt_id': str(quote_receipt['id']), 'quote_controller': quote_controller,
            'quote_run': int(quote_run)}


def resolve_preclaim_quotes(root, source, controller, original, build_run, quote_run, outputs, metadata):
    from acceptance_policy import input_fingerprint, validate_quotes
    from hosted_release import artifact
    from release_inputs import version_code

    require(re.fullmatch(r'[1-9][0-9]*', quote_run) and quote_run != build_run,
            'Exact linked quote recovery run required')
    run = metadata.api('/actions/runs/' + quote_run)
    prior = run['head_sha']
    require(run['id'] == int(quote_run) and run['path'] == WORKFLOW and run['event'] == 'workflow_dispatch'
            and run['head_branch'] == 'main' and run['head_repository']['full_name'] == REPOSITORY
            and run['actor']['id'] == OPERATOR_ID and run['status'] == 'completed'
            and run['conclusion'] == 'failure' and run['run_attempt'] == 1
            and re.fullmatch(r'[a-f0-9]{40}', prior) and prior != controller
            and git(root, 'merge-base', prior, controller).decode().strip() == prior,
            'Expected a completed trusted recovery before the linked claim')
    verify_delta(root, source, original, prior)
    changed = git(root, 'diff', '--no-renames', '--name-only', '-z', prior, controller)
    require(set(filter(None, changed.decode().split('\0'))) <= {
        '.private/prepare_direct_core.py', '.private/quote_recovery.py', '.github/workflows/build-release.yml',
        '.private/test_quote_recovery.py', '.private/RELEASE-WORKFLOW.md',
    }, 'Prior quote execution inputs changed')
    jobs = metadata.pages(f'/actions/runs/{quote_run}/attempts/1/jobs', 'jobs')
    for name, conclusion in (('admit_hosted', 'success'), ('recovery_quotes / quote-cycles', 'success'),
                             ('hosted_core / prepare-direct', 'failure'), ('hosted_core / reserve', 'skipped'),
                             ('hosted_core / wallet', 'skipped'), ('publish', 'skipped')):
        matching = [job for job in jobs if job['name'] == 'build / ' + name
                    and job['run_id'] == int(quote_run) and job['head_sha'] == prior]
        require(len(matching) == 1 and matching[0]['conclusion'] == conclusion,
                'Prior recovery did not prove a passing quote and stopped wallet setup: ' + name)
    artifacts = metadata.pages(f'/actions/runs/{quote_run}/artifacts', 'artifacts')
    matching = [row for row in artifacts if row['name'] == 'hosted-quote-receipt']
    require(len(matching) == 1, 'Expected exactly one linked recovery quote receipt')
    recorded = artifact(metadata, matching[0]['id'], 'hosted-quote-receipt', quote_run, prior)
    require(recorded['size_in_bytes'] <= 64 * 1024, 'Quote receipt archive is too large')
    archive = subprocess.check_output([metadata.gh, 'api', metadata.repo +
                                        f'/actions/artifacts/{recorded["id"]}/zip'], timeout=30)
    require(len(archive) == recorded['size_in_bytes'] and 'sha256:' + hashlib.sha256(archive).hexdigest() ==
            recorded['digest'], 'Linked quote receipt archive changed')
    with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
        require(bundle.namelist() == ['hosted-quotes.json'] and
                bundle.getinfo('hosted-quotes.json').file_size <= 64 * 1024, 'Unexpected linked quote receipt archive')
        receipt = json.loads(bundle.read('hosted-quotes.json'))
    validate_quotes(receipt, input_fingerprint(source, root=root, profile='production-quotes'), use_tor=False)
    require(receipt.get('build_source_sha') == source and receipt.get('candidate_version_code') ==
            version_code(root, source) and receipt.get('workflow_run_id') == int(quote_run)
            and receipt.get('artifact_run_id') == int(build_run) and receipt.get('quote_controller_sha') == prior
            and receipt.get('producer') == artifact(metadata, outputs['quote_id'], 'isolated-ui-repro-apk',
                                                     build_run, original)
            and receipt.get('production_artifact') == artifact(metadata, outputs['production_id'],
                                                               'validated-unsigned-apk', build_run, original),
            'Linked quote receipt does not match the original release APKs')
    return {'quote_receipt_id': str(recorded['id']), 'quote_controller': prior, 'quote_run': int(quote_run)}
