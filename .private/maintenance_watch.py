"""Read-only maintenance discovery. A shadow finding grants no promotion authority."""
import base64
from dataclasses import dataclass
import datetime as dt
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
from urllib.request import urlopen

from maintenance_cadence import window
from notify import PUBLICATION_OPERATIONS, blocker_issues, current_blockers, matches_recovery
from publication_attestation import ATTESTATION_ARTIFACT


REPOSITORY = 'Codely-Solutions/zec-private'
CONTROL_ROOTS = {'.github', '.private'}
DEPENDENCY_FILES = {'gradle.properties', 'gradle/verification-metadata.xml',
                    'gradle/wrapper/gradle-wrapper.properties', 'gradle/wrapper/gradle-wrapper.jar'}
GRADLE_LOCK_FILES = {'gradle.lockfile', 'buildscript-gradle.lockfile'}
WRAPPER_PROPERTIES = 'gradle/wrapper/gradle-wrapper.properties'
WRAPPER_VERSION = r'([1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)'
RELEASE_EVIDENCE = Path(__file__).resolve().parent / 'release-evidence'
PROPOSAL_PAGE_SIZE = 100
PROPOSAL_PAGE_LIMIT = 10


def authored_by_dependabot(pr):
    user = pr.get('user') or {}
    return user.get('login') == 'dependabot[bot]' and user.get('type') == 'Bot'


def trusted_proposal(pr, repository_id):
    return (pr['state'] == 'open' and not pr['draft']
            and authored_by_dependabot(pr)
            and pr['base']['ref'] == 'main' and pr['base']['repo']['id'] == repository_id
            and pr['head']['repo'] is not None and pr['head']['repo']['id'] == repository_id
            and pr['head']['ref'].startswith('dependabot/')
            and re.fullmatch(r'[0-9a-f]{40}', pr['head']['sha']) is not None)


def classify_paths(files, count):
    if not 0 < count <= 100 or len(files) != count:
        return 'incomplete diff'
    paths = [path for file in files for path in (file['filename'], file.get('previous_filename')) if path]
    if any(PurePosixPath(path).parts[0] in CONTROL_ROOTS for path in paths):
        return 'controller change requires review'
    if any(file['status'] != 'modified' for file in files):
        return 'file creation, removal or rename requires review'
    if any(path not in DEPENDENCY_FILES and PurePosixPath(path).name not in GRADLE_LOCK_FILES for path in paths):
        return 'change outside dependency declarations requires review'
    return 'dependency proposal needs provenance and acceptance'


def wrapper_patch(base, proposed, published_checksum):
    """Admit only the exact stable patch coordinates of an unchanged wrapper."""
    url = r'distributionUrl=https\\://services\.gradle\.org/distributions/gradle-' + WRAPPER_VERSION + r'-bin\.zip'
    checksum = r'distributionSha256Sum=([0-9a-f]{64})'

    def coordinates(text):
        lines = text.splitlines()
        versions = [match for line in lines if (match := re.fullmatch(url, line))]
        sums = [match[1] for line in lines if (match := re.fullmatch(checksum, line))]
        if len(versions) != 1 or len(sums) != 1:
            raise ValueError('Expected one stable Gradle version and one SHA-256')
        relevant = {'distributionUrl', 'distributionSha256Sum'}
        if any(line.lstrip().startswith(tuple(relevant)) and not (re.fullmatch(url, line) or re.fullmatch(checksum, line))
               for line in lines):
            raise ValueError('Ambiguous wrapper properties')
        template = [line.split('=', 1)[0] + '=' if line.split('=', 1)[0] in relevant else line for line in lines]
        return tuple(map(int, versions[0].groups())), sums[0], template

    old, _, original = coordinates(base)
    new, proposed_checksum, changed = coordinates(proposed)
    if new[:2] != old[:2] or new <= old or changed != original:
        raise ValueError('Only a stable patch with unchanged wrapper settings is eligible')
    version = '.'.join(map(str, new))
    if published_checksum(version) != proposed_checksum:
        raise ValueError('Proposed checksum differs from the official Gradle distribution')
    return version


def official_checksum(version):
    url = f'https://services.gradle.org/distributions/gradle-{version}-bin.zip.sha256'
    with urlopen(url, timeout=15) as response:
        value = response.read(128).decode('ascii').strip()
    if not re.fullmatch(r'[0-9a-f]{64}', value):
        raise ValueError('Gradle did not return a SHA-256')
    return value


def wrapper_source(prefix, sha):
    if not re.fullmatch(r'[0-9a-f]{40}', sha):
        raise ValueError('Exact wrapper source revision required')
    metadata = api(f'{prefix}/contents/{WRAPPER_PROPERTIES}?ref={sha}')
    if metadata['type'] != 'file' or metadata['encoding'] != 'base64' or not 0 < metadata['size'] <= 8192:
        raise ValueError('Bounded tracked wrapper properties required')
    data = base64.b64decode(''.join(metadata['content'].split()), validate=True)
    if len(data) != metadata['size']:
        raise ValueError('Wrapper metadata size mismatch')
    return data.decode('ascii')


