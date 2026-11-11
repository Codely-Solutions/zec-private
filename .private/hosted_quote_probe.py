"""Reuse the accepted quote APK for a hosted-only unfunded transport probe."""
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

from hosted_fixture import recorded_pair
from release_metadata import Metadata, require


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
    prepare(apks, root, SDK)
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
