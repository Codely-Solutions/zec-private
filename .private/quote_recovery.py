"""Resume an unpublished hosted release on its original build."""
import json
import os
import re
import subprocess

from custom_release import OPERATOR_ID, REPOSITORY, WORKFLOW, require_dispatch, verify_custom_child
from maintenance_cadence import MARKER as MAINTENANCE_MARKER, changed_marker, git
from release_metadata import (UPSTREAM_DISCOVERY_WORKFLOW, artifact_producer_job, copied_job_matches,
                              hosted_release_job_names, job_timestamp, require)

RECOVERY_PATHS = {
    '.github/workflows/build-release.yml', '.github/workflows/custom-release.yml',
    '.github/workflows/quote-bridge.yml',
    '.github/workflows/hosted-acceptance-probe.yml', '.github/workflows/sync-upstream.yml',
    '.github/workflows/sync-upstream-main.yml', '.private/quote_recovery.py',
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

MAINTENANCE_WORKFLOW = UPSTREAM_DISCOVERY_WORKFLOW
PUBLICATION_PATHS = {
    '.github/workflows/build-release.yml', '.github/workflows/custom-release.yml', MAINTENANCE_WORKFLOW,
    '.github/workflows/sync-upstream-main.yml',
    '.github/workflows/maintenance-watch.yml', '.github/workflows/verify-public-release.yml',
    '.private/custom_release.py', '.private/quote_recovery.py', '.private/hosted_release.py', '.private/publish.py',
    '.private/release_metadata.py', '.private/signing_wiring.py', '.private/reconcile_release.py', '.private/verify_public_release.py',
    '.private/integrate_release.py', '.private/maintenance_watch.py', '.private/test_maintenance_watch.py',
    '.private/test_publish.py', '.private/test_packaging.py', '.private/test_quote_recovery.py',
    '.private/test_custom_release.py', '.private/test_hosted_release.py',
    '.private/RELEASE-WORKFLOW.md', '.private/MAINTENANCE.md', '.private/notify.py', '.private/test_notify.py',
}

ARTIFACTS = {
    'production_id': 'validated-unsigned-apk', 'core_id': 'core-testnet-candidate',
    'quote_id': 'isolated-ui-repro-apk', 'core_receipt_id': 'hosted-core-receipt',
}
ARTIFACT_JOBS = {
    'validated-unsigned-apk': 'unsigned',
    'core-testnet-candidate': 'testnet_apk / testnet',
    'isolated-ui-repro-apk': 'emulator',
    'hosted-core-receipt': 'hosted_core / wallet',
    'hosted-upgrade-receipt': 'hosted_core / wallet',
    'core-direct-candidate': 'hosted_core / prepare-direct',
}
QUOTE_JOBS = ('hosted_quotes / quote-cycles', 'recovery_quotes / quote-cycles')
WALLET_RECEIPTS = ('hosted-core-receipt', 'hosted-upgrade-receipt')
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


def trusted_producer(run, run_id, *, maintenance=False, completed=True):
    custom = run['path'] == WORKFLOW and run['event'] == 'workflow_dispatch' and run['actor']['id'] == OPERATOR_ID
    automatic = (maintenance and run['path'] == MAINTENANCE_WORKFLOW
                 and run['event'] in ('schedule', 'workflow_dispatch', 'push'))
    return ((custom or automatic) and run['id'] == int(run_id) and run['head_branch'] == 'main'
            and run['head_repository']['full_name'] == REPOSITORY and (not completed or run['status'] == 'completed')
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


class UnpublishedRun:
    """Keep the original stage decisions and inspect every attempt for publication."""

    def __init__(self, metadata, run, *, original=False):
        self.metadata = metadata
        self.run = run
        self.path = f'/actions/runs/{run["id"]}'
        latest = run['run_attempt']
        require(type(latest) is int and latest >= 1, 'Invalid recovery run attempt')
        self.attempts = {latest: run}
        self.jobs = {}
        self.artifacts = metadata.pages(self.path + '/artifacts', 'artifacts')
        require(not any(row['name'].startswith('release-attestation-') for row in self.artifacts),
                'Resolve the retained publication attestation before another signing attempt' if original
                else 'Prior recovery must have no publication attestation')
        for number in range(1, latest + 1):
            jobs = metadata.pages(f'{self.path}/attempts/{number}/jobs', 'jobs')
            require(all(job['run_id'] == run['id'] and job['head_sha'] == run['head_sha']
                        and job['run_attempt'] == number for job in jobs),
                    'Recovery job run, attempt or controller differs')
            self.jobs[number] = jobs
            publisher = self.job('publish', number)
            active = number == latest and run['status'] != 'completed'
            require(publisher is None or (publisher['status'] == 'completed'
                                          and publisher['conclusion'] in ('skipped', 'failure', 'cancelled'))
                    or (active and publisher['conclusion'] is None),
                    'Recovery job did not reach the required boundary: build / publish')

    def attempt(self, number):
        if number not in self.attempts:
            value = self.metadata.api(f'{self.path}/attempts/{number}')
            require(trusted_producer(value, str(self.run['id']), maintenance=True)
                    and value['head_sha'] == self.run['head_sha'] and value['run_attempt'] == number
                    and value['path'] == self.run['path'] and value['event'] == self.run['event'],
                    'Recovery attempt identity differs from the trusted run')
            self.attempts[number] = value
        return self.attempts[number]

    def job(self, name, number):
        names = hosted_release_job_names(self.run['path'], name)
        matching = [job for job in self.jobs[number] if job['name'] in names]
        require(len(matching) <= 1, 'Ambiguous recovery job: build / ' + name)
        return next(iter(matching), None)

    def state(self, name, allowed=None, *, attempt=1):
        job = self.job(name, attempt)
        if job is not None:
            require(job['status'] == 'completed' and job['conclusion'] is not None,
                    'Recovery job did not reach the required boundary: build / ' + name)
        result = job['conclusion'] if job else None
        require(allowed is None or result in allowed,
                'Recovery job did not reach the required boundary: build / ' + name)
        return result

    def contains(self, *names):
        return any(row['name'] in names for row in self.artifacts)

    def retained(self, name, *roles, first_attempt=False):
        from hosted_release import artifact_record

        rows = [row for row in self.artifacts if row['name'] == name]
        require(len(rows) == 1, 'Expected one live artifact for recovery: ' + name)
        value = artifact_record(self.metadata, rows[0]['id'], name, self.run['id'], self.run['head_sha'])
        names = set().union(*(hosted_release_job_names(self.run['path'], job)
                             for job in (roles or (ARTIFACT_JOBS[name],))))
        jobs = [job for group in self.jobs.values() for job in group if job['name'] in names]
        producer = artifact_producer_job(self.run, jobs, value, self.attempt, role=name, strict_timestamps=True)
        require(not first_attempt or producer['run_attempt'] == 1,
                'Recovery requires the original attempt for ' + name)
        self.state('admit_hosted', ('success',), attempt=producer['run_attempt'])
        return str(value['id']), producer


def resolve(root, source, controller, run_id, metadata, quote_run='', *, signing=False, preclaim_run='', core_run=''):
    from hosted_release import require_current_hosted_upgrade, verify_recovered_receipts

    require(re.fullmatch(r'[1-9][0-9]*', run_id), 'Exact original run required')
    require_dispatch(source, controller, metadata, publish=True, recover_run=run_id, quote_run=quote_run,
                     preclaim_run=preclaim_run, core_run=core_run)
    require(not (preclaim_run and core_run), 'Choose a passing wallet run or a failed pre-claim run')
    require_current_hosted_upgrade(root, metadata)
    run = metadata.api('/actions/runs/' + run_id)
    original = run['head_sha']
    require(trusted_producer(run, run_id, maintenance=True)
            and run['conclusion'] in ('cancelled', 'failure', 'success'),
            'Expected the original trusted custom run or maintenance run before publication')
    if run['path'] == MAINTENANCE_WORKFLOW:
        verify_maintenance_recovery(root, source, original, run, metadata)
    history = UnpublishedRun(metadata, run, original=True)
    initial = history.attempt(1)
    state = history.state
    same = original == controller
    if same:
        require(not preclaim_run and not core_run,
                'Same-controller recovery requires the exact original attempt without a wallet selector')
        verify_build_candidate(root, source, original)

    wallet = state('hosted_core / wallet')
    reserve = state('hosted_core / reserve')
    prepared = state('hosted_core / prepare-direct')
    publisher = state('publish')
    quote_result = state(QUOTE_JOBS[0])
    original_quote = quote_result == 'success' or history.contains('hosted-quote-receipt')
    own_quote_retry = (same and not original_quote and not quote_run and quote_result == 'failure'
                       and int(os.environ.get('GITHUB_RUN_ATTEMPT', '1')) > 1)
    original_preclaim = preclaim_run == run_id
    approved_upgrade = wallet == 'success' and reserve == 'skipped' and history.contains('hosted-upgrade-receipt')
    builds = {key: history.retained(name) for key, name in ARTIFACTS.items() if key != 'core_receipt_id'}
    outputs = {'original_controller': original, **{key: value[0] for key, value in builds.items()}}
    if wallet == 'success':
        receipt = 'hosted-upgrade-receipt' if approved_upgrade else 'hosted-core-receipt'
        other = next(name for name in WALLET_RECEIPTS if name != receipt)
        require(not history.contains(other), 'Ambiguous original wallet receipts')
        outputs.update(core_receipt_id=history.retained(receipt, first_attempt=True)[0],
                       core_controller=original, core_run=int(run_id))
        if prepared == 'success':
            outputs['prepared_id'] = history.retained('core-direct-candidate', first_attempt=True)[0]
    funded = False
    if core_run:
        require(wallet != 'success' and core_run != run_id and not history.contains(*WALLET_RECEIPTS),
                'The original run already has wallet acceptance evidence')
        outputs.update(resolve_core(root, source, controller, original, core_run, metadata,
                                    original_history=history))
        funded = outputs.get('reuse_funded') == 'true'
    elif approved_upgrade:
        require(not preclaim_run, 'Passing installed upgrade cannot start a linked claim')
        state('hosted_core / prepare-direct', ('success',))
        funded = True
    elif (not same and not preclaim_run and wallet != 'success'
            and (initial['conclusion'] == 'cancelled' or (initial['conclusion'] == 'failure'
                 and (wallet == 'failure' or (wallet == 'skipped' and prepared == 'failure'))))):
        from funded_reuse import select

        funded = select(source, controller, metadata, root=root) is not None
    if not same:
        verify_delta(root, source, original, controller, funded=funded)
    if approved_upgrade:
        outputs['reuse_funded'] = 'true'
    elif funded and not core_run:
        outputs.update(funded_recovery_artifacts(root, source, history, metadata))
        outputs['reuse_funded'] = 'true'
    elif wallet == 'success':
        require(not preclaim_run, 'Passing wallet cannot start a linked claim')
    elif not core_run:
        missing_core = state('hosted_core') == 'skipped'
        stopped_before_wallet = (wallet == 'skipped' and reserve == 'skipped' and prepared == 'failure')
        require(not same and (missing_core or original_preclaim or stopped_before_wallet),
                'Original job did not reach the required boundary: wallet or qualified funded reuse')
        require(publisher == 'skipped' and not history.contains(*WALLET_RECEIPTS),
                'Wallet recovery requires a stopped publisher and no previous wallet receipt')
        if missing_core and not original_preclaim:
            prefixes = tuple(hosted_release_job_names(history.run['path'], 'hosted_core /'))
            require(not any(job['name'].startswith(prefixes) for job in history.jobs[1]),
                    'Skipped-wallet recovery requires no previous wallet execution')
        require(all(job['run_attempt'] == 1 for _, job in builds.values()),
                'Fresh wallet acceptance requires the original build attempt')
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
        outputs['run_missing_core'] = 'true'
    if same and not original_quote and quote_result == 'failure':
        if not quote_run and not own_quote_retry:
            outputs['same_controller_retry'] = 'true'
    elif same:
        require(original_quote, 'Same-controller quote retry requires the original transient quote failure')
    if original_quote:
        require(not quote_run, 'The original run already has a passing quote receipt')
        outputs.update(quote_receipt_id=history.retained('hosted-quote-receipt', QUOTE_JOBS[0])[0],
                       quote_controller=original, quote_run=int(run_id))
    if quote_run:
        outputs.update(resolve_quotes(root, source, controller, original, quote_run, metadata, original_run=run_id,
                                      retry_allowed=quote_result == 'failure', funded=funded,
                                      preclaim=bool(preclaim_run)))
    if own_quote_retry:
        outputs.update(resolve_quotes(root, source, controller, original, os.environ['GITHUB_RUN_ID'], metadata,
                                      original_run=run_id, retry_allowed=True, historical=True))
    if quote_result == 'failure' and not original_quote and (outputs.get('same_controller_retry')
                                                             or outputs.get('quote_controller') == original):
        require_transient_quote_failure(metadata, history.job(QUOTE_JOBS[0], 1))
    if signing and outputs.get('same_controller_retry'):
        same_controller_retry(source, controller, run_id, metadata, signing=True)
    verify_recovered_receipts(root, source, controller, run_id, outputs, metadata,
                             original_prepared=prepared, original_reserve=reserve)
    return outputs


def funded_recovery_artifacts(root, source, history, metadata):
    from hosted_claim import binding, ref_for, verify
    from release_inputs import version_code

    run = history.attempt(1)
    state = history.state
    cancelled = run['conclusion'] == 'cancelled'
    interrupted = ('cancelled', None) if cancelled else ()
    wallet = state('hosted_core / wallet', ('failure', 'skipped', *interrupted))
    reserve = state('hosted_core / reserve', ('success', 'skipped', *interrupted))
    state('publish', ('skipped', *interrupted))
    prepared = state('hosted_core / prepare-direct', ('success', 'failure', 'cancelled', 'skipped', None))
    require(not history.contains(*WALLET_RECEIPTS),
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
    candidates = [row for row in history.artifacts if row['name'] == 'core-direct-candidate']
    require(len(candidates) <= 1, 'Expected one original direct preparation artifact')
    if prepared == 'success' and candidates and candidates[0].get('expired') is False:
        result['prepared_id'] = history.retained('core-direct-candidate', first_attempt=True)[0]
    return result


def unused_reservation(root, source, controller, original_run, original, prior_run, metadata):
    from hosted_claim import binding, ref_for, verify
    from release_inputs import version_code

    require(re.fullmatch(r'[1-9][0-9]*', prior_run),
            'Exact prior pre-claim run required')
    run = metadata.api('/actions/runs/' + prior_run)
    prior = run['head_sha']
    require(trusted_producer(run, prior_run, maintenance=prior_run == original_run) and prior != controller
            and git(root, 'merge-base', prior, controller).decode().strip() == prior,
            'Prior pre-claim run does not match the trusted recovery controller')
    if prior_run == original_run:
        require(prior == original, 'Original pre-claim run has a different controller')
    else:
        verify_delta(root, source, original, prior)
    history = UnpublishedRun(metadata, run)
    initial = history.attempt(1)
    require(initial['conclusion'] == 'failure' and not history.contains(*WALLET_RECEIPTS),
            'Expected a failed first pre-claim attempt without a wallet receipt')
    reserve_name, wallet_name = 'hosted_core / reserve', 'hosted_core / wallet'
    for name, conclusion in (('admit_hosted', 'success'), (reserve_name, 'success'),
                             (wallet_name, 'failure'), ('publish', 'skipped')):
        history.state(name, (conclusion,))
    reserve = history.job(reserve_name, 1)
    for number in range(2, run['run_attempt'] + 1):
        require(history.state(wallet_name, attempt=number) in ('skipped', None),
                'A later attempt executed the prior wallet')
        later = history.job(reserve_name, number)
        if history.state(reserve_name, attempt=number) in ('skipped', None):
            continue
        require(copied_job_matches(reserve, later) and
                job_timestamp(initial.get('run_started_at')) <= job_timestamp(reserve.get('started_at')) <=
                job_timestamp(reserve.get('completed_at')) <
                job_timestamp(history.attempt(number).get('run_started_at')),
                'A later attempt executed a new funding reservation')
    wallet = history.job(wallet_name, 1)['id']
    log = subprocess.check_output([metadata.gh, 'api', '--allow-escape-sequences',
                                   metadata.repo + f'/actions/jobs/{int(wallet)}/logs'], timeout=30)
    failure_lines = re.findall(rb'(?m)^(?:[0-9][0-9T:.-]+Z )?Hosted lifecycle failed at ([^\r\n]+)\r?$', log)
    startup_failed = (len(failure_lines) == 1
                      and failure_lines[0] in (b'networkTargets, claim state no-POST',
                                               b'receiptProbe, claim state no-POST',
                                               b'rpcProbe, claim state no-POST',
                                               b'torModeSave, claim state no-POST',
                                               b'torModeResume, claim state no-POST')
                      and b'CORE_STAGE recipient passed' not in log and b'CORE_STAGE sender passed' not in log)
    readiness_failed = (failure_lines == [b'preclaim_readiness, claim state no-POST']
                        and b'CORE_STAGE sender passed' in log)
    budget_closed = (failure_lines == [b'funding_time_budget, claim state no-POST']
                     and b'CORE_STAGE sender passed' in log)
    require(len(log) <= 1024**2 and (startup_failed or readiness_failed or budget_closed)
            and b'CORE_STAGE fund passed' not in log,
            'Prior wallet did not prove failure before any funding request')
    value = binding(source, prior, version_code(root, source), int(prior_run), False)
    commit = metadata.api('/git/ref/' + ref_for(value).removeprefix('refs/'))['object']['sha']
    verify(value, commit, metadata)
    return commit


def stopped_recovery(run_id, metadata, *, historical=False):
    run = metadata.api('/actions/runs/' + run_id)
    require(trusted_producer(run, run_id, completed=not historical),
            'Expected the exact trusted recovery attempt before publication')
    history = UnpublishedRun(metadata, run)
    require(history.attempt(1)['conclusion'] in ('failure', 'cancelled', 'success'),
            'Expected a stopped initial recovery attempt')
    return history


def verify_receipt_delta(root, producer, controller, stage):
    if producer == controller:
        return
    require(git(root, 'merge-base', producer, controller).decode().strip() == producer,
            stage + ' receipt controller is not an ancestor of the signer')
    delta = git(root, 'diff', '--no-renames', '--name-only', '-z', producer, controller)
    paths = PUBLICATION_PATHS
    if stage == 'Quote':
        paths = paths | {'.private/prepare_direct_core.py'}
    elif stage == 'Wallet':
        paths = paths | {
            '.private/hosted_quote_probe.py', '.private/tor_resident_ui_driver.py',
            '.private/size_workflow_ui_scenario.py', '.github/workflows/hosted-acceptance-probe.yml',
            '.github/workflows/quote-bridge.yml',
        }
    require(set(filter(None, delta.decode().split('\0'))) <= paths,
            stage + ' execution inputs changed after the passing receipt')


def resolve_core(root, source, controller, original, core_run, metadata, *, original_history):
    require(re.fullmatch(r'[1-9][0-9]*', core_run), 'Exact wallet recovery run required')
    history = stopped_recovery(core_run, metadata)
    core_controller, state = history.run['head_sha'], history.state
    state('hosted_core / wallet', ('success',))
    reserve = state('hosted_core / reserve', ('success', 'skipped'))
    funded = reserve == 'skipped'
    verify_delta(root, source, original, core_controller, funded=funded)
    verify_receipt_delta(root, core_controller, controller, 'Wallet')
    receipt = 'hosted-upgrade-receipt' if funded else 'hosted-core-receipt'
    other = 'hosted-core-receipt' if funded else 'hosted-upgrade-receipt'
    require(not history.contains(other), 'Ambiguous wallet recovery receipts')
    result = dict(core_receipt_id=history.retained(receipt, first_attempt=True)[0],
                  core_run=int(core_run), core_controller=core_controller)
    prepared = state('hosted_core / prepare-direct', ('success', 'skipped', None) if funded else ('success',))
    if prepared == 'success':
        prepared_history = history
    else:
        require(original_history.state('hosted_core / prepare-direct') == 'success',
                'Prior upgrade requires a passing direct preparation job')
        prepared_history = original_history
    result.update(prepared_id=prepared_history.retained('core-direct-candidate', first_attempt=True)[0],
                  prepared_run=prepared_history.run['id'], prepared_controller=prepared_history.run['head_sha'])
    if funded:
        result['reuse_funded'] = 'true'
    return result


def resolve_quotes(root, source, controller, original, quote_run, metadata, *, original_run='', retry_allowed=False,
                   funded=False, historical=False, preclaim=False):
    require(re.fullmatch(r'[1-9][0-9]*', quote_run) and quote_run != original_run, 'Exact quote recovery run required')
    history = stopped_recovery(quote_run, metadata, historical=historical)
    quote_controller = history.run['head_sha']
    receipt, producer = history.retained('hosted-quote-receipt', *QUOTE_JOBS,
                                         first_attempt=historical or quote_controller == original)
    linked = producer['name'] in hosted_release_job_names(history.run['path'], QUOTE_JOBS[1])
    if quote_controller == original:
        require(retry_allowed and original_run and not linked,
                'Same-controller quote receipt requires the original transient quote failure')
        history.state('reserve_quote_retry', ('success',))
        verify_quote_reservation(source, original, original_run, quote_run, metadata)
    else:
        require(not historical, 'Signer rerun requires the original quote retry controller')
        verify_delta(root, source, original, quote_controller, funded=funded and not linked)
    if preclaim and linked:
        require(quote_controller != controller and producer['run_attempt'] == 1,
                'Linked claim requires the prior stopped quote recovery')
        history.state('hosted_core / prepare-direct', ('failure',))
        history.state('hosted_core / reserve', ('skipped',))
        history.state('hosted_core / wallet', ('skipped',))
        history.state('publish', ('skipped',))
    verify_receipt_delta(root, quote_controller, controller, 'Quote')
    return dict(quote_receipt_id=receipt, quote_controller=quote_controller, quote_run=int(quote_run))
