"""Fail early on a publication request lacking reusable real app acceptance."""
import json
import os
from pathlib import Path
from acceptance_policy import input_fingerprint, validate_core, validate_quotes, validate_core_sources

root=Path(__file__).resolve().parents[1]
pending=[]
for profile,name,validate in (('core-testnet','core-acceptance.json',validate_core),
                              ('production-quotes','quote-acceptance.json',validate_quotes)):
    path=root/'.private'/name
    fingerprint=input_fingerprint(profile=profile)
    try:
        receipt=json.loads(path.read_text())
        validate(receipt,fingerprint)
        if profile=='core-testnet':
            validate_core_sources(receipt)
        elif input_fingerprint(receipt['build_source_sha'],profile=profile)!=fingerprint:
            raise ValueError('Executed quote build inputs differ')
    except (OSError,ValueError,KeyError):
        pending.append(profile)
print(json.dumps({'pending_acceptance':pending,'publication_permitted':not pending}))
if pending and os.environ.get('VALIDATION_ONLY')!='true':
    raise SystemExit('Real acceptance required before release preparation: '+', '.join(pending)+'. Discovery remains enabled; prepare an isolated candidate and record acceptance first.')
