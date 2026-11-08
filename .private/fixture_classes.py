"""Reject missing opt-in fixture code before upload; real launch remains required."""
import json
import re
import sys
import zipfile


def require_classes(apk, names):
    remaining = {('L' + name.replace('.', '/') + ';').encode() for name in names}
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.infolist():
            if re.fullmatch(r'classes(?:\d+)?\.dex', entry.filename):
                if entry.file_size > 128 * 1024 * 1024:
                    raise ValueError('Oversized fixture DEX entry')
                data = archive.read(entry)
                remaining = {name for name in remaining if name not in data}
    if remaining:
        raise ValueError('Missing compiled fixture classes: ' + repr(sorted(remaining)))
    return {'required_fixture_classes': names}


if __name__ == '__main__':
    if len(sys.argv) < 3:
        raise SystemExit('Usage: fixture_classes.py APK CLASS [CLASS ...]')
    print(json.dumps(require_classes(sys.argv[1], sys.argv[2:])))
