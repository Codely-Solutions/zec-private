"""Select original funded evidence for a candidate with matching wallet behavior."""
import hashlib
import json
import re

from acceptance_policy import ROOT, git, input_manifest, validate_core_sources
from hosted_claim import binding, verify
from release_metadata import require

TAG = re.compile(r'v\d+\.\d+\.\d+-private\.\d+')
SHA = re.compile(r'[a-f0-9]{40}')
DIGEST = re.compile(r'[a-f0-9]{64}')

# Reviewed Release 27 transitions affect fiat refresh scheduling and presentation.
# Exact blobs keep later edits to these shared files in the funded comparison.
COMMON = 'ui-lib/src/main/java/co/electriccoin/zcash/ui/'
STRINGS = 'ui-design-lib/src/main/res/ui/common/'
REVIEWED_TRANSITIONS = {
    STRINGS + 'values-es/strings.xml': ('8c6292b3be38aa6be15aa686c3f04d77ab74619f', '802f01a94e8f865ed13db5e5be2bd5d036b76a38'),
    STRINGS + 'values/strings.xml': ('3ea9e1b99b07932d31718dd8278978f3bd8f443c', 'cefc11dce8e7b93690b62e7112d58535294b514d'),
    COMMON + 'common/repository/ExchangeRateRepository.kt': ('c4fa1b5f3a4d85fdab1098682e556942ac8c75ed', '27b4f2dc4df6654bd0a5f0cab456d480efae6345'),
    COMMON + 'common/wallet/StaleLock.kt': ('a0886229ebbd8ffe924fa7df93accb99dac5d3b8', '973a1ab81ee0207fb05363398aa1369e4fb0703c'),
    COMMON + 'screen/exchangerate/widget/StyledExchangeBalance.kt': ('dca6d245ca553afc9903294848aab9e525f4394b', 'f91cfeaa8741755ead6536f5c001451d82eb49fa'),
}


def wallet_inputs_match(original, source, root=ROOT):
    prior = input_manifest(original, root=root, profile='core-runtime')
    current = input_manifest(source, root=root, profile='core-runtime')
    for path in prior.keys() | current.keys():
        if path.startswith(('.private/', '.github/')) or prior.get(path) == current.get(path):
            continue
        reviewed = REVIEWED_TRANSITIONS.get(path)
        if not reviewed or (prior.get(path), current.get(path)) != tuple('100644 blob ' + item for item in reviewed):
            return False
    return True


def read_record(controller, tag, root):
    require(TAG.fullmatch(tag), 'Expected an exact integrated funded release tag')
    raw = git('show', f'{controller}:.private/release-evidence/{tag}.json', root=root)
    require(0 < len(raw) <= 256 * 1024, 'Release evidence exceeds its bound')
    record = json.loads(raw)
    require(record.get('release_tag') == tag and SHA.fullmatch(record.get('source_sha', ''))
            and DIGEST.fullmatch(record.get('apk_sha256', '')), 'Invalid protected release evidence')
    return record, dict(release=tag, sha256=hashlib.sha256(raw).hexdigest(), source=record['source_sha'])


def signing_identity(revision, root):
    script = git('show', revision + ':.private/verify_signing_key.sh', root=root)
    publisher = git('show', revision + ':.private/publish.py', root=root)
    certificates = re.findall(rb"^CERT='([a-f0-9]{64})'$", publisher, re.M)
    require(len(certificates) == 1, 'Exactly one pinned production signing certificate is required')
    return certificates[0], hashlib.sha256(script).digest()


def select(source, controller, metadata, root=ROOT):
    require(SHA.fullmatch(source) and SHA.fullmatch(controller), 'Expected exact candidate and controller')
    fixture = json.loads(git('show', controller + ':.private/hosted-fixture.json', root=root))
    latest, origin = read_record(controller, fixture['release'], root)
    pair = latest.get('installed_upgrade') or latest['core']
    require(latest['source_sha'] == fixture['source_sha'] and latest['versionCode'] == fixture['version_code']
            and pair['candidate_apk_sha256'] == fixture['candidate_apk_sha256']
            and pair['instrumentation_apk_sha256'] == fixture['instrumentation_apk_sha256'],
            'Selected upgrade fixture differs from integrated release evidence')
    record = latest
    if 'funded_reuse' in latest:
        record, origin = read_record(controller, latest['funded_reuse']['release'], root)
        require(latest['funded_reuse'] == origin and latest['core'] == record['core']
                and 'funded_reuse' not in record, 'Funded evidence must point to the original integrated receipt')

    if (not wallet_inputs_match(origin['source'], source, root)
            or not wallet_inputs_match(origin['source'], fixture['source_sha'], root)
            or signing_identity(record['controller_sha'], root) != signing_identity(controller, root)):
        return None

    core = record['core']
    require(core['build_source_sha'] == origin['source'] and core['candidate_version_code'] == record['versionCode']
            and record['real_funds_used'] is False and core['rehearsal'] is False
            and core['funding_setup']['claim_requests'] == 1
            and record['production_artifact'] == core['production_artifact']
            and core['artifact_id'] == core['producer']['id'], 'Funded receipt differs from its original release')
    prepared = core.get('direct_preparation')
    validate_core_sources(core, origin['source'], root=root, baseline=core['hosted_fixture'],
                          use_tor=core.get('tor_enabled', True), prepared=prepared)
    reservation = core['hosted_reservation']
    saved = json.loads(metadata.api('/git/commits/' + reservation)['message'])
    verify(binding(origin['source'], core['producer_controller_sha'], record['versionCode'], core['workflow_run_id'],
                   False, saved.get('previous')), reservation, metadata)
    public = metadata.api('/releases/tags/' + origin['release'])
    apks = [asset for asset in public['assets'] if asset['name'] == 'zec-private-latest.apk']
    require(public['tag_name'] == origin['release'] and public['draft'] is False and len(apks) == 1
            and apks[0].get('digest') == 'sha256:' + record['apk_sha256'],
            'Public funded release APK differs from the independently verified record')
    return dict(core=core, origin=origin, fixture=fixture)