def api(path, *options):
    return json.loads(subprocess.check_output(['gh', 'api', path, *options], text=True, timeout=30))


def open_proposals(prefix):
    proposals = {}
    for page in range(1, PROPOSAL_PAGE_LIMIT + 1):
        batch = api(f'{prefix}/pulls?state=open&sort=created&direction=asc'
                    f'&per_page={PROPOSAL_PAGE_SIZE}&page={page}')
        for proposal in batch:
            number = proposal['number']
            if number in proposals:
                raise RuntimeError('The proposal inventory changed during discovery. Retry the watch.')
            proposals[number] = proposal
        if len(batch) < PROPOSAL_PAGE_SIZE:
            return proposals
    raise RuntimeError('The complete proposal inventory exceeds the bounded discovery window.')


def proposal_inventory(prefix):
    proposals = open_proposals(prefix)
    if len(proposals) >= PROPOSAL_PAGE_SIZE:
        # Compare complete observed inventories before evaluating a multi-page result. An earlier
        # closure or reopen can shift offsets even when neither page contains a repeated number.
        current = open_proposals(prefix)
        if tuple(proposals) != tuple(current):
            raise RuntimeError('The proposal inventory changed during discovery. Retry the watch.')
        proposals = current
    return proposals.values()


class ProposalChanged(ValueError):
    """The candidate changed while the controller was verifying it."""


@dataclass(frozen=True)
class WrapperUpdate:
    version: str
    properties: str
    head: str
    number: int
    repository_id: int


def require_current_wrapper_proposal(prefix, number, base, head, repository_id):
    current = api(f'{prefix}/pulls/{number}')
    if (not trusted_proposal(current, repository_id) or current['head']['sha'] != head
            or current['base']['sha'] != base or current['changed_files'] != 1
            or api(f'{prefix}/commits/main')['sha'] != base):
        raise ProposalChanged('Proposal or main changed during verification; check discovery again')


def verified_wrapper(prefix, number, base, head, repository_id):
    detail = api(f'{prefix}/pulls/{number}')
    if (not trusted_proposal(detail, repository_id) or detail['head']['sha'] != head
            or detail['base']['sha'] != base or detail['changed_files'] != 1):
        raise ProposalChanged('Exact current Dependabot wrapper-only proposal required')
    files = api(f'{prefix}/pulls/{number}/files?per_page=2')
    if (len(files) != 1 or files[0]['filename'] != WRAPPER_PROPERTIES
            or files[0]['status'] != 'modified' or files[0].get('previous_filename')):
        raise ValueError('Wrapper-only proposal required')
    proposed = wrapper_source(prefix, head)
    version = wrapper_patch(wrapper_source(prefix, base), proposed, official_checksum)
    require_current_wrapper_proposal(prefix, number, base, head, repository_id)
    return version, proposed


def pending_recovery(prefix, evidence_dir=RELEASE_EVIDENCE, resolve=None):
    latest = api(f'{prefix}/releases/latest')
    tag = latest['tag_name']
    if not re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+-private\.[1-9][0-9]*', tag):
        raise ValueError('Unexpected latest wallet release identity')
    evidence = evidence_dir / (tag + '.json')
    recorded, blockers = None, []
    if evidence.is_file():
        recorded = evidence.read_bytes()
        proof = json.loads(recorded)
        from published_history import require_recorded_public_release

        require_recorded_public_release(latest, proof, lambda path: api(f'{prefix}/{path}'),
                                        evidence_dir.parent.parent)
        opened = blocker_issues(api(f'{prefix}/issues?state=open&per_page=100', '--paginate', '--slurp'))
        if len(opened) > 1:
            raise ValueError('Multiple open blocker issues require operator review')
        if opened:
            blockers = [state for state, _ in current_blockers(opened[0].get('body') or '').values()
                        if state['operation'] in PUBLICATION_OPERATIONS]
        if not blockers:
            return None
        from reconcile_release import lookup

        def resolve(run, attempt, artifact, *, target):
            result, attestation, _ = lookup(run, attempt, artifact, target=target)
            if (json.dumps(attestation, indent=2) + '\n').encode() != recorded:
                return None
            return result
    if resolve is None:
        from reconcile_release import resolve
    # Attestations survive later attempts of the same workflow run.
    matches = set()
    for page in range(1, 11):
        artifacts = api(f'{prefix}/actions/artifacts?per_page=100&page={page}')['artifacts']
        for artifact in artifacts:
            match = ATTESTATION_ARTIFACT.fullmatch(artifact['name'])
            if artifact['expired'] or match is None or (match[3] is not None and match[3] != tag):
                continue
            run_id = str(artifact['workflow_run']['id'])
            attempt, artifact_id = match[1], str(artifact['id'])
            if recorded is not None:
                recovery = dict(operation='reconciliation', candidate=f'publisher:{run_id}:{attempt}:{artifact_id}',
                                attestation=artifact_id)
                if not any(matches_recovery(state, recovery) for state in blockers):
                    continue
            try:
                proof = resolve(run_id, attempt, artifact_id, target=latest)
            except ValueError:
                continue
            if proof is not None and proof['release_tag'] == tag:
                matches.add((run_id, attempt, artifact_id))
        if len(artifacts) < 100:
            break
    else:
        raise ValueError('Publication proof search reached 1000 artifacts; recover the exact publisher run manually')
    if recorded is not None and not matches:
        return None
    if len(matches) != 1:
        raise ValueError('Unrecorded public release requires one live matching publication attestation')
    run, attempt, artifact = matches.pop()
    return dict(reconciliation_run=run, reconciliation_attempt=attempt, reconciliation_artifact=artifact)


