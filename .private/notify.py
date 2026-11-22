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


def gh(*args):
    return subprocess.check_output(['gh', *args], text=True).strip()


def issue_body(repo, number, body):
    with tempfile.TemporaryDirectory(prefix='zec-notify-') as temp:
        path = Path(temp)/'body.txt'
        path.write_text(body, encoding='utf-8')
        return gh('issue', 'edit', str(number), '--repo', repo, '--body-file', str(path))


def current_state(body):
    match = re.search(re.escape(START)+r'\n<!-- state: (.+?) -->\n.*?'+re.escape(END), body, re.S)
    if match:
        try:
            value = json.loads(match[1])
            return value if isinstance(value, dict) else {}
        except (ValueError, TypeError):
            pass
    return {}


def with_current(body, state, message, diagnosis, run):
    section = (START+'\n<!-- state: '+json.dumps(state, sort_keys=True)+' -->\n'
               'Current blocker: '+message+'\n\n'+('Diagnosis: '+diagnosis+'\n\n' if diagnosis else '')+
               'Workflow evidence: '+run+'\n\nPublication and recovery status: see the linked workflow.\n'+END)
    pattern = re.escape(START)+r'.*?'+re.escape(END)
    if re.search(pattern, body, re.S):
        return re.sub(pattern, lambda _: section, body, count=1, flags=re.S)
    return body.rstrip()+('\n\n' if body.strip() else '')+section


def main(message):
    repo = os.environ['GITHUB_REPOSITORY']
    run = f'https://github.com/{repo}/actions/runs/{os.environ["GITHUB_RUN_ID"]}'
    diagnosis = os.environ.get('BLOCKER_DIAGNOSIS', '').strip()
    url = os.environ.get('NTFY_URL', '').strip()
    blocker = os.environ.get('MAKE_ISSUE') == 'true'
    recovery = os.environ.get('RECOVERY_VERIFIED') == 'true' and not blocker
    number, body, state = None, '', {}
    link = ''
    print('Workflow evidence: '+run, flush=True)
    if blocker or recovery:
        issues = json.loads(gh('issue', 'list', '--repo', repo, '--state', 'open',
                               '--search', TITLE+' in:title', '--json', 'number,title,body'))
        issues = [issue for issue in issues if issue['title'] == TITLE]
        if issues:
            number = issues[0]['number']
            body = issues[0].get('body') or ''
            state = current_state(body)
        if recovery:
            if number is not None:
                blocker_run = str(state.get('run', '')).rsplit('/', 1)[-1]
                if blocker_run.isdecimal() and int(blocker_run) > int(os.environ['GITHUB_RUN_ID']):
                    print('Preserved a blocker from a later workflow run.')
                else:
                    issue_body(repo, number, body+'\n\nVerified recovery: '+message+'\nWorkflow evidence: '+run+'\n')
                    gh('issue', 'close', str(number), '--repo', repo, '--reason', 'completed')
        else:
            fingerprint = hashlib.sha256((message+'\n'+diagnosis).encode()).hexdigest()
            delivered = state.get('fingerprint') == fingerprint and state.get('delivery') == 'accepted'
            state = dict(fingerprint=fingerprint, delivery='accepted' if delivered else 'pending', run=run)
            updated = with_current(body, state, message, diagnosis, run)
            if number is None:
                with tempfile.TemporaryDirectory(prefix='zec-notify-') as temp:
                    path = Path(temp)/'body.txt'
                    path.write_text(updated, encoding='utf-8')
                    created = gh('issue', 'create', '--repo', repo, '--title', TITLE, '--body-file', str(path))
                number = int(created.rstrip('/').rsplit('/', 1)[1])
            elif updated != body:
                issue_body(repo, number, updated)
            body = updated
            link = f'https://github.com/{repo}/issues/{number}'
            if delivered:
                print('Current blocker recorded; duplicate push suppressed.')
                return
    if not url:
        print('ntfy is not configured; current evidence remains in GitHub.')
        return
    text = message+(' Diagnosis: '+diagnosis if diagnosis else '')+' '+run+(' '+link if link else '')
    request = urllib.request.Request(url, data=text.encode(),
                                     headers={'Title': 'ZEC Private', 'Priority': 'default'}, method='POST')
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            if response.status != 200:
                raise RuntimeError('notification rejected')
    except Exception as error:
        raise SystemExit('ntfy notification failed: '+type(error).__name__) from None
    if blocker:
        state['delivery'] = 'accepted'
        issue_body(repo, number, with_current(body, state, message, diagnosis, run))
    print('Push notification accepted by ntfy; phone delivery requires subscription.')


if __name__ == '__main__':
    main(sys.argv[1])
