"""Resume an interrupted custom release on its original build."""
import os
import re

from custom_release import OPERATOR_ID, REPOSITORY, WORKFLOW, require_dispatch, verify_custom_child
from maintenance_cadence import git
from release_metadata import require

RECOVERY_PATHS = {
    '.github/workflows/build-release.yml', '.github/workflows/custom-release.yml',
    '.github/workflows/hosted-acceptance-probe.yml', '.github/workflows/sync-upstream.yml', '.private/quote_recovery.py',
    '.private/hosted_release.py', '.private/hosted_quote_probe.py', '.private/custom_release.py',
    '.private/publish.py', '.private/integrate_release.py', '.private/verify_public_release.py',
    '.private/tor_resident_ui_driver.py', '.private/size_workflow_ui_scenario.py',
    '.private/test_packaging.py', '.private/test_quote_recovery.py', '.private/RELEASE-WORKFLOW.md',
    '.github/workflows/hosted-core.yml', '.private/hosted_claim.py', '.private/hosted_core.py', '.private/hosted_fixture.py',
}

ARTIFACTS = {
    'production_id': 'validated-unsigned-apk', 'core_id': 'core-testnet-candidate',
    'quote_id': 'isolated-ui-repro-apk', 'core_receipt_id': 'hosted-core-receipt',
}


def verify_delta(root, source, original, controller):
    verify_custom_child(source, original, root)
    require(original != controller and git(root, 'merge-base', original, controller).decode().strip() == original,
            'Quote recovery requires a descendant controller')
    delta = git(root, 'diff', '--no-renames', '--name-only', '-z', original, controller)
    require(set(filter(None, delta.decode().split('\0'))) <= RECOVERY_PATHS,
            'Controller changed outside quote recovery paths')


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
            and run['conclusion'] in ('failure', 'success') and re.fullmatch(r'[a-f0-9]{40}', original),
            'Expected the original trusted custom run before publication')
    verify_delta(root, source, original, controller)
    jobs = metadata.pages(f'/actions/runs/{run_id}/attempts/{run["run_attempt"]}/jobs', 'jobs')
    required = {'build / admit_hosted': 'success', 'build / unsigned': 'success', 'build / emulator': 'success',
                'build / testnet_apk / testnet': 'success', 'build / publish': 'skipped'}
    for name, conclusion in required.items():
        matching = [job for job in jobs if job['name'] == name]
        require(len(matching) == 1 and matching[0]['conclusion'] == conclusion
                and matching[0]['run_id'] == int(run_id) and matching[0]['head_sha'] == original,
                'Original job did not reach the required boundary: ' + name)
    missing_core = any(job['name'] == 'build / hosted_core' and job['conclusion'] == 'skipped'
                       and job['run_id'] == int(run_id) and job['head_sha'] == original for job in jobs)
    states = {job['name']: job['conclusion'] for job in jobs
              if job['run_id'] == int(run_id) and job['head_sha'] == original}
    quote_result = states.get('build / hosted_quotes / quote-cycles')
    artifacts = metadata.pages(f'/actions/runs/{run_id}/artifacts', 'artifacts')
    outputs = {'original_controller': original}
    expected_artifacts = dict(ARTIFACTS)
    if missing_core:
        require(not any(job['name'].startswith('build / hosted_core /') for job in jobs)
                and not any(row['name'] == 'hosted-core-receipt' for row in artifacts)
                and not quote_run and quote_result in ('success', 'failure'),
                'Skipped-wallet recovery requires no previous wallet execution or receipt')
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
    for key, name in expected_artifacts.items():
        matching = [row for row in artifacts if row['name'] == name]
        require(len(matching) == 1, 'Expected one original artifact: ' + name)
        outputs[key] = str(artifact(metadata, matching[0]['id'], name, run_id, original)['id'])
    if quote_run:
        outputs.update(resolve_quotes(root, source, controller, original, quote_run, metadata))
    return outputs


def unused_reservation(root, source, controller, original_run, original, prior_run, metadata):
    from hosted_claim import binding, ref_for, verify
    from release_inputs import version_code
    import subprocess

    require(re.fullmatch(r'[1-9][0-9]*', prior_run) and prior_run != original_run,
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
    require(len(log) <= 1024**2 and b'Hosted lifecycle failed at networkTargets, claim state no-POST' in log
            and b'CORE_STAGE recipient passed' not in log and b'CORE_STAGE sender passed' not in log,
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
