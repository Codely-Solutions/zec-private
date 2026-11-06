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
        major,minor,patch_version,build=sync.version(self.state['tag'])
        self.next_tag=f'{major}.{minor}.{patch_version+1}-{build+1}'

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
        for screen in ('walletbackup','restore','onboarding','authentication','about','theme','tor','send',
                       'swap','reviewtransaction','signkeystonetransaction','receive','ironwood'):
            with self.subTest(screen=screen):
                self.assertTrue(sync.critical(base+screen+'/Screen.kt'))
        self.assertFalse(sync.critical(base+'colorpicker/Screen.kt'))
        for path in ('ui-design-lib/src/main/res/ui/common/drawable-night-v35/app_logo.png',
                     'ui-lib/src/main/res/ui/about/values/strings.xml',
                     'ui-design-lib/src/main/java/example/design/theme/Theme.kt'):
            self.assertTrue(sync.critical(path), path)
        self.assertFalse(sync.critical('feature-voting/src/main/UnusedVotingChange.kt'))
        self.assertTrue(sync.critical('feature-voting'))
        self.assertTrue(sync.critical('feature-voting-other/build.gradle.kts'))
        for path in ('configuration-impl-android-lib/src/main/kotlin/Flags.kt',
                     'spackle-lib/src/main/kotlin/Logging.kt','build-info-lib/src/main/kotlin/BuildInfo.kt',
                     'scripts/build.sh','wallet-foundation-lib/src/main/kotlin/Flow.kt'):
            with self.subTest(path=path):
                self.assertTrue(sync.critical(path))

    def test_critical_change_blocks_before_candidate_or_publication(self):
        original=sync.run
        def commands(*args,**kwargs):
            if args[:2]==('git','fetch'): return ''
            if args[:2]==('git','rev-parse'): return self.state['sha']
            if args[:3]==('git','diff','--name-only'): return 'ui-lib/src/main/java/co/electriccoin/zcash/ui/common/usecase/RequestSwapQuoteUseCase.kt'
            return original(*args,**kwargs)
        with patch.object(sync,'gh',return_value=[self.release(self.next_tag)]),patch.object(sync,'run',side_effect=commands):
            with self.assertRaisesRegex(RuntimeError,'Critical upstream changes'): sync.main()
        self.assertEqual(self.before,(sync.ROOT/'.private/upstream.json').read_bytes())

    def test_changed_excluded_upstream_tree_is_removed_before_integration(self):
        original=sync.run
        writes=[]
        with tempfile.TemporaryDirectory() as temp:
            upstream=Path(temp)/'upstream'
            original('git','worktree','add','--detach',str(upstream),self.state['sha'])
            try:
                new_file=upstream/'feature-voting/upstream-only-change.txt'
                new_file.write_text('Disposable excluded feature change\n')
                original('git','add',str(new_file),cwd=upstream)
                original('git','-c','user.name=Excluded feature test','-c',
                         'user.email=zec-private@users.noreply.github.com','commit','-m',
                         'Disposable upstream voting-only change',cwd=upstream)
                changed_sha=original('git','rev-parse','HEAD',cwd=upstream)
                def commands(*args,**kwargs):
                    if args[:2]==('git','fetch'):return ''
                    if args[:3]==('git','rev-parse','FETCH_HEAD^{commit}'):return changed_sha
                    if args[:2]==('git','ls-remote'):return ''
                    if args[:2]==('git','push'):
                        writes.append(args);return ''
                    return original(*args,**kwargs)
                with patch.dict(os.environ,{'RUNNER_TEMP':temp}),patch.object(sync,'gh',
                        return_value=[self.release(self.next_tag)]),patch.object(sync,'run',
                        side_effect=commands),patch.object(sync,'output') as output:
                    sync.main()
                    tree=original('git','ls-tree','-r','--name-only',output.call_args.kwargs['source_sha']).splitlines()
                    self.assertFalse(any(name.startswith('feature-voting/') for name in tree))
                    self.assertEqual(1,len(writes))
            finally:
                original('git','worktree','remove','--force',str(upstream))
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
            with patch.dict(os.environ,{'RUNNER_TEMP':temp}),patch.object(sync,'gh',return_value=[self.release(self.next_tag)]),patch.object(sync,'run',side_effect=commands),patch.object(sync,'output') as output:
                sync.main()
                self.assertEqual('true',output.call_args.kwargs['changed'])
                self.assertEqual('updates/'+self.next_tag,output.call_args.kwargs['branch'])
                self.assertEqual(1,len(writes))
                tree=original('git','ls-tree','-r','--name-only',output.call_args.kwargs['source_sha']).splitlines()
                self.assertFalse(any('__pycache__' in name or name.endswith('.pyc') for name in tree))
                self.assertFalse(any(name.startswith('feature-voting/') for name in tree))
                self.assertFalse(any('/ui/screen/flexa/' in name for name in tree))
                self.assertIn('wallet-foundation-lib/build.gradle.kts', tree)
                self.assertIn('app/src/main/java/co/electriccoin/zcash/app/ZecPrivateProductModule.kt', tree)
            self.assertFalse((Path(temp)/'candidate').exists())
        self.assertEqual(self.before,(sync.ROOT/'.private/upstream.json').read_bytes())

    def test_new_upstream_branding_is_rejected_before_branch_push(self):
        original=sync.run
        writes=[]
        with tempfile.TemporaryDirectory() as temp:
            upstream=Path(temp)/'upstream'
            original('git','worktree','add','--detach',str(upstream),self.state['sha'])
            try:
                added=upstream/'ui-design-lib/src/main/res/ui/common/values/upstream_product.xml'
                added.write_text('<resources><string name="new_promotion">Choose Zodl today</string></resources>')
                original('git','add',str(added),cwd=upstream)
                original('git','-c','user.name=Branding integration test','-c',
                         'user.email=zec-private@users.noreply.github.com','commit','-m',
                         'Disposable upstream branding change',cwd=upstream)
                changed_sha=original('git','rev-parse','HEAD',cwd=upstream)
                def commands(*args,**kwargs):
                    if args[:2]==('git','fetch'):return ''
                    if args[:3]==('git','rev-parse','FETCH_HEAD^{commit}'):return changed_sha
                    if args[:2]==('git','ls-remote'):return ''
                    if args[:2]==('git','push'):
                        writes.append(args);return ''
                    return original(*args,**kwargs)
                with patch.dict(os.environ,{'RUNNER_TEMP':temp}),patch.object(sync,'gh',
                        return_value=[self.release(self.next_tag)]),patch.object(sync,'run',side_effect=commands):
                    with self.assertRaises(subprocess.CalledProcessError):sync.main()
                self.assertEqual([],writes)
            finally:
                original('git','worktree','remove','--force',str(upstream))
        self.assertEqual(self.before,(sync.ROOT/'.private/upstream.json').read_bytes())

if __name__=='__main__': unittest.main()
