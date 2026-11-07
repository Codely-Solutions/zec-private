"""Keep automated update proposals bounded and separate from signing."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]

class ToolchainPolicyTests(unittest.TestCase):
    def test_proposals_exclude_majors_and_runtime_dependency_sweep(self):
        config=(ROOT/'.github/dependabot.yml').read_text()
        self.assertIn('dependency-name: gradle-wrapper',config)
        self.assertEqual(config.count('version-update:semver-major'),2)
        self.assertNotIn('dependency-name: com.',config)
        self.assertIn('open-pull-requests-limit: 1',config)

    def test_pull_request_job_has_no_signing_or_write_credentials(self):
        workflow=(ROOT/'.github/workflows/toolchain-review.yml').read_text()
        self.assertIn('pull_request:',workflow)
        self.assertNotIn('pull_request_target:',workflow)
        self.assertNotIn('secrets.',workflow)
        self.assertNotIn('contents: write',workflow)
        self.assertNotIn('release create',workflow)
        self.assertIn('cache-read-only: true',workflow)
        self.assertIn('persist-credentials: false',workflow)

if __name__=='__main__':unittest.main()
