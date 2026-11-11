"""Reuse the accepted quote APK for a hosted-only unfunded transport probe."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

from hosted_fixture import recorded_pair
from release_metadata import Metadata, require


def stage_candidate(root, source, quote_id, production_id):
    from hosted_release import artifact, stage
    from release_inputs import version_code

    metadata = Metadata()
    producer = stage(root, quote_id, 'isolated-ui-repro-apk', metadata=metadata)
    production = artifact(metadata, production_id, 'validated-unsigned-apk',
                          os.environ['GITHUB_RUN_ID'], os.environ['GITHUB_SHA'])
    paths = list(root.glob('*.apk'))
    require(len(paths) == 1 and (root/'source-sha.txt').read_text().strip() == source,
            'Quote APK belongs to a different candidate')
    paths[0].rename(root/'quotes.apk')
    (root/'quote-manifest.json').write_text(json.dumps(dict(
        package='com.personal.zecprivate.foss.debug', expectedVersionCode=version_code(Path.cwd(), source),
        phase1Scenario=True, sizeWorkflowScenario=True, source=source, producer=producer,
        production_artifact=production)))


def stage_quote_apk(root, tag):
    evidence, _ = recorded_pair(tag)
    source = evidence['source_sha']
    quote = evidence['production_quotes']
    require(quote['passed'] is True and quote['funds_transferred'] is False
            and quote['personal_wallet_access'] is False and quote['build_source_sha'] == source,
            'Exact release with unfunded quote evidence required')
    metadata = Metadata()
    _, artifact, _ = metadata.producer(quote['workflow_run_id'], source, 'build-release.yml', 'emulator',
                                      'isolated-ui-repro-apk')
    require(artifact['id'] == quote['artifact_id'] and artifact['digest'] == 'sha256:' + quote['archive_sha256']
            and 0 < artifact['size_in_bytes'] <= 160 * 1024**2,
            'Quote producer differs from release evidence')
    with (root/'quote.zip').open('xb') as output:
        subprocess.run(['gh', 'api', metadata.repo + '/actions/artifacts/' + str(artifact['id']) + '/zip'],
                       stdout=output, timeout=120, check=True)
    path = root/'quote.zip'
    raw = path.read_bytes()
    require(len(raw) == artifact['size_in_bytes'] and 'sha256:' + hashlib.sha256(raw).hexdigest() == artifact['digest'],
            'Quote archive bytes differ from the executed producer')
    with zipfile.ZipFile(path) as archive:
        members = archive.infolist()
        apk = [member.filename for member in members if member.filename.endswith('.apk')]
        require(len(apk) == 1 and sum(member.file_size for member in members) <= 192 * 1024**2
                and archive.namelist().count('source-sha.txt') == 1
                and archive.read('source-sha.txt').decode().strip() == source,
                'Exact quote archive contents required')
        binary = archive.read(apk[0])
    require(hashlib.sha256(binary).hexdigest() == quote['original_apk_sha256'],
            'Quote APK differs from the original accepted executable')
    (root/'quotes.apk').write_bytes(binary)
    (root/'quote-manifest.json').write_text(json.dumps(dict(package='com.personal.zecprivate.foss.debug',
        expectedVersionCode=evidence['versionCode'], phase1Scenario=True, sizeWorkflowScenario=True)))
    path.unlink()


def run(session, root, fixture):
    from emulator_session import SDK
    from strip_emulator_apk import prepare
    from tor_resident_ui_driver import run as quote_scenario

    manifest = json.loads((fixture/'quote-manifest.json').read_text())
    apks = [fixture/name for name in ('quotes.apk', 'ui-bridge.apk')]
    prepared = prepare(apks, root, SDK)
    quote_scenario(session.adb, root, apks, manifest)
    receipt = json.loads((root/'tor-ui-result.json').read_text())
    scenario = receipt['phase1Scenario']
    require(receipt['passed'] is True and min(receipt['quoteRequests'], receipt['quoteReviewsVerified'],
                                             receipt['dismissals']) >= 3
            and scenario['recipientNetworkMismatchBlocked'] is True and scenario['correctedWithoutLeavingForm'] is True
            and scenario['independentComparisons'] > 0, 'Required hosted quote cycles and correction missing')
    print(json.dumps(dict(quote_reviews=receipt['quoteReviewsVerified'], dismissals=receipt['dismissals'],
                          independent_comparisons=scenario['independentComparisons'],
                          tor_enabled=receipt['torOptInVerified'], funded=False)))
    if 'source' in manifest:
        from acceptance_policy import input_fingerprint, validate_quotes
        source = manifest['source']
        result = dict(schema=1, evidence_class='real-app-production-quotes', passed=True,
                      build_source_sha=source, candidate_version_code=manifest['expectedVersionCode'],
                      input_fingerprint=input_fingerprint(source, profile='production-quotes'),
                      executed_apk_sha256=prepared['quotes.apk']['testedSha256'],
                      original_apk_sha256=prepared['quotes.apk']['originalSha256'],
                      quote_requests=receipt['quoteRequests'], quote_reviews=receipt['quoteReviewsVerified'],
                      dismissals=receipt['dismissals'], tor_enabled=receipt['torOptInVerified'],
                      funds_transferred=receipt['fundsTransferred'], personal_wallet_access=receipt['personalWalletAccess'],
                      recipient_network_mismatch_blocked=scenario['recipientNetworkMismatchBlocked'],
                      corrected_without_leaving_form=scenario['correctedWithoutLeavingForm'],
                      independent_comparisons=scenario['independentComparisons'], reference_states=scenario['referenceStates'],
                      production_artifact=manifest['production_artifact'], producer=manifest['producer'],
                      workflow_run_id=int(os.environ['GITHUB_RUN_ID']))
        validate_quotes(result, result['input_fingerprint'])
        (fixture/'hosted-quotes.json').write_text(json.dumps(result, indent=2) + '\n')
