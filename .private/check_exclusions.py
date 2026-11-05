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
    for path in root.glob('*/build.gradle.kts'):
        text = path.read_text()
        if 'libs.flexa' in text or 'projects.featureVoting' in text:
            failures.append('Excluded integration dependency: ' + str(path.relative_to(root)))
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
        from compare_runtime import compare
        comparison=compare(evidence)
        print(json.dumps(comparison,sort_keys=True))
    if errors:
        raise SystemExit('\n'.join(errors))
    print('Excluded integration checks passed.')
