"""Small negative architecture fixtures supplement the compiled composition tests."""
import os
import shutil
import tempfile
import unittest
import hashlib
import json
from pathlib import Path
from foundation_architecture import ROOT, check, check_graph, validate_graph_binding


class FoundationArchitectureTest(unittest.TestCase):
    def test_signing_rejects_missing_digest_or_substituted_graph(self):
        raw=json.dumps(dict(source_sha='expected',configuration='runtimeClasspath',
                            components=['project :wallet-foundation-lib'])).encode()
        proof=dict(foundation_graph_sha256=hashlib.sha256(raw).hexdigest())
        validate_graph_binding(raw,proof,'expected')
        for candidate,identity,source in ((raw,{},'expected'),(raw,proof,'other'),(raw+b' ',proof,'expected')):
            with self.assertRaises(ValueError):validate_graph_binding(candidate,identity,source)

    def test_resolved_graph_rejects_engine_and_product_dependencies(self):
        evidence=dict(configuration='runtimeClasspath', components=[
            'project :wallet-foundation-lib','org.jetbrains.kotlin:kotlin-stdlib:2.3.10'])
        check_graph(evidence)
        for forbidden in ('com.zodl.android:zcash-android-sdk:6.0.0','project :ui-lib', 'io.ktor:ktor-client-core:3.1.3'):
            with self.assertRaises(ValueError):
                check_graph(dict(evidence,components=evidence['components']+[forbidden]))

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
