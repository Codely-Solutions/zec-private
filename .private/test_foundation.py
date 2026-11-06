"""Small negative architecture fixtures supplement the compiled composition tests."""
import os
import shutil
import tempfile
import unittest
from pathlib import Path
from foundation_architecture import ROOT, check


class FoundationArchitectureTest(unittest.TestCase):
    def test_current_direction_and_rejected_engine_dependency(self):
        self.assertEqual([], check())
        with tempfile.TemporaryDirectory(dir=os.environ.get('CODEX_GUARD_OUTPUT')) as directory:
            root = Path(directory)
            for name in ('wallet-foundation-lib', 'ui-lib/src/main/java', 'app/src/main/java'):
                shutil.copytree(ROOT / name, root / name, ignore=shutil.ignore_patterns('build'))
            source = root / 'wallet-foundation-lib/src/main/kotlin/Forbidden.kt'
            source.write_text('import cash.z.ecc.android.sdk.model.Proposal\n')
            self.assertTrue(any('Concrete foundation import' in e for e in check(root)))
            source.unlink()
            build = root / 'wallet-foundation-lib/build.gradle.kts'
            build.write_text(build.read_text() + '\ndependencies { implementation(projects.uiLib) }\n')
            self.assertTrue(any('depends on a concrete' in e for e in check(root)))


if __name__ == '__main__':
    unittest.main()
