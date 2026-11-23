"""Resolve only a successful candidate from this repository's release workflow."""
import os
import re

from acceptance_policy import verify_promotion_revision
from release_metadata import Metadata

run_id=os.environ['CANDIDATE_RUN']
if not re.fullmatch(r'[1-9][0-9]*',run_id):
    raise SystemExit('Invalid candidate run')
metadata = Metadata(repo=os.environ['GITHUB_REPOSITORY'])
run = metadata.api(f'/actions/runs/{run_id}')
metadata.producer(int(run_id), run['head_sha'], 'build-release.yml', 'unsigned', 'validated-unsigned-apk')
# Only attestations and the dedicated AndroidTest Kotlin file may follow validation.
# Runtime, Gradle wiring, host policy, workflows and signing code remain unchanged.
verify_promotion_revision(run['head_sha'],os.environ['GITHUB_SHA'])
with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
    output.write('source_sha='+run['head_sha']+'\n')
