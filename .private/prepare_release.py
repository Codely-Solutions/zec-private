"""Prepare immutable release acceptance inputs; never launch, sign or publish."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys
from acceptance_policy import input_fingerprint
from acceptance_planning import plan_execution
from release_inputs import git, selected_baseline, version_code
from release_metadata import Metadata, require

SOURCE = Path(__file__).resolve().parents[1]


def prepare(source, sha, candidate_run, core_run, owner, out, *, baseline_path=None, metadata=None, planning=None, profiles=None):
    source, out = Path(source).resolve(), Path(out).resolve()
    require(source == SOURCE, 'Run helper from selected source checkout')
    require(re.fullmatch('[0-9a-f]{40}', sha), 'Exact source SHA required')
    require(git(source, 'rev-parse', 'HEAD') == sha and not git(source, 'diff', '--name-only', 'HEAD'),
            'Clean exact candidate source HEAD required')
    require(not git(source, 'ls-files', '--others', '--exclude-standard', '--', '.private', '.github'),
            'Commit maintained preparation/controller inputs before preparing acceptance')
    require(re.fullmatch('[a-z][a-z0-9-]{2,63}', owner), 'Explicit bounded resource owner required')
    task_root = source.parent / 'work'
    require(out.is_relative_to(task_root) and out != task_root and not out.exists(),
            'Fresh task-output directory beneath project work required')
    profiles = ['core', 'quotes'] if profiles is None else list(profiles)
    require(profiles and len(profiles) == len(set(profiles)) and set(profiles) <= {'core', 'quotes'}, 'Unique core/quotes selection required')
    require(type(candidate_run) is int and candidate_run > 0 and ('core' not in profiles or type(core_run) is int and core_run > 0),
            'Positive producer runs required')
    from acceptance_status import status
    acceptance = status(root=source, revision=sha)
    metadata = metadata or Metadata()
    _, production, production_proof = metadata.producer(candidate_run, sha, 'build-release.yml', 'unsigned', 'validated-unsigned-apk')
    ui, core = {}, {}
    ui_proof, core_proof = {}, {}
    if 'quotes' in profiles:
        _, ui, ui_proof = metadata.producer(candidate_run, sha, 'build-release.yml', 'emulator', 'isolated-ui-repro-apk')
    if 'core' in profiles:
        _, core, core_proof = metadata.producer(core_run, sha, 'core-validation.yml', 'testnet', 'core-testnet-candidate')
    baseline = selected_baseline(source, sha, baseline_path)
    bridge = json.loads(git(source, 'show', sha + ':.private/maintenance-assets.json'))['ui_bridge']
    for asset in (baseline['artifact'], baseline['instrumentation']['artifact'], bridge):
        metadata.verify_asset(asset)
    version = version_code(source, sha)
    require('core' not in profiles or version > baseline['version_code'], 'Candidate must advance the paired baseline version')
    fp = {p: input_fingerprint(sha, root=source, profile=p) for p in
          ('core-testnet', 'core-runtime', 'core-build', 'production-quotes')}
    paths = sorted(p.resolve() for p in (source / '.private').rglob('*.py'))
    paths += [source / p for p in ('.private/upgrade-baseline.json', '.private/maintenance-assets.json',
        '.private/core-network-policy.json', '.private/core-wallet/include.gradle',
        '.private/core-wallet/src/co/electriccoin/zcash/app/CoreWalletAcceptance.kt',
        '.github/workflows/build-release.yml', '.github/workflows/core-validation.yml')]
    common = dict(build_source_sha=sha, product_source_sha=sha, stable_candidate_run=candidate_run,
                  productionUnsignedArtifact=production, controller_source_sha=sha,
                  productionUnsignedProducer=production_proof)
    core.update(common, artifactProducer=core_proof, workflow_run_id=core_run, probeKind='core-testnet', package=baseline['package'],
        candidate_version_code=version, baseline_version_code=baseline['version_code'],
        baseline_source_sha=baseline['source_sha'], input_fingerprint=fp['core-testnet'],
        runtime_input_fingerprint=fp['core-runtime'], build_input_fingerprint=fp['core-build'],
        upgradeBaseline=dict(baseline['artifact'], source_sha=baseline['source_sha']),
        upgradeBaselineInstrumentation=dict(baseline['instrumentation']['artifact'],
            source_sha=baseline['source_sha'], apk_sha256=baseline['instrumentation']['apk_sha256']))
    recipes = ['.github/workflows/build-release.yml', '.private/emulator_checks.sh', '.private/tor-ui-abi.gradle',
               '.private/task-timing.gradle', '.private/donation-review/include.gradle', '.private/style-comparison/include.gradle']
    recipe_ids = dict(zip(recipes, git(source, 'rev-parse', *[sha + ':' + p for p in recipes]).splitlines()))
    ui.update(common, artifactProducer=ui_proof, workflow_run_id=candidate_run, probeKind='ui', package=baseline['package'].replace('.testnet', ''),
        expectedVersionCode=version, phase1Scenario=True, sizeWorkflowScenario=True, residentUiBridge=bridge,
        uiBuildInputs=dict(profile='production-quotes', input_fingerprint=fp['production-quotes'], recipes=recipe_ids))
    bindings = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
    # All verification precedes creating task outputs; remove only this fresh owned output on write failure.
    planning = planning or {}
    selected_profiles = [('core-testnet' if p == 'core' else 'production-quotes') for p in profiles]
    execution = plan_execution(selected_profiles, planning.get('targeted_checks', {}),
        planning.get('stable_peers', {}), planning.get('available_memory_bytes', 0))
    out.mkdir(parents=True)
    try:
        for name, manifest, seconds in [('core', core, 3000), ('quotes', ui, 1800)]:
            if name not in profiles:
                continue
            manifest['acceptanceRuntimeBindings'] = bindings
            path = out / (name + '-manifest.json')
            path.write_text(json.dumps(manifest, indent=2) + '\n')
            job = dict(owner=owner, purpose='Required isolated ' + name + ' acceptance',
                cleanup='Shared guard removes owned guest, downloads and helpers after retaining evidence',
                argv=[sys.executable, '-B', str(source / '.private/run_tor_emulator.py')], cwd=str(source),
                seconds=seconds, reserveBytes=8450000000, sources=[str(p) for p in paths] + [str(path)])
            (out / (name + '-job.json')).write_text(json.dumps(job, indent=2) + '\n')
        result = dict(prepared=True, source=sha, candidate_run=candidate_run, core_run=core_run,
                      baseline=baseline['release'], profiles=selected_profiles, execution_plan=execution,
                      acceptance_status=acceptance,
                      reason='Selected profiles prepared; reusable evidence and invalidation reasons are recorded',
                      launch=False, signing=False)
        (out / 'preparation.json').write_text(json.dumps(result, indent=2) + '\n')
        return result
    except BaseException:
        shutil.rmtree(out)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=SOURCE)
    parser.add_argument('--source-sha', required=True)
    parser.add_argument('--candidate-run', required=True, type=int)
    parser.add_argument('--core-run', type=int)
    parser.add_argument('--profile',choices=('core','quotes'),action='append')
    parser.add_argument('--owner', required=True)
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('--baseline', type=Path)
    parser.add_argument('--planning-evidence',type=Path, help='Matching targeted/stable identities and available memory; defaults sequential')
    parser.add_argument('--repo', default='Codely-Solutions/zec-private')
    args = parser.parse_args()
    print(json.dumps(prepare(args.source, args.source_sha, args.candidate_run, args.core_run, args.owner,
                             args.out, baseline_path=args.baseline, metadata=Metadata(repo=args.repo),
                             planning=json.loads(args.planning_evidence.read_text()) if args.planning_evidence else None, profiles=args.profile)))


if __name__ == '__main__':
    main()
