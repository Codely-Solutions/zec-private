"""Read release inputs from the selected immutable source revision."""
import json
import re
import subprocess
from pathlib import Path
from release_metadata import require


def git(source, *args):
    return subprocess.check_output(['git', *args], cwd=source, text=True, timeout=30).strip()


def selected_baseline(source, sha, path=None):
    value = json.loads(git(source, 'show', sha + ':.private/upgrade-baseline.json'))
    if path is not None:
        require(json.loads(Path(path).read_text()) == value, 'Selected baseline differs from source manifest')
    require(value.get('schema') == 1 and re.fullmatch('[0-9a-f]{40}', value.get('source_sha', ''))
            and type(value.get('version_code')) is int and value['version_code'] > 0
            and value.get('package') == 'com.personal.zecprivate.testnet.foss.debug',
            'Paired baseline source/version/package required')
    instrumentation = value.get('instrumentation', {})
    require(re.fullmatch('[0-9a-f]{64}', instrumentation.get('apk_sha256', ''))
            and instrumentation.get('artifact', {}).get('source_sha') == value['source_sha']
            and instrumentation['artifact'].get('source_release') == value['release'],
            'Paired baseline instrumentation identity required')
    return value


def version_code(source, sha):
    match = re.search(r'^ZCASH_VERSION_CODE=(\d+)$', git(source, 'show', sha + ':gradle.properties'), re.M)
    require(match is not None, 'Source versionCode required')
    return int(match[1])
