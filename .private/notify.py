"""Record current blocker evidence and deduplicate delivered notifications."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import urllib.request

TITLE = 'ZEC Private update blocked: review required'
START = '<!-- zec-private-blocker-current -->'
END = '<!-- /zec-private-blocker-current -->'
SECTION = re.compile(re.escape(START)+r'\r?\n<!-- state: ([^\r\n]+) -->\r?\n'
                     r'(?:(?!'+re.escape(START)+r').)*?'+re.escape(END), re.S)
PUBLICATION_OPERATIONS = {'release', 'reconciliation'}
BLOCKER_OPERATIONS = PUBLICATION_OPERATIONS | {'discovery', 'validation'}


def gh(*args):
    return subprocess.check_output(['gh', *args], text=True, timeout=45).strip()


def issue_body(repo, number, body):
    with tempfile.TemporaryDirectory(prefix='zec-notify-') as temp:
        path = Path(temp)/'body.txt'
        path.write_text(body, encoding='utf-8', newline='')
        return gh('issue', 'edit', str(number), '--repo', repo, '--body-file', str(path))


def blocker_scope(state):
    return state.get('operation'), state.get('candidate'), state.get('validation_emulator', '')


def current_blockers(body):
    blockers = {}
    for match in SECTION.finditer(body):
        try:
            value = json.loads(match[1])
        except (ValueError, TypeError):
            continue
        if not isinstance(value, dict):
            continue
        scope = blocker_scope(value)
        operation, candidate, emulator = scope
        if (not isinstance(operation, str) or operation not in BLOCKER_OPERATIONS
                or not isinstance(candidate, str) or not candidate or emulator not in ('', 'false', 'true')
                or (operation != 'validation' and emulator)):
            continue
        if scope in blockers:
            raise RuntimeError('Duplicate blocker scope requires operator review')
        blockers[scope] = value, match
    return blockers


def state_json(state):
    return json.dumps(state, sort_keys=True).replace('<', r'\u003c').replace('>', r'\u003e')


def with_current(body, match, state, message, diagnosis, run):
    section = (START+'\n<!-- state: '+state_json(state)+' -->\n'
               'Current blocker: '+message+'\n\n'+('Diagnosis: '+diagnosis+'\n\n' if diagnosis else '')+
               'Workflow evidence: '+run+'\n\nPublication and recovery status: see the linked workflow.\n'+END)
    if match is not None:
        return body[:match.start()]+section+body[match.end():]
    return body+('\n\n' if body else '')+section


def run_order(state):
    # Use the previous state format's run link during migration.
    run_id = str(state.get('run', '')).rsplit('/', 1)[-1]
    attempt = state.get('attempt', 1)
    if re.fullmatch(r'[0-9]+', run_id) and type(attempt) is int:
        return int(run_id), attempt
    return 0, 0


def matches_recovery(state, revision):
    operation, candidate, emulator = blocker_scope(revision)
    previous_operation, previous_candidate, previous_emulator = blocker_scope(state)
    same_scope = (bool(candidate) and (operation, candidate) == (previous_operation, previous_candidate)
                  and (emulator == previous_emulator or (operation == 'validation' and emulator == 'true')))
    same_attestation = (revision['operation'] in PUBLICATION_OPERATIONS
                        and state['operation'] in PUBLICATION_OPERATIONS
                        and bool(revision['attestation']) and state.get('attestation') == revision['attestation'])
    return same_scope or same_attestation


def acknowledge(repo, number, revision):
    issue = json.loads(gh('issue', 'view', str(number), '--repo', repo, '--json', 'body,state'))
    body = issue.get('body') or ''
    if issue['state'] != 'OPEN':
        return
    state, match = current_blockers(body).get(blocker_scope(revision), ({}, None))
    if state == revision:
        # Preserve surrounding notes and text in the freshly fetched section.
        accepted = dict(revision, delivery='accepted')
        issue_body(repo, number, body[:match.start(1)]+state_json(accepted)+body[match.end(1):])


def main(message):
    repo = os.environ['GITHUB_REPOSITORY']
    run = f'https://github.com/{repo}/actions/runs/{os.environ["GITHUB_RUN_ID"]}'
    attempt = int(os.environ.get('GITHUB_RUN_ATTEMPT', '1'))
    evidence = f'{run}/attempts/{attempt}'
    diagnosis = os.environ.get('BLOCKER_DIAGNOSIS', '').strip()
    url = os.environ.get('NTFY_URL', '').strip()
    blocker = os.environ.get('MAKE_ISSUE') == 'true'
    recovery = os.environ.get('RECOVERY_VERIFIED') == 'true' and not blocker
    number, body = None, ''
    link = ''
    print('Workflow evidence: '+evidence, flush=True)
    if blocker or recovery:
        revision = dict(run=run, attempt=attempt, operation=os.environ['BLOCKER_OPERATION'],
                        candidate=os.environ['BLOCKER_CANDIDATE'],
                        attestation=os.environ.get('BLOCKER_ATTESTATION', ''))
        if revision['operation'] not in BLOCKER_OPERATIONS or not revision['candidate']:
            raise ValueError('Invalid blocker scope')
        if revision['operation'] == 'validation' and (emulator := os.environ.get('BLOCKER_VALIDATION_EMULATOR')):
            if emulator not in ('true', 'false'):
                raise ValueError('Invalid validation emulator scope')
            revision['validation_emulator'] = emulator
        pages = json.loads(gh('api', f'repos/{repo}/issues?state=open&per_page=100', '--paginate', '--slurp'))
        issues = [issue for page in pages for issue in page if issue['title'] == TITLE and 'pull_request' not in issue]
        if len(issues) > 1:
            raise RuntimeError('Multiple open blocker issues require operator review')
        if issues:
            number = issues[0]['number']
            body = issues[0].get('body') or ''
        blockers = current_blockers(body)
        if recovery:
            if number is not None:
                resolved = [match for state, match in blockers.values()
                            if run_order(revision) >= run_order(state) and matches_recovery(state, revision)]
                if resolved:
                    gh('issue', 'comment', str(number), '--repo', repo, '--body',
                       'Verified recovery: '+message+'\nWorkflow evidence: '+evidence)
                    if len(resolved) == body.count(START):
                        gh('issue', 'close', str(number), '--repo', repo, '--reason', 'completed')
                    else:
                        for match in reversed(resolved):
                            body = body[:match.start()]+body[match.end():]
                        issue_body(repo, number, body)
                else:
                    print('Preserved the open blocker because its operation or revision is unresolved.')
        else:
            state, match = blockers.get(blocker_scope(revision), ({}, None))
            if run_order(revision) < run_order(state):
                print('Preserved a blocker from a later workflow run or attempt.')
                return
            fingerprint = hashlib.sha256((message+'\n'+diagnosis).encode()).hexdigest()
            delivered = (state.get('attestation', '') == revision['attestation']
                         and state.get('fingerprint') == fingerprint and state.get('delivery') == 'accepted')
            revision.update(fingerprint=fingerprint, delivery='accepted' if delivered else 'pending')
            updated = with_current(body, match, revision, message, diagnosis, evidence)
            if number is None:
                with tempfile.TemporaryDirectory(prefix='zec-notify-') as temp:
                    path = Path(temp)/'body.txt'
                    path.write_text(updated, encoding='utf-8', newline='')
                    created = gh('issue', 'create', '--repo', repo, '--title', TITLE, '--body-file', str(path))
                number = int(created.rstrip('/').rsplit('/', 1)[1])
            elif updated != body:
                issue_body(repo, number, updated)
            link = f'https://github.com/{repo}/issues/{number}'
            if delivered:
                print('Current blocker recorded; duplicate push suppressed.')
                return
    if not url:
        print('ntfy is not configured; current evidence remains in GitHub.')
        return
    text = message+(' Diagnosis: '+diagnosis if diagnosis else '')+' '+evidence+(' '+link if link else '')
    request = urllib.request.Request(url, data=text.encode(),
                                     headers={'Title': 'ZEC Private', 'Priority': 'default'}, method='POST')
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            if response.status != 200:
                raise RuntimeError('notification rejected')
    except Exception as error:
        raise SystemExit('ntfy notification failed: '+type(error).__name__) from None
    if blocker:
        acknowledge(repo, number, revision)
    print('Push notification accepted by ntfy; phone delivery requires subscription.')


if __name__ == '__main__':
    main(sys.argv[1])