def main():
    prefix = f'repos/{REPOSITORY}'
    repository = api(prefix)
    upstream = api(f'{prefix}/actions/workflows/sync-upstream.yml')
    runs = api(f'{prefix}/actions/workflows/sync-upstream.yml/runs?event=schedule&per_page=1')['workflow_runs']
    now = dt.datetime.now(dt.timezone.utc)
    age = ((now - dt.datetime.fromisoformat(runs[0]['created_at'])).total_seconds() / 3600
           if runs else None)
    status = ('missing' if age is None else f'{age:.1f}h ago')
    messages = ['## Maintenance watch', '', f'Upstream discovery is {upstream["state"]}. Last schedule {status}.']
    ready = repository['has_pull_requests'] and repository['pull_request_creation_policy'] == 'collaborators_only'
    if not ready:
        raise RuntimeError('Expected collaborator-only maintenance proposals')
    recovery = pending_recovery(prefix)
    if recovery:
        with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8') as stream:
            stream.writelines(f'{key}={value}\n' for key, value in recovery.items())
        with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a', encoding='utf-8') as stream:
            stream.write(f"Reconcile the public release using publisher run {recovery['reconciliation_run']}.\n")
        return
    eligible = []
    for pr in proposal_inventory(prefix):
        if not authored_by_dependabot(pr):
            continue
        number = pr['number']
        detail = api(f'{prefix}/pulls/{number}')
        if not trusted_proposal(detail, repository['id']):
            messages.append(f'- #{number} is not a current open Dependabot proposal for main')
            continue
        files = api(f'{prefix}/pulls/{number}/files?per_page=100') if detail['changed_files'] <= 100 else []
        current = api(f'{prefix}/pulls/{number}')
        if (not trusted_proposal(current, repository['id']) or current['head']['sha'] != detail['head']['sha']
                or current['base']['sha'] != detail['base']['sha']):
            raise ProposalChanged(f'Dependabot proposal #{number} changed during discovery. Retry the watch.')
        head = detail['head']['sha']
        finding = classify_paths(files, detail['changed_files'])
        if detail['changed_files'] == len(files) == 1 and files[0]['filename'] == WRAPPER_PROPERTIES \
                and files[0]['status'] == 'modified' and not files[0].get('previous_filename'):
            base = api(f'{prefix}/commits/main')['sha']
            if base != detail['base']['sha']:
                finding = 'proposal must rebase against current main'
            else:
                try:
                    version, _ = verified_wrapper(prefix, number, base, head, repository['id'])
                except ProposalChanged:
                    raise
                except ValueError as error:
                    finding = str(error)
                except OSError:
                    finding = 'official Gradle checksum unavailable'
                else:
                    if base == os.environ['GITHUB_SHA']:
                        cutoff = window(now)
                        date = api(f'{prefix}/commits/{head}')['commit']['committer']['date']
                        if (cutoff is not None and dt.datetime.fromisoformat(date) <= cutoff
                                and dt.datetime.fromisoformat(detail['created_at']) <= cutoff):
                            eligible.append((tuple(map(int, version.split('.'))), number, head))
                        finding = f'Gradle {version} wrapper coordinates verified; retained for monthly selection'
                    else:
                        finding = 'controller main advanced; wait for the next watch run'
        messages.append(f'- #{number} `{head}`: {finding}')
    messages.append('\nVerified proposals may prepare an isolated branch. Integration and release require acceptance.')
    with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a', encoding='utf-8') as stream:
        stream.write('\n'.join(messages) + '\n')
    if upstream['state'] != 'active':
        raise RuntimeError('Upstream discovery is disabled')
    if age is None or age > 8:
        print('Upstream discovery schedule is delayed. This run still checks current eligibility.', flush=True)
    if eligible:
        with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8') as stream:
            _, number, head = max(eligible)
            stream.write(f'proposal={number}\nproposal_head={head}\n')


if __name__ == '__main__':
    main()
