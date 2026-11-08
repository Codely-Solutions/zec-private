"""Prevent optional-feature removals from silently changing retained library versions."""
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]


def compare(candidate):
    baseline=json.loads((ROOT/'.private/phase1-runtime-baseline.json').read_text())
    def versions(components):
        return {s.rsplit(':',1)[0]:s.rsplit(':',1)[1] for s in components if s.count(':')==2}
    before=versions(baseline['components'])
    # Preserve the original removal baseline; reviewed upgrades are explicit expectations.
    for key, update in baseline.get('reviewedVersionUpdates', {}).items():
        if key not in before or before[key] != update['from']:
            raise RuntimeError('Invalid reviewed runtime upgrade: '+key)
        before[key] = update['to']
    after=versions(candidate['components'])
    changed={key:[old,after[key]] for key,old in before.items() if key in after and old!=after[key]}
    if changed:
        raise RuntimeError('Retained runtime versions changed; explicit upstream/dependency review required: '+json.dumps(changed))
    return {'baselineComponents':len(baseline['components']),'candidateComponents':len(candidate['components']),
            'removed':sorted(set(baseline['components'])-set(candidate['components'])),
            'added':sorted(set(candidate['components'])-set(baseline['components'])),
            'retainedVersionsUnchanged':True}
