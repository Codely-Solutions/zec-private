"""Run the standalone UI bridge with the product's reviewed verification metadata."""
import argparse
import os
from pathlib import Path
import tempfile

from gradle_resolution import ROOT, run_with_recovery


def stage_metadata(root):
    source = root / 'gradle/verification-metadata.xml'
    data = source.read_bytes()
    target = root / '.private/ui-bridge/gradle/verification-metadata.xml'
    target.parent.mkdir(parents=True, exist_ok=True)
    path = None
    try:
        with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as staged:
            path = Path(staged.name)
            staged.write(data)
        os.replace(path, target)
    finally:
        if path is not None:
            path.unlink(missing_ok=True)
    return source


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('task', choices=('assembleDebug', 'help'), default='assembleDebug', nargs='?')
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    metadata = stage_metadata(ROOT)
    wrapper = 'gradlew.bat' if os.name == 'nt' else 'gradlew'
    command = [str(ROOT / wrapper), '-p', str(ROOT / '.private/ui-bridge'),
               '--no-daemon', '--no-watch-fs', '--dependency-verification', 'strict']
    if args.offline:
        command.append('--offline')
    return run_with_recovery([*command, args.task], metadata=metadata)


if __name__ == '__main__':
    raise SystemExit(main())
