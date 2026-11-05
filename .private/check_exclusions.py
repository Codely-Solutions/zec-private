"""Reject reintroduced excluded integrations in source or resolved runtime evidence."""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def check(root=ROOT):
    policy = json.loads((root / '.private/excluded-features.json').read_text())
    failures = []
    for folder in ['app/src/main', 'ui-lib/src/main']:
        for path in (root / folder).rglob('*.kt'):
            text = path.read_text()
            for package in policy['forbidden_source_packages']:
                if any(line.startswith(('import ' + package, 'package ' + package)) for line in text.splitlines()):
                    failures.append(str(path.relative_to(root)) + ': excluded package ' + package)
    settings = (root / 'settings.gradle.kts').read_text()
    for project in policy['forbidden_projects']:
        if 'include("' + project + '")' in settings:
            failures.append('Excluded project included: ' + project)
    for path in [root / 'app/build.gradle.kts', root / 'ui-lib/build.gradle.kts']:
        if 'libs.flexa' in path.read_text():
            failures.append('Excluded Flexa dependency: ' + str(path.relative_to(root)))
    return failures


if __name__ == '__main__':
    errors = check()
    if len(sys.argv) > 1:
        evidence = json.loads(Path(sys.argv[1]).read_text())
        components = evidence['components']
        if not components or evidence['configuration'] != 'zcashmainnetFossReleaseRuntimeClasspath':
            errors.append('Missing release runtime graph')
        for component in components:
            if component.startswith('co.flexa:') or component == 'project :feature-voting':
                errors.append('Excluded runtime component: ' + component)
    if errors:
        raise SystemExit('\n'.join(errors))
    print('Excluded integration checks passed.')
