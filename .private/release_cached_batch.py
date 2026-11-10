"""Select an explicitly verified managed image for the maintained batch worker."""
import sys
from pathlib import Path
import emulator_batch
import emulator_session


def main():
    if len(sys.argv) != 3:
        raise ValueError('Explicit batch plan and verified image required')
    plan, image_path = sys.argv[1:]
    image = emulator_session.verify_image(Path(image_path))
    emulator_batch.prepare_image = lambda root, environ=None: image
    workers = emulator_batch.read_plan(plan)
    import os
    result = emulator_batch.execute(workers, os.environ['CODEX_GUARD_OUTPUT'])
    import json
    print(json.dumps(result))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
