"""Resume an unpublished hosted release on its original build."""
import json
import os
import re
import subprocess

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
    '.private/test_custom_release.py', '.private/test_hosted_release.py',
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


def verify_quote_reservation(source, controller, original_run, run, metadata):
    from hosted_claim import verify_ref

    ref = 'refs/heads/testnet-claims/quote-recovery-' + original_run
    value = dict(kind='quote-recovery', source=source, controller=controller,
                 original_run=int(original_run), run=int(run))
    commit = metadata.api('/git/ref/' + ref.removeprefix('refs/'))['object']['sha']
    verify_ref(ref, value, commit, metadata)


def same_controller_retry(source, controller, original_run, metadata, *, signing=False):
    from hosted_claim import post_ref, reserve_ref, verify_ref_rules

    require(re.fullmatch(r'[1-9][0-9]*', original_run) and os.environ['GITHUB_RUN_ATTEMPT'] == '1',
            'Same-controller quote retry requires the initial dispatch and exact original run')
    run = int(os.environ['GITHUB_RUN_ID'])
    require(run > 0 and run != int(original_run), 'Expected a separate quote recovery run')
    ref = 'refs/heads/testnet-claims/quote-recovery-' + original_run
    value = dict(kind='quote-recovery', source=source, controller=controller,
                 original_run=int(original_run), run=run)
    if signing:
        verify_quote_reservation(source, controller, original_run, run, metadata)
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


