"""Bounded read-only verification of release producer and fixture identities."""
from datetime import datetime
import json,os,re,subprocess,time
GH=r"C:\Program Files\GitHub CLI\gh.exe" if os.name == 'nt' else 'gh'
MIB = 1024**2
ARCHIVE_LIMITS = {
    # Maximum compressed and expanded bytes for each artifact role.
    'validated-unsigned-apk': (64 * MIB, 192 * MIB),
    'core-testnet-candidate': (160 * MIB, 192 * MIB),
    'core-direct-candidate': (160 * MIB, 192 * MIB),
    'isolated-ui-repro-apk': (160 * MIB, 192 * MIB),
    'isolated-quote-bridge': (160 * MIB, 192 * MIB),
    'hosted-upgrade-receipt': (300 * 1024, 256 * 1024),
    'hosted-core-receipt': (300 * 1024, 256 * 1024),
    'hosted-quote-receipt': (64 * 1024, 64 * 1024),
    'release-attestation': (300 * 1024, 256 * 1024),
}
PUBLIC_APK_LIMIT = 96 * MIB
APK_PAYLOAD_LIMIT = 256 * MIB
UPSTREAM_DISCOVERY_WORKFLOW = '.github/workflows/sync-upstream.yml'


def hosted_release_job_names(workflow, job):
    """Keep original discovery evidence readable after moving execution behind ref admission."""
    original = 'build / ' + job
    if workflow == UPSTREAM_DISCOVERY_WORKFLOW:
        return {original, 'discover / ' + original}
    return {original}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def job_timestamp(value):
    require(isinstance(value, str), 'Missing GitHub artifact or job timestamp')
    parsed = datetime.fromisoformat(value)
    require(parsed.tzinfo is not None, 'GitHub artifact or job timestamp must include timezone')
    return parsed


def copied_job_matches(original, copy):
    fields = ('status', 'conclusion', 'started_at', 'completed_at', 'runner_id', 'steps')
    return (type(original.get('runner_id')) is int and original['runner_id'] > 0 and original.get('steps')
            and type(copy.get('id')) is int and copy['id'] > 0 and copy['id'] != original['id']
            and copy['run_attempt'] > original['run_attempt']
            and all(copy.get(field) == original.get(field) for field in fields))


def artifact_producer_job(run, jobs, asset, attempt, *, role, strict_timestamps=False, conclusions=('success',)):
    created = job_timestamp(asset.get('created_at'))
    matches = []
    for job in jobs:
        started = job_timestamp(job['started_at']) if job.get('started_at') else None
        completed = job_timestamp(job['completed_at']) if job.get('completed_at') else None
        if strict_timestamps and job.get('conclusion') == 'success':
            require(started is not None and completed is not None, 'Missing GitHub artifact or job timestamp')
        if started is not None and started <= created and (completed is None or created <= completed):
            matches.append((job, started, completed))
    ids = [job.get('id') for job, _, _ in matches]
    require(all(type(job_id) is int and job_id > 0 for job_id in ids) and len(ids) == len(set(ids)),
            f'Expected one record per matching {role} producer job')

    current = run['run_attempt']
    attempts = {current: run}
    producers, copies = [], []
    for job, started, completed in matches:
        number = job.get('run_attempt')
        require(type(number) is int and 1 <= number <= current, f'Invalid {role} producer attempt')
        if number not in attempts:
            attempts[number] = attempt(number)
        attempt_started = job_timestamp(attempts[number].get('run_started_at'))
        if started >= attempt_started:
            producers.append(job)
        else:
            require(completed is not None and completed < attempt_started,
                    f'{role.capitalize()} producer overlaps the start of its reported attempt')
            copies.append(job)

    producer_state = 'successful' if conclusions == ('success',) else 'completed'
    require(len(producers) == 1 and producers[0]['status'] == 'completed'
            and producers[0]['conclusion'] in conclusions and producers[0].get('completed_at'),
            f'Expected one {producer_state} {role} producer job when the selected artifact was uploaded')
    job = producers[0]
    if copies:
        # GitHub reruns can copy a successful job into a later attempt under
        # a new job ID. Its original execution finished before that attempt.
        require(all(copied_job_matches(job, copy) for copy in copies),
                f'Copied {role} producer does not match the original execution')
    return job


def wallet_release_versions(pages):
    versions = []
    for release in (item for page in pages for item in page):
        match = re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+-private\.([1-9][0-9]*)', release['tag_name'])
        if not match:
            continue
        codes = re.findall(r'^Android versionCode: ([1-9][0-9]*)[ \t]*\r?$', release.get('body') or '', re.M)
        require(len(codes) <= 1, 'Ambiguous published Android versionCode')
        versions.append((int(match[1]), int(codes[0]) if codes else 0))
    return versions


