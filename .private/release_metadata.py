"""Bounded read-only verification of release producer and fixture identities."""
import json,re,subprocess,time
GH=r"C:\Program Files\GitHub CLI\gh.exe"
def require(condition, message):
    if not condition:
        raise ValueError(message)

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
        jobs = self.pages('/actions/runs/' + str(run_id) + '/jobs?filter=latest', 'jobs')
        matching = [job for job in jobs if job['name'] == job_name]
        require(len(matching) == 1 and matching[0]['status'] == 'completed'
            and matching[0]['conclusion'] == 'success'
            and matching[0]['run_id'] == run_id
            and matching[0]['head_sha'] == source_sha, 'Exact artifact producer job must have succeeded')
        artifacts = self.pages('/actions/runs/' + str(run_id) + '/artifacts', 'artifacts')
        matching_artifacts = [item for item in artifacts if item['name'] == artifact_name]
        require(len(matching_artifacts) == 1, 'Artifact name must be unambiguous')
        artifact = matching_artifacts[0]
        require(not artifact['expired'] and artifact['size_in_bytes'] > 0
            and re.fullmatch(r'sha256:[0-9a-f]{64}', artifact.get('digest') or '')
            and artifact['workflow_run']['id'] == run_id
            and artifact['workflow_run']['head_sha'] == source_sha, 'Artifact digest/run/source binding missing')
        proof = dict(workflow=workflow, event=run['event'], head_branch=run['head_branch'],
            head_sha=source_sha, run_id=run_id, run_attempt=run['run_attempt'], status=run['status'],
            conclusion=run['conclusion'], producer_job_id=matching[0]['id'], producer_job_name=job_name,
            producer_job_conclusion='success', artifact_name=artifact_name)
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
