"""Fail early on a publication request lacking reusable real app acceptance."""
import hashlib
import json
import os
import subprocess
from pathlib import Path
from acceptance_policy import ROOT, input_fingerprint, validate_core, validate_quotes, validate_core_sources, changed_inputs


def status(root=ROOT, revision='HEAD'):
    """Inspect committed candidate inputs against unchanged receipt bytes."""
    root = Path(root)
    pending = []
    diagnostics = {}
    reusable = {}
    for profile, name, validate in (('core-testnet', 'core-acceptance.json', validate_core),
                                    ('production-quotes', 'quote-acceptance.json', validate_quotes)):
        path = root / '.private' / name
        receipt = {}
        try:
            fingerprint = input_fingerprint(revision, root=root, profile=profile)
            raw = path.read_bytes()
            receipt = json.loads(raw)
            if not isinstance(receipt, dict):
                raise ValueError('Acceptance receipt must be an object')
            if profile == 'core-testnet':
                validate_core_sources(receipt, product_revision=revision, root=root)
            else:
                validate(receipt, fingerprint)
                if input_fingerprint(receipt['build_source_sha'], root=root, profile=profile) != fingerprint:
                    raise ValueError('Executed quote build inputs differ')
            reusable[profile] = {
                'receipt_sha256': hashlib.sha256(raw).hexdigest(),
                'build_source_sha': receipt['build_source_sha'],
                'executed_apk_sha256': receipt['candidate_apk_sha256'] if profile == 'core-testnet'
                                      else receipt['executed_apk_sha256'],
                'input_fingerprint': fingerprint,
            }
        except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
            pending.append(profile)
            detail = {'reason': str(error)}
            previous = receipt.get('build_source_sha') if isinstance(receipt, dict) else None
            if previous:
                try:
                    detail['changed_inputs'] = changed_inputs(previous, revision, root=root, profile=profile)
                    detail['compared_source_sha'] = previous
                except (OSError, ValueError, subprocess.CalledProcessError):
                    detail['changed_inputs_unavailable'] = True
            diagnostics[profile] = detail
    return {'pending_acceptance': pending, 'publication_permitted': not pending,
            'acceptance_diagnostics': diagnostics, 'reusable_acceptance': reusable}


def main():
    report = status()
    print(json.dumps(report))
    pending = report['pending_acceptance']
    if pending and os.environ.get('VALIDATION_ONLY') != 'true':
        raise SystemExit('Real acceptance required before release preparation: '+', '.join(pending)+'. Discovery remains enabled; prepare an isolated candidate and record acceptance first.')


if __name__ == '__main__':
    main()
