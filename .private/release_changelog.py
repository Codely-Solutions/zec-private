"""Validate release copy before building or signing."""
import json
import re

from maintenance_cadence import MARKER, changed_marker, git
from release_metadata import require

PATH = '.private/release-notes.json'


def changes_for(records, tag, *, required=False):
    changes = records.get(tag, [])
    require(isinstance(changes, list) and len(changes) <= 6
            and all(isinstance(item, str) and item.strip() and len(item) <= 300
                    and not any(ord(char) < 32 for char in item) for item in changes),
            'Release changes must contain up to six single-line entries')
    require(not required or changes, f'Add a user-facing changelog for {tag} in {PATH}')
    return changes


def custom_changes(root, source, controller, *, required=False):
    def read(revision, path):
        return git(root, 'show', f'{revision}:{path}').decode()

    original = json.loads(read(controller, PATH))
    proposed = json.loads(read(source, PATH))
    version = re.search(r'^ZCASH_VERSION_NAME=(\d+\.\d+\.\d+)$', read(source, 'gradle.properties'), re.M)
    require(version is not None, 'Expected a valid release version for the changelog')
    revision = json.loads(read(source, '.private/release-revision.json'))['private_revision']
    tag = f'v{version[1]}-private.{revision}'
    require({key: value for key, value in proposed.items() if key != tag}
            == {key: value for key, value in original.items() if key != tag},
            'A custom candidate may edit only its own changelog entry')
    return changes_for(proposed, tag, required=required)


def maintenance_changes(upstream_tag, maintenance, *, upstream_changed, wrapper_changed):
    if maintenance.get('kind') == 'security':
        return [f'Apply the security fix for {maintenance["advisory"]}.']
    changes = []
    if upstream_changed:
        changes.append(f'Update the upstream wallet to {upstream_tag}.')
    if wrapper_changed:
        changes.append('Update the Gradle build toolchain.')
    require(changes, 'No verified changes available for the maintenance changelog')
    return changes


def hosted_changes(root, source, controller):
    from custom_release import MARKER as CUSTOM_MARKER

    if changed_marker(root, source, controller, CUSTOM_MARKER):
        return custom_changes(root, source, controller, required=True)

    def read(revision, path):
        return json.loads(git(root, 'show', f'{revision}:{path}'))

    upstream_path = '.private/upstream.json'
    prior = read(controller, upstream_path)['tag']
    candidate = changed_marker(root, source, controller, '.private/candidate.json')
    upstream = candidate.get('tag') or read(source, upstream_path)['tag']
    revision = read(source, '.private/release-revision.json')['private_revision']
    tag = f'v{upstream.rsplit("-", 1)[0]}-private.{revision}'
    curated = changes_for(read(controller, PATH), tag)
    wrapper = git(root, 'diff', '--name-only', controller, source, '--', 'gradle/wrapper/gradle-wrapper.properties')
    return curated or maintenance_changes(upstream, changed_marker(root, source, controller, MARKER),
                                          upstream_changed=upstream != prior, wrapper_changed=bool(wrapper))
