"""Delete only reviewed superseded transient artifacts; dry-run by default.

Manifest schema: {"repository":"owner/repo", "references":[".private/core-acceptance.json"],
"candidates":[{"id":1,"name":"validated-unsigned-apk","size_in_bytes":1,
"digest":"sha256:...","run_id":2,"head_sha":"...","reason":"replacement accepted"}]}.
Reference paths are tracked repository files. Every live JSON manifest in .private is also
inspected. Run with a trusted checkout and a token restricted to this repository.
"""
import argparse
import base64
import hashlib
import json
import re
import subprocess
from pathlib import Path

NAMES = {'validated-unsigned-apk', 'isolated-ui-repro-apk', 'core-testnet-candidate', 'core-testnet-baseline'}


def gh(path, method='GET'):
    command = ['gh', 'api', '--method', method, path]
    output = subprocess.check_output(command, text=True, timeout=30)
    return json.loads(output) if output.strip() else None


def reference_snapshot(root, paths):
    tracked = set(subprocess.check_output(['git', 'ls-files'], cwd=root, text=True, timeout=30).splitlines())
    names = set(paths)
    names |= {p.relative_to(root).as_posix() for p in (root / '.private').glob('*.json')}
    snapshot = {}
    for name in sorted(names):
        path = root / name
        if name not in tracked or path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(root.resolve()):
            raise ValueError(f'Untracked, missing or unsafe current reference: {name}')
        data = path.read_bytes()
        snapshot[name] = (hashlib.sha256(data).hexdigest(), json.loads(data))
    if not snapshot:
        raise ValueError('No current reference manifests; preserve all artifacts')
    return snapshot


def live_references(api, repository, paths):
    """Read current default-branch manifests; unknown/missing ownership fails closed."""
    repo = api(f'repos/{repository}')
    branch = repo['default_branch']
    # Resolve one immutable tree, then check branch identity again before deletion.
    from urllib.parse import quote
    head = api(f'repos/{repository}/commits/{quote(branch, safe="")}')['sha']
    values = {}
    for path in paths:
        content = api(f'repos/{repository}/contents/{quote(path, safe="/")}?ref={head}')
        if content.get('encoding') != 'base64':
            raise ValueError('Unsupported live reference encoding')
        values[path] = json.loads(base64.b64decode(content['content'], validate=False))
    return head, values


def require_idle(api, repository):
    # A completed producer can still supply an active promotion/acceptance run.
    for page in range(1, 101):
        runs = api(f'repos/{repository}/actions/runs?per_page=100&page={page}')['workflow_runs']
        if any(run.get('status') != 'completed' for run in runs):
            raise ValueError('Repository has active/unknown runs; retry after completion')
        if len(runs) < 100:
            return
    raise ValueError('Run inventory exceeded bounded page limit; preserve artifacts')


def referenced(value, candidate, key=''):
    if isinstance(value, dict):
        return any(referenced(v, candidate, k) for k, v in value.items())
    if isinstance(value, list):
        return any(referenced(v, candidate, key) for v in value)
    # Conservatively preserve source/run matches even when a manifest omits artifact ID.
    identities = {str(candidate['id']), str(candidate['run_id']), candidate['digest'], candidate['head_sha']}
    return str(value) in identities


def validate_candidate(candidate, live, run):
    if not candidate.get('reason', '').strip() or candidate['name'] not in NAMES:
        raise ValueError('Candidate lacks reviewed supersession reason or known transient binary name')
    if not re.fullmatch(r'sha256:[a-f0-9]{64}', candidate['digest']):
        raise ValueError('Artifact digest is missing or invalid')
    if not re.fullmatch(r'[a-f0-9]{40}', candidate['head_sha']):
        raise ValueError('Source SHA is missing or invalid')
    for field in ('id', 'name', 'size_in_bytes', 'digest'):
        if live.get(field) != candidate[field]:
            raise ValueError(f'Artifact identity changed: {field}')
    binding = live.get('workflow_run') or {}
    if binding.get('id') != candidate['run_id'] or binding.get('head_sha') != candidate['head_sha']:
        raise ValueError('Artifact run/source binding changed')
    if run.get('id') != candidate['run_id'] or run.get('head_sha') != candidate['head_sha']:
        raise ValueError('Run identity changed')
    if run.get('status') != 'completed':
        raise ValueError('Artifact run is active or unknown')


def cleanup(manifest, root, execute=False, api=gh):
    repository = manifest['repository']
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('Invalid repository identity')
    remote = subprocess.check_output(['git', 'remote', 'get-url', 'origin'], cwd=root, text=True, timeout=30).strip()
    if remote not in (f'https://github.com/{repository}.git', f'https://github.com/{repository}', f'git@github.com:{repository}.git'):
        raise ValueError('Manifest repository does not match checkout origin')
    references = reference_snapshot(root, manifest['references'])
    receipt = {'repository': repository, 'dry_run': not execute, 'items': [], 'deleted_bytes': 0,
               'scope': 'Exact listed Actions artifacts; caches, releases, workflow history and billing unchanged'}
    candidates = manifest['candidates']
    if len({c['id'] for c in candidates}) != len(candidates):
        raise ValueError('Duplicate artifact identity')
    for candidate in candidates:
        item = {'id': candidate['id'], 'result': 'preserved'}
        receipt['items'].append(item)
        try:
            path = f'repos/{repository}/actions/artifacts/{int(candidate["id"])}'
            live = api(path)
            run_path = f'repos/{repository}/actions/runs/{int(candidate["run_id"])}'
            validate_candidate(candidate, live, api(run_path))
            current = reference_snapshot(root, manifest['references'])
            if current != references:
                raise ValueError('Current reference manifests changed during cleanup')
            if any(referenced(value, candidate) for _, value in current.values()):
                raise ValueError('Artifact, run, digest or source is currently referenced')
            remote_refs = live_references(api, repository, current)
            if any(referenced(value, candidate) for value in remote_refs[1].values()):
                raise ValueError('Artifact is referenced by current default-branch manifests')
            require_idle(api, repository)
            if execute:
                # Repeat live identity/state checks immediately before the exact DELETE.
                validate_candidate(candidate, api(path), api(run_path))
                if reference_snapshot(root, manifest['references']) != current:
                    raise ValueError('Current references changed before deletion')
                if live_references(api, repository, current) != remote_refs:
                    raise ValueError('Live current references changed before deletion')
                require_idle(api, repository)
                api(path, 'DELETE')
                item['result'] = 'deleted'
                receipt['deleted_bytes'] += candidate['size_in_bytes']
            else:
                item['result'] = 'would-delete'
        except (ValueError, KeyError, subprocess.SubprocessError) as error:
            item['reason'] = str(error)
    return receipt


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--execute', action='store_true')
    args = parser.parse_args()
    result = cleanup(json.loads(args.manifest.read_text()), Path(__file__).resolve().parents[1], args.execute)
    print(json.dumps(result, indent=2))
    if any(item['result'] == 'preserved' for item in result['items']):
        raise SystemExit(1)
