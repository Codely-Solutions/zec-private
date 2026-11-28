"""Resume an interrupted custom release on its original build."""
from datetime import datetime
import hashlib
import io
import json
import os
import re
import subprocess
import zipfile

from custom_release import OPERATOR_ID, REPOSITORY, WORKFLOW, require_dispatch, verify_custom_child
from maintenance_cadence import git
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

ARTIFACTS = {
    'production_id': 'validated-unsigned-apk', 'core_id': 'core-testnet-candidate',
    'quote_id': 'isolated-ui-repro-apk', 'core_receipt_id': 'hosted-core-receipt',
}
TRANSIENT_FAILURE = 'HOSTED_QUOTE_FAILURE transient'
RERUN_PRODUCERS = {
    'production_id': ('validated-unsigned-apk', 'build / unsigned'),
    'core_id': ('core-testnet-candidate', 'build / testnet_apk / testnet'),
    'quote_id': ('isolated-ui-repro-apk', 'build / emulator'),
    'quote_receipt_id': ('hosted-quote-receipt', 'build / hosted_quotes / quote-cycles'),
}


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


def verify_delta(root, source, original, controller, *, funded=False):
    verify_custom_child(source, original, root)
    require(original != controller and git(root, 'merge-base', original, controller).decode().strip() == original,
            'Quote recovery requires a descendant controller')
    delta = git(root, 'diff', '--no-renames', '--name-only', '-z', original, controller)
    paths = set(filter(None, delta.decode().split('\0')))
    if funded:
        require(paths <= FUNDED_RECOVERY_PATHS, 'Controller changed outside funded recovery paths')
        return
    changed = paths - RECOVERY_PATHS
    if changed:
        require(changed <= DIRECT_RECOVERY_PATHS, 'Controller changed outside quote recovery paths')
        from acceptance_policy import needs_live_tor_probe

        require(not needs_live_tor_probe(source, root), 'Controller changed outside quote recovery paths for a Tor candidate')


