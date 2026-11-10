"""Choose sequential acceptance unless both current peers are proven stable.

This is a preparation decision. The existing batch launcher remains responsible
for exact manifest bindings and shared-guard admission at execution time.
"""
import re

GIB = 1024**3


def plan_execution(required_profiles, targeted_checks, stable_peers, available_memory_bytes):
    """Evidence maps contain each profile's current identity and passing identity.

    A peer row is {identity: SHA256, passed_identity: SHA256, passed: true}.
    A changed identity, missing evidence or failing diagnostic selects serial work.
    """
    profiles = list(required_profiles)
    if len(profiles) != len(set(profiles)) or any(p not in ('core-testnet', 'production-quotes') for p in profiles):
        raise ValueError('Unique supported acceptance profiles required')
    reasons = []
    if set(profiles) != {'core-testnet', 'production-quotes'}:
        reasons.append('Only one or no profile requires execution')
    for profile in profiles:
        if targeted_checks.get(profile) is not True:
            reasons.append(profile + ': targeted check has not passed')
        row = stable_peers.get(profile, {})
        identity = row.get('identity')
        if not (isinstance(identity, str) and re.fullmatch('[0-9a-f]{64}', identity)
                and row.get('passed_identity') == identity and row.get('passed') is True):
            reasons.append(profile + ': matching stable independent execution is missing')
    if type(available_memory_bytes) is not int or available_memory_bytes < 16*GIB:
        reasons.append('Two workers require at least 16 GiB available RAM')
    parallel = not reasons
    return dict(schema=1, mode='parallel' if parallel else 'sequential',
                profiles=profiles, max_concurrency=2 if parallel else min(1, len(profiles)),
                reasons=reasons, resource_admission_required=True,
                launcher='admit_emulator_batch.py' if parallel else 'single-worker',
                peer_failure_cancels_batch=True,
                real_concurrency='deferred to the next authorized candidate requiring both profiles')
