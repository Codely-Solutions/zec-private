import contextlib, importlib.util, json, os, tempfile, unittest
from pathlib import Path
import shutil
import re
from urllib.parse import parse_qs, unquote, urlsplit

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
    def test_active_repository_references_follow_transfer(self):
        legacy='Sammy'+'-Lastre/zec-private'
        root=checks.ROOT
        paths=[root/'README.md',root/'.private/release-notes.json']
        for directory in ('.github','ui-lib/src','app/src'):
            paths.extend(p for p in (root/directory).rglob('*')
                         if p.is_file() and p.suffix in ('.kt','.md','.yml','.yaml'))
        paths.extend((root/'docs'/name) for name in ('obtainium.json','WALLET-GUIDE.md'))
        paths.extend((root/'.private').glob('*.py'))
        for path in paths:
            with self.subTest(path=path.relative_to(root).as_posix()):
                self.assertNotIn(legacy,path.read_text())

    def test_obtainium_import_is_valid_and_selects_only_production_apk(self):
        config=json.loads((checks.ROOT/'docs/obtainium.json').read_text())
        settings=json.loads(config['additionalSettings'])
        self.assertEqual('com.personal.zecprivate.foss',config['id'])
        self.assertEqual('https://github.com/Codely-Solutions/zec-private',config['url'])
        for key in ('includePrereleases','includeDrafts','fallbackToOlderReleases','trackOnly'):
            self.assertIs(settings[key],False)
        pattern=re.compile(settings['apkFilterRegEx'])
        self.assertIsNotNone(pattern.fullmatch('zec-private-latest.apk'))
        for name in ('TESTNET_UPGRADE_FIXTURE.zip','app-x86_64.apk','latest.apk',
                     'zec-private-latestXapk','zec-private-latest.apk.bak'):
            self.assertIsNone(pattern.fullmatch(name))
        for document in ('README.md','docs/WALLET-GUIDE.md'):
            with self.subTest(document=document):
                guide=(checks.ROOT/document).read_text()
                link=re.search(r'\[Add ZEC Private to Obtainium\]\((https://[^)]+)\)',guide).group(1)
                uri=parse_qs(urlsplit(link).query)['r'][0]
                prefix='obtainium://app/'
                self.assertTrue(uri.startswith(prefix))
                self.assertEqual(config,json.loads(unquote(uri[len(prefix):])))

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
