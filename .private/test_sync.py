"""No-network controller tests, including an actual disposable upstream worktree."""
import importlib.util,json,os,subprocess,tempfile,unittest
from pathlib import Path
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('sync',Path(__file__).with_name('sync.py'))
sync=importlib.util.module_from_spec(spec);spec.loader.exec_module(sync)

class SyncTests(unittest.TestCase):
    def setUp(self):
        self.state=json.loads((sync.ROOT/'.private/upstream.json').read_text())
        self.before=(sync.ROOT/'.private/upstream.json').read_bytes()

    def release(self,tag,published='2026-11-04T00:00:00Z',**extra):
        return dict(tag_name=tag,published_at=published,draft=False,prerelease=False,
                    html_url='https://github.com/zodl-inc/zodl-android/releases/tag/'+tag,**extra)

    def test_duplicate_poll_ignores_old_formats_and_prereleases(self):
        releases=[self.release(self.state['tag']),self.release('old-format','2025-01-01T00:00:00Z')]
        draft=self.release('99.0.0-9999');draft['draft']=True
        prerelease=self.release('98.0.0-9998');prerelease['prerelease']=True
        with patch.object(sync,'gh',return_value=releases+[draft,prerelease]),patch.object(sync,'output') as output:
            sync.main()
            output.assert_called_once_with(changed='false')

    def test_sensitive_wallet_screens_require_review(self):
        base='ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/'
        for screen in ('walletbackup','restore','onboarding','authentication','tor','send',
                       'swap','reviewtransaction','signkeystonetransaction','receive','ironwood'):
            with self.subTest(screen=screen):
                self.assertTrue(sync.critical(base+screen+'/Screen.kt'))
        self.assertFalse(sync.critical(base+'colorpicker/Screen.kt'))
        for path in ('configuration-impl-android-lib/src/main/kotlin/Flags.kt',
                     'spackle-lib/src/main/kotlin/Logging.kt','build-info-lib/src/main/kotlin/BuildInfo.kt',
                     'scripts/build.sh'):
            with self.subTest(path=path):
                self.assertTrue(sync.critical(path))

    def test_critical_change_blocks_before_candidate_or_publication(self):
        original=sync.run
        def commands(*args,**kwargs):
            if args[:2]==('git','fetch'): return ''
            if args[:2]==('git','rev-parse'): return self.state['sha']
            if args[:3]==('git','diff','--name-only'): return 'ui-lib/src/main/java/co/electriccoin/zcash/ui/common/usecase/RequestSwapQuoteUseCase.kt'
            return original(*args,**kwargs)
        with patch.object(sync,'gh',return_value=[self.release('3.19.1-3235')]),patch.object(sync,'run',side_effect=commands):
            with self.assertRaisesRegex(RuntimeError,'Critical upstream changes'): sync.main()
        self.assertEqual(self.before,(sync.ROOT/'.private/upstream.json').read_bytes())

    def test_simulated_new_release_reapplies_real_patch_without_remote_writes(self):
        original=sync.run
        writes=[]
        def commands(*args,**kwargs):
            if args[:2]==('git','fetch'): return ''
            if args[:3]==('git','rev-parse','FETCH_HEAD^{commit}'): return self.state['sha']
            if args[:2]==('git','ls-remote'): return ''
            if args[:2]==('git','push'):
                writes.append(args);return ''
            return original(*args,**kwargs)
        with tempfile.TemporaryDirectory() as temp:
            with patch.dict(os.environ,{'RUNNER_TEMP':temp}),patch.object(sync,'gh',return_value=[self.release('3.19.1-3235')]),patch.object(sync,'run',side_effect=commands),patch.object(sync,'output') as output:
                sync.main()
                self.assertEqual('true',output.call_args.kwargs['changed'])
                self.assertEqual('updates/3.19.1-3235',output.call_args.kwargs['branch'])
                self.assertEqual(1,len(writes))
            self.assertFalse((Path(temp)/'candidate').exists())
        self.assertEqual(self.before,(sync.ROOT/'.private/upstream.json').read_bytes())

if __name__=='__main__': unittest.main()
