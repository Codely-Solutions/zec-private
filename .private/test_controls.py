import importlib.util, json, tempfile, unittest
from pathlib import Path
import shutil

spec=importlib.util.spec_from_file_location('forkcheck',Path(__file__).with_name('check.py'))
checks=importlib.util.module_from_spec(spec);spec.loader.exec_module(checks)

class ControlsTest(unittest.TestCase):
    def test_current_source(self):
        self.assertEqual([],checks.check())

    def test_fee_and_auth_regressions_block(self):
        # Copy only static-check inputs; never touch real release state or keys.
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            for relative in ['ui-lib/src/main','app/src','gradle.properties','LICENSE']:
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

if __name__=='__main__': unittest.main()
