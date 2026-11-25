"""Resolve explicitly migrated public refs while keeping original artifact evidence."""
import hashlib
import json
from pathlib import Path
import re
import subprocess

from release_metadata import PUBLIC_APK_LIMIT, WALLET_APK, checksum_bytes, find_release_tag, require, require_public_version


ROOT = Path(__file__).resolve().parents[1]
RECORD = '.private/published-history-migration.json'
SHA = re.compile(r'[0-9a-f]{40}')


def migration(root=ROOT):
    path = Path(root) / RECORD
    if not path.is_file():
        return {}
    return json.loads(path.read_bytes())


def source_revision(original, root=ROOT):
    """Resolve only tracked bytes. Use the original SHA for receipt identities."""
    entry = migration(root).get('sources', {}).get(original)
    if entry is None:
        return original
    revised, tree = entry['commit'], entry['tree']
    if not SHA.fullmatch(revised) or not SHA.fullmatch(tree):
        raise ValueError('Invalid migrated source identity')
    actual = subprocess.check_output(
        ['git', '--no-replace-objects', 'rev-parse', '--verify', revised + '^{tree}'],
        cwd=root, timeout=30, text=True).strip()
    if actual != tree:
        raise ValueError('Migrated source tree changed')
    return revised


def claim_revision(original, *, ref=None, root=ROOT):
    entry = migration(root).get('claims', {}).get(original)
    if entry is None:
        return original
    if (ref is not None and entry['ref'] != ref) or not SHA.fullmatch(entry['commit']):
        raise ValueError('Invalid migrated reservation identity')
    return entry['commit']


def release_tag_matches(tag, source, apk_digest, release_id, ref, api, root=ROOT):
    if ref['type'] != 'commit':
        return False
    if ref['sha'] == source:
        return True
    migrated = migration(root).get('release', {})
    if (tag != migrated.get('tag') or source != migrated.get('source')
            or apk_digest != migrated.get('apk_sha256') or release_id != migrated.get('id')
            or ref['sha'] != migrated.get('commit')):
        return False
    return api('git/commits/' + ref['sha'])['tree']['sha'] == migrated['tree']



def require_recorded_public_release(live, proof, api, root=ROOT):
    tag = proof['release_tag']
    require(live['tag_name'] == tag and live['draft'] is False and live['prerelease'] is False,
            'Expected the recorded public stable release')
    require_public_version(live, proof['versionCode'], 'Latest public release version differs from its record')
    apk_digest = proof['apk_sha256']
    checksum = checksum_bytes(apk_digest)
    expected = {WALLET_APK: (apk_digest, PUBLIC_APK_LIMIT),
                'SHA256SUMS': (hashlib.sha256(checksum).hexdigest(), len(checksum))}
    assets = live['assets']
    require(len(assets) == len(expected) and {asset['name'] for asset in assets} == expected.keys()
            and all(asset['state'] == 'uploaded' and 0 < asset['size'] <= expected[asset['name']][1]
                    and asset.get('digest') == 'sha256:' + expected[asset['name']][0] for asset in assets),
            'Latest public release assets differ from the integrated release evidence')
    ref = find_release_tag(tag, api)
    require(ref is not None and release_tag_matches(tag, proof['source_sha'], apk_digest, live['id'], ref, api, root),
            'Latest public release tag differs from the attested upgrade source')
