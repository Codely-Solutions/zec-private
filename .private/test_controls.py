import contextlib, importlib.util, json, os, tempfile, unittest
from pathlib import Path
import shutil

spec=importlib.util.spec_from_file_location('forkcheck',Path(__file__).with_name('check.py'))
checks=importlib.util.module_from_spec(spec);spec.loader.exec_module(checks)

@contextlib.contextmanager
def fixture_directory():
    # The native guard scans its output while the child runs. Leave task-owned
    # fixture deletion to its final cleanup, avoiding a scan/delete race.
    guarded=os.environ.get('CODEX_GUARD_OUTPUT')
    if guarded:
        yield tempfile.mkdtemp(prefix='controls-',dir=guarded)
    else:
        with tempfile.TemporaryDirectory() as temp:
            yield temp

class ControlsTest(unittest.TestCase):
    def test_current_source(self):
        self.assertEqual([],checks.check())

    def test_fee_and_auth_regressions_block(self):
        # Copy only static-check inputs; never touch real release state or keys.
        with fixture_directory() as temp:
            root=Path(temp)
            for relative in ['ui-lib/src/main','app/src','gradle.properties','LICENSE',
                             'settings.gradle.kts','app/build.gradle.kts','ui-lib/build.gradle.kts',
                             '.private/excluded-features.json', '.private/branding-policy.json']:
                source=checks.ROOT/relative
                target=root/relative
                target.parent.mkdir(parents=True,exist_ok=True)
                if source.is_dir(): shutil.copytree(source,target)
                else: shutil.copy2(source,target)
            p=root/checks.COMMON/'datasource/NearSwapDataSource.kt'
            original=p.read_text()
            p.write_text(original.replace('appFees = emptyList()','appFees = listOf(AppFee("regression", 67))'))
            self.assertTrue(checks.check(root))
            p.write_text(original)
            p=root/checks.COMMON/'provider/NearApiProvider.kt'
            p.write_text(p.read_text()+'\n// Authorization: Bearer regression\n')
            self.assertTrue(checks.check(root))
            p.write_text(p.read_text().replace('// Authorization: Bearer regression',''))
            p=root/'ui-lib/src/main/res/ui/non_translatable/values/private_strings.xml'
            p.write_text(p.read_text().replace('0.25%% included in quote','0.25% included in quote'))
            self.assertTrue(checks.check(root))

    def test_excluded_integrations_cannot_return(self):
        from check_exclusions import check
        with fixture_directory() as temp:
            root=Path(temp)
            for relative in ['app/build.gradle.kts','ui-lib/build.gradle.kts','settings.gradle.kts',
                             '.private/excluded-features.json', '.private/branding-policy.json']:
                target=root/relative
                target.parent.mkdir(parents=True,exist_ok=True)
                shutil.copy2(checks.ROOT/relative,target)
            self.assertEqual([],check(root))
            settings=root/'settings.gradle.kts'
            original=settings.read_text()
            settings.write_text(original+'\ninclude("feature-voting")\n')
            self.assertTrue(check(root))
            settings.write_text(original)
            source=root/'app/src/main/Reintroduced.kt'
            source.parent.mkdir(parents=True)
            source.write_text('import com.flexa.core.Flexa\n')
            self.assertTrue(check(root))

if __name__=='__main__': unittest.main()
