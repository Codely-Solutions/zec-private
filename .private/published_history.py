"""Resolve explicitly migrated public refs while keeping original artifact evidence."""
import json
from pathlib import Path
import re
import subprocess


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
