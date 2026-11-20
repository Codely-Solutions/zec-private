"""Recover one checksum-pinned Kotlin configuration miss in the same Gradle job."""
import hashlib
from http.client import HTTPException
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.request import urlopen
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CENTRAL = 'https://repo.maven.apache.org/maven2/'
MISSING = re.compile(r'^\s*(?:> )?Could not find ([\w.-]+):([\w.-]+):([^\s:]+)\.$', re.M)


def run_logged(command):
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    captured = bytearray()
    overflow = False
    try:
        while chunk := process.stdout.read(8192):
            sys.stdout.buffer.write(chunk)
            sys.stdout.buffer.flush()
            if len(captured) + len(chunk) <= 512 * 1024:
                captured.extend(chunk)
            else:
                overflow = True
        return process.wait(), '' if overflow else captured.decode('utf-8', errors='replace')
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
        process.stdout.close()


def pinned_metadata(metadata):
    result = {}
    for component in ET.parse(metadata).findall('.//{*}component'):
        coordinate = tuple(component.get(key) for key in ('group', 'name', 'version'))
        if coordinate[0] != 'org.jetbrains.kotlin':
            continue
        for extension in ('pom', 'module'):
            for artifact in component.findall('{*}artifact'):
                if artifact.get('name') == f'{coordinate[1]}-{coordinate[2]}.{extension}':
                    hashes = {value.get('value') for value in artifact.findall('{*}sha256')}
                    if hashes and all(re.fullmatch('[a-f0-9]{64}', value or '') for value in hashes):
                        result[coordinate] = (extension, hashes)
            if coordinate in result:
                break
    return result


def recoverable(log, metadata):
    log = re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]', '', log)
    if (not re.search(r"Could not resolve all artifacts for configuration ':?classpath'\.", log)
            or 'A problem occurred configuring ' not in log):
        return {}
    if re.search(r'(?im)^> Task .* FAILED|Execution failed for task|Dependency verification failed|'
                 r'checksum|signature verification|lock state|lockfile|compilation error|'
                 r'Could not GET|Could not HEAD|timed out|Connection refused|SSL.*Exception', log):
        return {}
    missing = set(MISSING.findall(log))
    if not 1 <= len(missing) <= 8:
        return {}
    pins = pinned_metadata(metadata)
    if any(group != 'org.jetbrains.kotlin' or not re.fullmatch(r'\d+\.\d+\.\d+', version)
           or coordinate not in pins for coordinate in missing for group, _, version in [coordinate]):
        return {}
    return {coordinate: pins[coordinate] for coordinate in sorted(missing)}


def verify_metadata(coordinate, pin, timeout):
    group, name, version = coordinate
    base = CENTRAL + f'{group.replace(".", "/")}/{name}/{version}/{name}-{version}'
    extension, hashes = pin
    try:
        deadline = time.monotonic() + timeout
        for suffix in dict.fromkeys(('pom', extension)):
            url = base + '.' + suffix
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return False
            with urlopen(url, timeout=remaining) as response:
                data = response.read(512 * 1024 + 1)
                if response.status != 200 or response.geturl() != url or len(data) > 512 * 1024:
                    return False
                if suffix == extension and hashlib.sha256(data).hexdigest() not in hashes:
                    return False
        return True
    except (OSError, ValueError, HTTPException):
        return False


def run_with_recovery(command, *, metadata=ROOT / 'gradle/verification-metadata.xml',
                      runner=run_logged, verify=verify_metadata):
    code, log = runner(command)
    if code == 0 or any(value in command for value in ('--refresh-dependencies', '--offline',
                                                     '--write-locks', '--write-verification-metadata')):
        return code
    try:
        missing = recoverable(log, metadata)
    except (OSError, ET.ParseError):
        return code
    deadline = time.monotonic() + 30
    for coordinate, hashes in missing.items():
        remaining = deadline - time.monotonic()
        if remaining <= 0 or not verify(coordinate, hashes, min(10, remaining)):
            return code
    if not missing:
        return code
    print('Kotlin POMs available and pinned metadata verified; refreshing this Gradle command once.', flush=True)
    return runner([command[0], '--refresh-dependencies', *command[1:]])[0]


if __name__ == '__main__':
    command = sys.argv[1:]
    if command[:1] == ['--']:
        command = command[1:]
    if not command or Path(command[0]).name not in {'gradlew', 'gradlew.bat', 'gradle', 'gradle.bat'}:
        raise SystemExit('Pass the existing Gradle command after --')
    raise SystemExit(run_with_recovery(command))