def resolve(root, source, controller, run_id, metadata, quote_run='', *, signing=False, preclaim_run='', core_run=''):
    from hosted_release import artifact, verify_recovered_receipts

    require(re.fullmatch(r'[1-9][0-9]*', run_id), 'Exact original run required')
    require_dispatch(source, controller, metadata, publish=True, recover_run=run_id, quote_run=quote_run,
                     preclaim_run=preclaim_run, core_run=core_run)
    require(not (preclaim_run and core_run), 'Choose a passing wallet run or a failed pre-claim run')
    run = metadata.api('/actions/runs/' + run_id)
    original = run['head_sha']
    require(trusted_producer(run, run_id, maintenance=True)
            and run['conclusion'] in ('cancelled', 'failure', 'success'),
            'Expected the original trusted custom run or maintenance run before publication')
    if run['path'] == MAINTENANCE_WORKFLOW:
        verify_maintenance_recovery(root, source, original, run, metadata)
    same = original == controller
    retry_attempt = int(os.environ.get('GITHUB_RUN_ATTEMPT', '1'))
    own_quote_retry = same and retry_attempt > 1 and not quote_run
    if same:
        require(run['run_attempt'] == 1 and not preclaim_run and not core_run,
                'Same-controller recovery requires the exact original attempt without a wallet selector')
        verify_build_candidate(root, source, original)

    jobs = metadata.pages(f'/actions/runs/{run_id}/attempts/{run["run_attempt"]}/jobs', 'jobs')

    def state(name, allowed=None):
        matching = [job for job in jobs if job['name'] == 'build / ' + name]
        if not matching and allowed is None:
            return None
        require(len(matching) == 1 and matching[0]['run_id'] == int(run_id)
                and matching[0]['head_sha'] == original and
                matching[0]['conclusion'] is not None and
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
    outputs = {'original_controller': original}
    expected_artifacts = dict(ARTIFACTS)
    funded = False
    if core_run:
        require(wallet != 'success' and core_run != run_id
                and not any(row['name'] in ('hosted-core-receipt', 'hosted-upgrade-receipt') for row in artifacts),
                'The original run already has wallet acceptance evidence')
        outputs.update(resolve_core(root, source, controller, original, core_run, metadata,
                                    original_run=run_id, original_prepared=prepared, original_artifacts=artifacts))
        expected_artifacts.pop('core_receipt_id')
        funded = outputs.get('reuse_funded') == 'true'
    elif approved_upgrade:
        require(not preclaim_run, 'Passing installed upgrade cannot start a linked claim')
        state('hosted_core / prepare-direct', ('success',))
        funded = True
    elif (not same and not preclaim_run and wallet != 'success' and run['run_attempt'] == 1
            and (run['conclusion'] == 'cancelled' or (run['conclusion'] == 'failure'
                 and (wallet == 'failure' or (wallet == 'skipped' and prepared == 'failure'))))):
        from funded_reuse import select

        funded = select(source, controller, metadata, root=root) is not None
    require(funded or run['conclusion'] != 'cancelled'
            or ((wallet == 'success' or core_run) and (quote_result == 'success' or quote_run)),
            'Cancelled original requires passing wallet and quote receipts or qualified funded reuse')
    if not same:
        verify_delta(root, source, original, controller, funded=funded)
    if core_run:
        require(quote_result == 'success' or quote_run, 'A passing wallet recovery requires a passing quote receipt')
    elif approved_upgrade:
        require((publisher is not None or run['conclusion'] == 'cancelled')
                and quote_result in ('success', 'failure'),
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
                     or (quote_result == 'success' and (publisher is not None or run['conclusion'] == 'cancelled'))),
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
        if not quote_run and not own_quote_retry:
            outputs['same_controller_retry'] = 'true'
    elif same:
        require(not own_quote_retry, 'Signer rerun requires a previously reserved same-controller quote retry')
    if quote_result == 'success':
        require(not quote_run, 'The original run already has a passing quote receipt')
        expected_artifacts['quote_receipt_id'] = 'hosted-quote-receipt'
        outputs.update(quote_controller=original, quote_run=int(run_id))
    if 'core_receipt_id' in expected_artifacts:
        outputs.update(core_controller=original, core_run=int(run_id))
    for key, name in expected_artifacts.items():
        matching = [row for row in artifacts if row['name'] == name]
        require(len(matching) == 1, 'Expected one original artifact: ' + name)
        outputs[key] = str(artifact(metadata, matching[0]['id'], name, run_id, original)['id'])
    if quote_run:
        if preclaim_run:
            outputs.update(resolve_preclaim_quotes(root, source, controller, original, run_id, quote_run, metadata))
        else:
            outputs.update(resolve_quotes(root, source, controller, original, quote_run, metadata, original_run=run_id,
                                          retry_allowed=quote_result == 'failure', funded=funded))
    if own_quote_retry:
        outputs.update(resolve_quotes(root, source, controller, original, os.environ['GITHUB_RUN_ID'], metadata,
                                      original_run=run_id, retry_allowed=True, historical=True))
    if quote_result == 'failure' and (outputs.get('same_controller_retry')
                                      or outputs.get('quote_controller') == original):
        original_quote = next(job for job in jobs if job['name'] == 'build / hosted_quotes / quote-cycles')
        require_transient_quote_failure(metadata, original_quote)
    if signing and outputs.get('same_controller_retry'):
        same_controller_retry(source, controller, run_id, metadata, signing=True)
    verify_recovered_receipts(root, source, controller, run_id, outputs, metadata,
                             original_prepared=prepared, original_reserve=reserve)
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


def stopped_recovery(run_id, metadata, *, historical=False):
    path = '/actions/runs/' + run_id
    if historical:
        run = metadata.api(path + '/attempts/1')
    else:
        run = metadata.api(path)
        require(trusted_producer(run, run_id) and run['conclusion'] in ('failure', 'cancelled'),
                'Expected the exact trusted recovery attempt before publication')
        if run['run_attempt'] > 1:
            attempt = metadata.api(path + '/attempts/1')
            require(attempt['head_sha'] == run['head_sha'], 'Recovery attempt controller changed')
            run = attempt
    require(trusted_producer(run, run_id) and run['run_attempt'] == 1
            and run['conclusion'] in ('failure', 'cancelled'),
            'Expected the exact trusted recovery attempt before publication')
    jobs = metadata.pages(f'/actions/runs/{run_id}/attempts/1/jobs', 'jobs')

    def state(name, allowed):
        matching = [job for job in jobs if job['name'] == 'build / ' + name]
        require((not matching and None in allowed) or
                (len(matching) == 1 and matching[0]['run_id'] == int(run_id)
                 and matching[0]['head_sha'] == run['head_sha'] and matching[0]['conclusion'] is not None
                 and matching[0]['conclusion'] in allowed),
                'Prior recovery job did not reach the required boundary: build / ' + name)
        return matching[0]['conclusion'] if matching else None

    state('admit_hosted', ('success',))
    state('publish', ('failure',) if run['conclusion'] == 'failure' else ('failure', 'cancelled', 'skipped', None))
    artifacts = metadata.pages(f'/actions/runs/{run_id}/artifacts', 'artifacts')
    require(not any(row['name'].startswith('release-attestation-') for row in artifacts),
            'Prior recovery must have no publication attestation')
    return run['head_sha'], state, artifacts


def retained_artifact(metadata, artifacts, name, run, controller):
    from hosted_release import artifact

    matching = [row for row in artifacts if row['name'] == name]
    require(len(matching) == 1, 'Expected exactly one prior recovery artifact: ' + name)
    return str(artifact(metadata, matching[0]['id'], name, run, controller)['id'])


def verify_receipt_delta(root, producer, controller, stage):
    if producer == controller:
        return
    require(git(root, 'merge-base', producer, controller).decode().strip() == producer,
            stage + ' receipt controller is not an ancestor of the signer')
    delta = git(root, 'diff', '--no-renames', '--name-only', '-z', producer, controller)
    require(set(filter(None, delta.decode().split('\0'))) <= PUBLICATION_PATHS,
            stage + ' execution inputs changed after the passing receipt')


def resolve_core(root, source, controller, original, core_run, metadata, *, original_run, original_prepared,
                 original_artifacts):
    require(re.fullmatch(r'[1-9][0-9]*', core_run), 'Exact wallet recovery run required')
    core_controller, state, artifacts = stopped_recovery(core_run, metadata)
    state('hosted_core / wallet', ('success',))
    reserve = state('hosted_core / reserve', ('success', 'skipped'))
    funded = reserve == 'skipped'
    verify_delta(root, source, original, core_controller, funded=funded)
    verify_receipt_delta(root, core_controller, controller, 'Wallet')
    receipt = 'hosted-upgrade-receipt' if funded else 'hosted-core-receipt'
    other = 'hosted-core-receipt' if funded else 'hosted-upgrade-receipt'
    require(not any(row['name'] == other for row in artifacts), 'Ambiguous wallet recovery receipts')
    result = dict(core_receipt_id=retained_artifact(metadata, artifacts, receipt, core_run, core_controller),
                  core_run=int(core_run), core_controller=core_controller)
    prepared = state('hosted_core / prepare-direct', ('success', 'skipped', None) if funded else ('success',))
    if prepared == 'success':
        prepared_artifacts, prepared_run, prepared_controller = artifacts, core_run, core_controller
    else:
        require(original_prepared == 'success', 'Prior upgrade requires a passing direct preparation job')
        prepared_artifacts, prepared_run, prepared_controller = original_artifacts, original_run, original
    result.update(prepared_id=retained_artifact(metadata, prepared_artifacts, 'core-direct-candidate',
                                               prepared_run, prepared_controller),
                  prepared_run=int(prepared_run), prepared_controller=prepared_controller)
    if funded:
        result['reuse_funded'] = 'true'
    return result


def resolve_quotes(root, source, controller, original, quote_run, metadata, *, original_run='', retry_allowed=False,
                   funded=False, historical=False):
    require(re.fullmatch(r'[1-9][0-9]*', quote_run) and quote_run != original_run, 'Exact quote recovery run required')
    quote_controller, state, artifacts = stopped_recovery(quote_run, metadata, historical=historical)
    normal = state('hosted_quotes / quote-cycles', ('success', 'skipped', None))
    linked = state('recovery_quotes / quote-cycles', ('success', 'skipped', None))
    require((normal == 'success') != (linked == 'success'), 'Expected exactly one passing quote recovery job')
    if quote_controller == original:
        require(retry_allowed and original_run and normal == 'success',
                'Same-controller quote receipt requires the original transient quote failure')
        state('reserve_quote_retry', ('success',))
        verify_quote_reservation(source, original, original_run, quote_run, metadata)
    else:
        require(not historical, 'Signer rerun requires the original quote retry controller')
        verify_delta(root, source, original, quote_controller, funded=funded)
    verify_receipt_delta(root, quote_controller, controller, 'Quote')
    return dict(quote_receipt_id=retained_artifact(metadata, artifacts, 'hosted-quote-receipt',
                                                 quote_run, quote_controller),
                quote_controller=quote_controller, quote_run=int(quote_run))


def resolve_preclaim_quotes(root, source, controller, original, build_run, quote_run, metadata):
    require(re.fullmatch(r'[1-9][0-9]*', quote_run) and quote_run != build_run,
            'Exact linked quote recovery run required')
    run = metadata.api('/actions/runs/' + quote_run)
    prior = run['head_sha']
    require(trusted_producer(run, quote_run) and run['conclusion'] == 'failure' and run['run_attempt'] == 1
            and prior != controller
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
    require(not any(row['name'].startswith('release-attestation-') for row in artifacts),
            'Prior recovery must have no publication attestation')
    return dict(quote_receipt_id=retained_artifact(metadata, artifacts, 'hosted-quote-receipt', quote_run, prior),
                quote_controller=prior, quote_run=int(quote_run))
