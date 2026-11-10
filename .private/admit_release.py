"""Admit prepared release jobs using the maintained batch runner and verified image."""
import argparse
import json
from pathlib import Path
import sys
from release_metadata import require

SOURCE = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prepared', type=Path, required=True)
    parser.add_argument('--owner', required=True)
    parser.add_argument('--profile', choices=('core', 'quotes'), action='append', required=True)
    parser.add_argument('--image', type=Path, help='Verified managed image; no modification or new cache')
    args = parser.parse_args()
    prepared = args.prepared.resolve()
    require(prepared.is_relative_to(SOURCE.parent / 'work'), 'Prepared inputs must belong to project work')
    require(1 <= len(args.profile) <= 2 and len(set(args.profile)) == len(args.profile), 'Unique selected profiles required')
    preparation = json.loads((prepared / 'preparation.json').read_text())
    require(len(args.profile) == 1 or preparation['execution_plan']['mode'] == 'parallel',
            'Prepared plan requires sequential admission; admit one profile at a time')
    import admit_emulator_batch as admission
    admission.PARENT = prepared
    admission.EVIDENCE.update({'prefunding-retries.json', 'quote-block-boundary.json', 'native-price-current.jsonl',
                               'quote-stages.json', 'quote-failure.png', 'quote-failure-state.json'})
    original_run = admission.guard.run
    for name in args.profile:
        job = json.loads((prepared / (name + '-job.json')).read_text())
        require(job['owner'] == args.owner, 'Prepared owner differs from admission owner')
    if args.image:
        from emulator_session import verify_image
        image = verify_image(args.image.resolve())
        # The worker is a separate process; use a maintained adapter with an explicit immutable image input.
        adapter = SOURCE / '.private/release_cached_batch.py'
        def cached_run(spec, *positional, **keywords):
            require(Path(spec['argv'][2]).resolve() == SOURCE / '.private/emulator_batch.py', 'Only maintained batch worker may use image adapter')
            spec['argv'][2] = str(adapter)
            spec['argv'].append(str(image))
            spec['sources'] += [str(adapter), str(image / '.codex-system-image.json')]
            spec['owner'] = args.owner
            return original_run(spec, *positional, **keywords)
        admission.guard.run = cached_run
    else:
        def owned_run(spec, *positional, **keywords):
            spec['owner'] = args.owner
            return original_run(spec, *positional, **keywords)
        admission.guard.run = owned_run
    argv = [sys.argv[0]]
    for name in args.profile:
        argv += ['--worker', name, str(prepared / (name + '-job.json')), str(prepared / (name + '-manifest.json'))]
    sys.argv = argv
    return admission.main()


if __name__ == '__main__':
    raise SystemExit(main())
