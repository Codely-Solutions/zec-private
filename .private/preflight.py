"""Bounded, offline candidate checks before Java, Gradle or emulator setup."""
import ast
import json
import re
import subprocess
from pathlib import Path
from urllib.parse import unquote, urlsplit

from check import check
from sync import source_state

ROOT = Path(__file__).resolve().parents[1]


def git(root, *args):
    return subprocess.check_output(['git', *args], cwd=root, text=True, timeout=30)


# Only current, maintained entry points: inherited/generated/history links have other semantics.
ACTIVE_DOCS = ('README.md', '.private/MAINTENANCE.md', '.private/UI-REVIEW.md', '.private/DEVELOPMENT.md',
               '.private/core-coverage.md', '.private/emulator-batch.md', '.private/RELEASE-WORKFLOW.md',
               'docs/DONATIONS.md', 'docs/WALLET-GUIDE.md', 'docs/FOUNDATION.md',
               'docs/PRIVACY.md', 'docs/USE.md', '.private/SWAP-AMOUNTS.md')


def documentation_links(root, documents=ACTIVE_DOCS):
    errors = []
    for name in documents:
        path = root / name
        if not path.is_file():
            errors.append(f'{name}: maintained document missing')
            continue
        text = path.read_text(encoding='utf-8')
        text = re.sub(r'```.*?```', '', text, flags=re.DOTALL)
        for target in re.findall(r'!?\[[^\]\n]*\]\(([^)]+)\)', text):
            target = target.strip().strip('<>')
            url = urlsplit(target)
            if url.scheme or url.netloc or not url.path:
                continue
            local = unquote(url.path)
            destination = root / local.lstrip('/') if local.startswith('/') else path.parent / local
            if not destination.exists():
                errors.append(f'{name}: missing local documentation target {local}')
    return errors


def controller_checks(root):
    errors = []
    for path in sorted((root / '.private').glob('*.py')):
        try:
            ast.parse(path.read_text(encoding='utf-8'), filename=str(path))
        except SyntaxError as error:
            errors.append(f'{path.relative_to(root)}:{error.lineno}: {error.msg}')
    for path in sorted((root / '.github/workflows').glob('*.y*ml')):
        text = path.read_text(encoding='utf-8')
        # Check concrete repository script bindings, without pretending to parse YAML.
        for binding in re.findall(r'(?<![\w/])\.private/[\w./-]+\.(?:py|sh)', text):
            if not (root / binding).is_file():
                errors.append(f'{path.name}: missing local script {binding}')
    return errors


def declaration_name_errors(path, name):
    """Catch the recurring single-class/view filename mistake; Detekt remains authoritative."""
    text = path.read_text()
    if '"MatchingDeclarationName"' in text:
        return []
    # Compose files commonly pair the named top-level function with a state/defaults class.
    if re.search(r'^(?:(?:internal|private|public)\s+)?fun\s+' + re.escape(path.stem) + r'\s*\(',
                 text, re.MULTILINE):
        return []
    names = re.findall(
        r'^(?:(?:internal|private|public|protected|data|sealed|open|abstract|enum|annotation|value|expect|actual)\s+)*'
        r'(?:class|interface|object)\s+(\w+)', text, re.MULTILINE)
    if len(names) == 1 and path.stem != names[0]:
        return [f'{name}: single top-level declaration {names[0]} requires a matching filename']
    return []


def changed_kotlin(root, base):
    """Catch the long changed declarations that previously cost a CI iteration."""
    errors = []
    paths = git(root, 'diff', '--name-only', '-z', base, '--', '*.kt', '*.kts').split('\0')
    for name in filter(None, paths):
        if not re.search(r'/src/(?:main|test)/.*\.kt$', name):
            continue  # Match production/unit-test sources, excluding private instrumentation and scripts.
        path = root / name
        if not path.is_file():
            continue
        errors.extend(declaration_name_errors(path, name))
        diff = git(root, 'diff', '--unified=0', base, '--', name)
        line = 0
        for value in diff.splitlines():
            if value.startswith('@@'):
                line = int(re.search(r'\+(\d+)', value).group(1)) - 1
            elif value.startswith('+') and not value.startswith('+++'):
                line += 1
                if len(value[1:]) > 120:
                    errors.append(f'{name}:{line}: changed Kotlin line exceeds 120 columns')
            elif value.startswith(' '):
                line += 1
    return errors


def run(root=ROOT, base='HEAD'):
    errors = controller_checks(root)
    errors.extend(documentation_links(root))
    if git(root, 'rev-parse', '--is-shallow-repository').strip() != 'false':
        errors.append('Checkout is shallow; use fetch-depth: 0 before acceptance selection')
    git(root, 'rev-parse', '--verify', base + '^{commit}')
    expected = json.loads((root / '.private/source-state.json').read_text())
    if source_state(root) != expected:
        errors.append('Source differs from source-state.json; refresh reviewed fork.patch and normalized state together')
    try:
        git(root, 'apply', '--numstat', str(root / '.private/fork.patch'))
    except subprocess.CalledProcessError:
        errors.append('fork.patch is malformed; regenerate it from reviewed source changes')
    errors.extend(changed_kotlin(root, base))
    errors.extend(check(root))
    return errors


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True, help='reviewed comparison commit for changed Kotlin lines')
    args = parser.parse_args()
    failures = run(base=args.base)
    if failures:
        raise SystemExit('\n'.join(failures))
    print('Offline preflight passed; compilation, full deterministic tests and acceptance remain required.')