def next_release_version(versions, current_revision, current_code, upstream_build):
    revision = max([current_revision] + [item[0] for item in versions]) + 1
    code = max(max([current_code] + [item[1] for item in versions]) + 1, upstream_build * 1000 + revision)
    require(1 <= revision <= 999 and 1 <= code <= 2_100_000_000, 'Release version outside reviewed bounds')
    return revision, code


def reserved_release_versions(metadata):
    refs = metadata.api('/git/matching-refs/heads/testnet-claims/release-')
    pattern = r'refs/heads/testnet-claims/release-([1-9][0-9]*)(?:-preclaim-recovery)?'
    return sorted({(0, int(match[1])) for row in refs if (match := re.fullmatch(pattern, row['ref']))})


class Metadata:
    def __init__(self, repo="Codely-Solutions/zec-private", gh=GH, seconds=180):
        require(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo), "Invalid repository")
        require(0 < seconds <= 180, "Metadata deadline must be bounded")
        self.repo, self.gh = "repos/" + repo, gh
        self.deadline = time.monotonic() + seconds

    def api(self, path, paginated=False):
        remaining = self.deadline - time.monotonic()
        require(remaining > 0, 'GitHub metadata preparation deadline expired')
        command = [self.gh, 'api', self.repo + path]
        if paginated:
            command += ['--paginate', '--slurp']
        return json.loads(subprocess.check_output(command, text=True, timeout=min(45, remaining)))

    def pages(self, path, key):
        path += ('&' if '?' in path else '?') + 'per_page=100'
        return [item for page in self.api(path, True) for item in page[key]]

    def producer(self, run_id, source_sha, workflow, job_name, artifact_name, allow_pending=False):
        require(type(run_id) is int and run_id > 0 and re.fullmatch(r'[0-9a-f]{40}', source_sha),
                'Exact producer run and source SHA required')
        run = self.api('/actions/runs/' + str(run_id))
        require(run['id'] == run_id and run['path'] == '.github/workflows/' + workflow
            and run['head_branch'] == 'main' and run['event'] == 'workflow_dispatch'
            and run['head_sha'] == source_sha, 'Exact workflow/main/dispatch/source identity required')
        complete = run['status'] == 'completed' and run['conclusion'] == 'success'
        pending = allow_pending and run['status'] != 'completed' and run['conclusion'] is None
        require(complete or pending, 'Producer run must pass; only visual may precede production completion')
        artifacts = self.pages('/actions/runs/' + str(run_id) + '/artifacts', 'artifacts')
        matching_artifacts = [item for item in artifacts if item['name'] == artifact_name]
        require(len(matching_artifacts) == 1, 'Artifact name must be unambiguous')
        artifact = matching_artifacts[0]
        require(not artifact['expired'] and artifact['size_in_bytes'] > 0
            and re.fullmatch(r'sha256:[0-9a-f]{64}', artifact.get('digest') or '')
            and artifact['workflow_run']['id'] == run_id
            and artifact['workflow_run']['head_sha'] == source_sha, 'Artifact digest/run/source binding missing')
        jobs = self.pages('/actions/runs/' + str(run_id) + '/jobs?filter=all', 'jobs')
        matching = [job for job in jobs if job['name'] == job_name]
        require(all(job['run_id'] == run_id and job['head_sha'] == source_sha for job in matching),
                'Exact artifact producer job identity required')

        def attempt(number):
            value = self.api(f'/actions/runs/{run_id}/attempts/{number}')
            require(value['id'] == run_id and value['run_attempt'] == number and value['head_sha'] == source_sha,
                    'Artifact producer attempt identity mismatch')
            return value

        job = artifact_producer_job(run, matching, artifact, attempt, role=job_name, strict_timestamps=True)
        # The run's current conclusion controls admission. The proof binds the execution that uploaded these bytes.
        proof = dict(workflow=workflow, event=run['event'], head_branch=run['head_branch'],
            head_sha=source_sha, run_id=run_id, run_attempt=job['run_attempt'], status=job['status'],
            conclusion=job['conclusion'], producer_job_id=job['id'], producer_job_name=job_name,
            producer_job_conclusion=job['conclusion'], artifact_name=artifact_name)
        return run, {key: artifact[key] for key in ('id', 'digest', 'size_in_bytes')}, proof

    def verify_asset(self, binding):
        require(isinstance(binding, dict) and type(binding.get('release_asset_id')) is int
            and binding['release_asset_id'] > 0 and type(binding.get('size_in_bytes')) is int
            and binding['size_in_bytes'] > 0 and re.fullmatch(r'sha256:[0-9a-f]{64}', binding.get('digest', '')),
            'Verified maintenance asset binding required')
        live = self.api('/releases/assets/' + str(binding['release_asset_id']))
        require(live['id'] == binding['release_asset_id'] and live['size'] == binding['size_in_bytes']
            and live.get('digest') == binding['digest'] and live['state'] == 'uploaded',
            'Live maintenance asset differs from verified byte binding')