def rerun_before_wallet(root, source, controller, run, latest_jobs, metadata, *, signing, preclaim_run=''):
    """Start one fresh wallet execution after build reruns with no previous wallet entry."""
    from acceptance_policy import input_fingerprint, validate_quotes
    from funded_reuse import select
    from hosted_claim import PREFIX
    from hosted_release import artifact
    from release_inputs import version_code

    original = run['head_sha']
    require(signing or os.environ['GITHUB_RUN_ATTEMPT'] == '1',
            'Wallet recovery must start on a fresh dispatch')
    original_funded = select(source, original, metadata, root=root)
    if original_funded is not None:
        require(not preclaim_run, 'Funded reuse cannot use a pre-claim recovery reservation')
        verify_delta(root, source, original, controller, funded=True)
        require(select(source, controller, metadata, root=root) == original_funded,
                'Build rerun recovery must retain the original funded evidence and fixture')
    else:
        require(run['conclusion'] == 'failure', 'Successful build rerun requires qualified funded reuse')
        verify_delta(root, source, original, controller)
    run_id = str(run['id'])
    jobs_by_attempt = {}
    for number in range(1, run['run_attempt'] + 1):
        jobs = (latest_jobs if number == run['run_attempt'] else
                metadata.pages(f'/actions/runs/{run_id}/attempts/{number}/jobs', 'jobs'))
        require(jobs and all(job['run_id'] == run['id'] and job['head_sha'] == original
                             and job['run_attempt'] == number and job['status'] == 'completed' for job in jobs),
                'Original attempt has incomplete or unrelated jobs')
        names = {job['name']: job for job in jobs}
        require(len(names) == len(jobs) and names.get('build / admit_hosted', {}).get('conclusion') == 'success',
                'Original attempt needs one successful trusted admission')
        for role in ('reserve', 'wallet'):
            job = names.get('build / hosted_core / ' + role)
            require((job is None and number != run['run_attempt'])
                    or (job is not None and job['conclusion'] == 'skipped' and not job.get('steps')),
                    'Original attempt entered the wallet or reservation job')
        publisher = names.get('build / publish')
        require(publisher is not None and stopped_unpublished(publisher),
                'Original attempt does not prove signing and publication stopped')
        jobs_by_attempt[number] = names

    artifacts = metadata.pages(f'/actions/runs/{run_id}/artifacts', 'artifacts')
    require(not any(row['name'] in ('hosted-core-receipt', 'hosted-upgrade-receipt')
                    or row['name'].startswith('release-attestation-') for row in artifacts),
            'Original attempt already has wallet or publication evidence')
    outputs = dict(original_controller=original, quote_controller=original, quote_run=run['id'])
    outputs['reuse_funded' if original_funded is not None else 'run_missing_core'] = 'true'
    if preclaim_run:
        outputs['previous_reservation'] = unused_reservation(root, source, controller, run_id, original,
                                                             preclaim_run, metadata)
    if not signing or original_funded is not None:
        ref = PREFIX + 'release-' + str(version_code(root, source))
        reservations = metadata.api('/git/matching-refs/' + ref.removeprefix('refs/'))
        forbidden = (ref + '-preclaim-recovery',) if preclaim_run else (ref, ref + '-preclaim-recovery')
        require(not any(row['ref'] in forbidden for row in reservations),
                'This release already has a protected funding reservation')

    archives = {}
    for key, (name, job_name) in RERUN_PRODUCERS.items():
        match = [row for row in artifacts if row['name'] == name]
        require(len(match) == 1, 'Expected one original artifact: ' + name)
        row = match[0]
        verify_rerun_upload(row, (jobs.get(job_name) for jobs in jobs_by_attempt.values()))
        archives[key] = artifact(metadata, row['id'], name, run_id, original)
        outputs[key] = str(archives[key]['id'])
    if original_funded is not None:
        prepared = [row for row in artifacts if row['name'] == 'core-direct-candidate']
        require(len(prepared) <= 1, 'Expected at most one original direct preparation artifact')
        if prepared and prepared[0]['expired'] is False:
            row = prepared[0]
            verify_rerun_upload(row, (jobs.get('build / hosted_core / prepare-direct')
                                      for jobs in jobs_by_attempt.values()))
            outputs['prepared_id'] = str(artifact(metadata, row['id'], row['name'], run_id, original)['id'])
    if not signing:
        quote = read_quote_archive(metadata, archives['quote_receipt_id'])
        validate_quotes(quote, input_fingerprint(source, root=root, profile='production-quotes'), use_tor=False)
        require(quote.get('build_source_sha') == source and quote.get('candidate_version_code') == version_code(root, source)
                and quote.get('workflow_run_id') == run['id'] and quote.get('artifact_run_id') == run['id']
                and quote.get('quote_controller_sha') == original
                and quote.get('producer') == archives['quote_id']
                and quote.get('production_artifact') == archives['production_id'],
                'Rerun quote does not match the original release APKs')
    return outputs


def stopped_unpublished(publisher):
    if publisher['conclusion'] == 'skipped':
        return not publisher.get('steps')
    if publisher['conclusion'] != 'failure':
        return False
    steps = {step['name']: step for step in publisher.get('steps', ())}
    return (steps.get('Sign and verify the validated APK', {}).get('conclusion') in ('failure', 'skipped')
            and steps.get('Publish the signed APK and checksum', {}).get('conclusion') == 'skipped'
            and not any(step['conclusion'] == 'success' and step['name'].startswith('Run actions/upload-artifact@')
                        for step in publisher.get('steps', ())))


def verify_rerun_upload(archive, jobs):
    created = datetime.fromisoformat(archive['created_at'])
    executions = set()
    for job in jobs:
        if (job is None or job['conclusion'] != 'success'
                or type(job.get('runner_id')) is not int or job['runner_id'] < 1):
            continue
        for step in job.get('steps', ()):
            if (step['name'].startswith('Run actions/upload-artifact@') and step['conclusion'] == 'success'
                    and datetime.fromisoformat(job['started_at']) <= datetime.fromisoformat(step['started_at']) <=
                    created <= datetime.fromisoformat(step['completed_at']) <= datetime.fromisoformat(job['completed_at'])):
                # GitHub copies a successful job's runner and timestamps into later attempts.
                executions.add((job['runner_id'], job['started_at'], job['completed_at'], step['number']))
    require(len(executions) == 1, 'Artifact does not bind to one successful producer upload: ' + archive['name'])


