"""Resume a custom release after a quote-only failure on the original build."""
import os
import re

from custom_release import OPERATOR_ID, REPOSITORY, WORKFLOW, require_dispatch, verify_custom_child
from maintenance_cadence import git
from release_metadata import require

RECOVERY_PATHS = {
    '.github/workflows/build-release.yml', '.github/workflows/custom-release.yml',
    '.github/workflows/hosted-acceptance-probe.yml', '.private/quote_recovery.py',
    '.private/hosted_release.py', '.private/hosted_quote_probe.py', '.private/custom_release.py',
    '.private/publish.py', '.private/integrate_release.py', '.private/verify_public_release.py',
    '.private/tor_resident_ui_driver.py', '.private/size_workflow_ui_scenario.py',
    '.private/test_packaging.py', '.private/test_quote_recovery.py', '.private/RELEASE-WORKFLOW.md',
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


def resolve(root, source, controller, run_id, metadata, quote_run=''):
    from hosted_release import artifact

    require(re.fullmatch(r'[1-9][0-9]*', run_id), 'Exact original run required')
    require_dispatch(source, controller, metadata, publish=True, recover_run=run_id, quote_run=quote_run)
    run = metadata.api('/actions/runs/' + run_id)
    original = run['head_sha']
    require(run['id'] == int(run_id) and run['path'] == WORKFLOW and run['event'] == 'workflow_dispatch'
            and run['head_branch'] == 'main' and run['head_repository']['full_name'] == REPOSITORY
            and run['actor']['id'] == OPERATOR_ID and run['status'] == 'completed'
            and run['conclusion'] == 'failure' and re.fullmatch(r'[a-f0-9]{40}', original),
            'Original trusted custom run must have failed before publication')
    verify_delta(root, source, original, controller)
    jobs = metadata.pages(f'/actions/runs/{run_id}/attempts/{run["run_attempt"]}/jobs', 'jobs')
    required = {'review': 'success', 'build / unsigned': 'success', 'build / emulator': 'success',
                'build / testnet_apk / testnet': 'success', 'build / hosted_core / wallet': 'success',
                'build / hosted_quotes / quote-cycles': 'failure', 'build / publish': 'skipped'}
    for name, conclusion in required.items():
        matching = [job for job in jobs if job['name'] == name]
        require(len(matching) == 1 and matching[0]['conclusion'] == conclusion
                and matching[0]['run_id'] == int(run_id) and matching[0]['head_sha'] == original,
                'Original job did not reach the required boundary: ' + name)
    artifacts = metadata.pages(f'/actions/runs/{run_id}/artifacts', 'artifacts')
    outputs = {'original_controller': original}
    for key, name in ARTIFACTS.items():
        matching = [row for row in artifacts if row['name'] == name]
        require(len(matching) == 1, 'Expected one original artifact: ' + name)
        outputs[key] = str(artifact(metadata, matching[0]['id'], name, run_id, original)['id'])
    if quote_run:
        outputs.update(resolve_quotes(root, source, controller, original, quote_run, metadata))
    return outputs


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
        '.github/workflows/build-release.yml', '.github/workflows/custom-release.yml', '.private/custom_release.py',
        '.private/quote_recovery.py', '.private/hosted_release.py', '.private/publish.py',
        '.private/test_packaging.py', '.private/test_quote_recovery.py', '.private/RELEASE-WORKFLOW.md',
    }
    require(set(filter(None, delta.decode().split('\0'))) <= publication_paths,
            'Quote execution inputs changed after the passing receipt')
    jobs = metadata.pages(f'/actions/runs/{quote_run}/attempts/{run["run_attempt"]}/jobs', 'jobs')
    for name, conclusion in (('review', 'success'), ('build / admit_hosted', 'success'),
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