def resolve(root, source, controller, run_id, metadata, quote_run='', *, signing=False, preclaim_run=''):
    from hosted_release import artifact

    require(re.fullmatch(r'[1-9][0-9]*', run_id), 'Exact original run required')
    require_dispatch(source, controller, metadata, publish=True, recover_run=run_id, quote_run=quote_run,
                     preclaim_run=preclaim_run)
    run = metadata.api('/actions/runs/' + run_id)
    original = run['head_sha']
    require(run['id'] == int(run_id) and run['path'] == WORKFLOW and run['event'] == 'workflow_dispatch'
            and run['head_branch'] == 'main' and run['head_repository']['full_name'] == REPOSITORY
            and run['actor']['id'] == OPERATOR_ID and run['status'] == 'completed'
            and run['conclusion'] in ('cancelled', 'failure', 'success') and re.fullmatch(r'[a-f0-9]{40}', original),
            'Expected the original trusted custom run before publication')
    same = original == controller
    if same:
        require(run['run_attempt'] == 1 and os.environ['GITHUB_RUN_ATTEMPT'] == '1'
                and not preclaim_run and not quote_run,
                'Same-controller recovery is limited to one original quote failure')
        verify_custom_child(source, original, root)
    jobs = metadata.pages(f'/actions/runs/{run_id}/attempts/{run["run_attempt"]}/jobs', 'jobs')
    states = {job['name']: job['conclusion'] for job in jobs
              if job['run_id'] == int(run_id) and job['head_sha'] == original}
    wallet = states.get('build / hosted_core / wallet')
    if (not same and run['run_attempt'] > 1
            and run['conclusion'] in ('failure', 'success') and wallet in (None, 'skipped')):
        require(not quote_run, 'Build rerun recovery uses the original passing quote receipt')
        return rerun_before_wallet(root, source, controller, run, jobs, metadata, signing=signing,
                                   preclaim_run=preclaim_run)
    funded = False
    if (not same and not preclaim_run and not quote_run and run['run_attempt'] == 1
            and (run['conclusion'] == 'cancelled' or (run['conclusion'] == 'failure' and wallet == 'failure'))):
        from funded_reuse import select

        funded = select(source, controller, metadata, root=root) is not None
    require(funded or run['conclusion'] != 'cancelled', 'Cancelled original requires qualified funded reuse')
    if not same:
        verify_delta(root, source, original, controller, funded=funded)
    required = {'build / admit_hosted': 'success', 'build / unsigned': 'success', 'build / emulator': 'success',
                'build / testnet_apk / testnet': 'success'}
    if not funded:
        required['build / publish'] = 'skipped'
    for name, conclusion in required.items():
        matching = [job for job in jobs if job['name'] == name]
        require(len(matching) == 1 and matching[0]['conclusion'] == conclusion
                and matching[0]['run_id'] == int(run_id) and matching[0]['head_sha'] == original,
                'Original job did not reach the required boundary: ' + name)
    missing_core = any(job['name'] == 'build / hosted_core' and job['conclusion'] == 'skipped'
                       and job['run_id'] == int(run_id) and job['head_sha'] == original for job in jobs)
    original_preclaim = preclaim_run == run_id
    quote_result = states.get('build / hosted_quotes / quote-cycles')
    artifacts = metadata.pages(f'/actions/runs/{run_id}/artifacts', 'artifacts')
    outputs = {'original_controller': original}
    expected_artifacts = dict(ARTIFACTS)
    if funded:
        expected_artifacts.pop('core_receipt_id')
        expected_artifacts.update(funded_recovery_artifacts(root, source, run, jobs, artifacts, metadata))
        outputs['reuse_funded'] = 'true'
        if 'quote_receipt_id' in expected_artifacts:
            outputs.update(quote_controller=original, quote_run=int(run_id))
    elif missing_core or original_preclaim:
        require(not same, 'Same-controller recovery requires a passing original wallet receipt')
        require(not any(row['name'] == 'hosted-core-receipt' for row in artifacts)
                and (not quote_run or bool(preclaim_run)) and quote_result in ('success', 'failure'),
                'Wallet recovery requires no previous wallet receipt')
        if not original_preclaim:
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
            require(not any(row['ref'] == ref for row in reservations),
                    'This release already has a protected funding reservation')
        expected_artifacts.pop('core_receipt_id')
        outputs['run_missing_core'] = 'true'
        if quote_result == 'success':
            require(not preclaim_run, 'Pre-claim recovery requires a fresh quote run')
            expected_artifacts['quote_receipt_id'] = 'hosted-quote-receipt'
            outputs.update(quote_controller=original, quote_run=int(run_id))
    else:
        require(not preclaim_run, 'Pre-claim recovery requires a skipped original wallet')
        require(states.get('build / hosted_core / wallet') == 'success',
                'Original job did not reach the required boundary: wallet')
        require(quote_result == 'failure' and run['conclusion'] == 'failure',
                'Original job did not reach the required boundary: quote failure')
        if same:
            matching = [job for job in jobs if job['name'] == 'build / hosted_quotes / quote-cycles'
                        and job['run_id'] == int(run_id) and job['head_sha'] == original]
            require(len(matching) == 1, 'Expected exactly one original quote job')
            require_transient_quote_failure(metadata, matching[0])
            outputs['same_controller_retry'] = 'true'
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
    if signing and same:
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
    wallet = state('hosted_core / wallet', ('failure', 'cancelled', 'skipped') if cancelled else ('failure',),
                   optional=cancelled)
    reserve = state('hosted_core / reserve', ('success', 'cancelled', 'skipped') if cancelled else ('success',),
                    optional=cancelled)
    state('publish', ('skipped', 'cancelled') if cancelled else ('skipped',), optional=cancelled)
    quote = state('hosted_quotes / quote-cycles', ('success',) if cancelled else ('success', 'failure'))
    prepared = state('hosted_core / prepare-direct', ('success', 'failure', 'cancelled', 'skipped'), optional=True)
    require(not any(row['name'] == 'hosted-core-receipt' or row['name'].startswith('release-attestation-')
                    for row in artifacts), 'Funded recovery requires no previous wallet receipt or attestation')

    value = binding(source, run['head_sha'], version_code(root, source), run['id'], False)
    ref = ref_for(value)
    reservations = metadata.api('/git/matching-refs/' + ref.removeprefix('refs/'))
    require(not any(row['ref'] == ref + '-preclaim-recovery' for row in reservations),
            'This release already has a linked funding reservation')
    existing = [row for row in reservations if row['ref'] == ref]
    if reserve == 'success' or wallet == 'failure' or existing:
        require(len(existing) == 1, 'Expected the original protected funding reservation')
        verify(value, existing[0]['object']['sha'], metadata)

    result = {}
    if quote == 'success':
        result['quote_receipt_id'] = 'hosted-quote-receipt'
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
    require(run['id'] == int(prior_run) and run['path'] == WORKFLOW and run['event'] == 'workflow_dispatch'
            and run['head_branch'] == 'main' and run['head_repository']['full_name'] == REPOSITORY
            and run['actor']['id'] == OPERATOR_ID and run['status'] == 'completed'
            and run['conclusion'] == 'failure' and run['run_attempt'] == 1
            and re.fullmatch(r'[a-f0-9]{40}', prior) and prior != controller
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
    publication_paths = {
        '.github/workflows/build-release.yml', '.github/workflows/custom-release.yml',
        '.github/workflows/sync-upstream.yml', '.private/custom_release.py',
        '.private/quote_recovery.py', '.private/hosted_release.py', '.private/publish.py',
        '.private/test_packaging.py', '.private/test_quote_recovery.py', '.private/RELEASE-WORKFLOW.md',
    }
    require(set(filter(None, delta.decode().split('\0'))) <= publication_paths,
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
    receipt = read_quote_archive(metadata, recorded)
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


def read_quote_archive(metadata, recorded):
    require(recorded['size_in_bytes'] <= 64 * 1024, 'Quote receipt archive is too large')
    archive = subprocess.check_output([metadata.gh, 'api', metadata.repo +
                                        f'/actions/artifacts/{recorded["id"]}/zip'], timeout=30)
    require(len(archive) == recorded['size_in_bytes'] and 'sha256:' + hashlib.sha256(archive).hexdigest() ==
            recorded['digest'], 'Linked quote receipt archive changed')
    with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
        require(bundle.namelist() == ['hosted-quotes.json'] and
                bundle.getinfo('hosted-quotes.json').file_size <= 64 * 1024, 'Unexpected linked quote receipt archive')
        return json.loads(bundle.read('hosted-quotes.json'))
