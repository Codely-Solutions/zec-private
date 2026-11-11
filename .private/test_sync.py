"""No-network controller tests, including an actual disposable upstream worktree."""
import importlib.util,json,os,subprocess,tempfile,unittest
from pathlib import Path
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('sync',Path(__file__).with_name('sync.py'))
sync=importlib.util.module_from_spec(spec);spec.loader.exec_module(sync)

class RefreshPatchTests(unittest.TestCase):
    def test_index_patch_preserves_crlf_hunks_across_checkout_modes(self):
        import refresh_source
        patches=[]
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            def git(*args):
                return subprocess.check_output(['git',*args],cwd=root,stderr=subprocess.STDOUT)
            git('init','--quiet')
            git('config','user.name','Canonical patch test')
            git('config','user.email','canonical-patch@example.invalid')
            git('config','core.autocrlf','false')
            (root/'.private').mkdir()
            # A patch contains meaningful CR bytes inside text hunks. Treat its
            # bytes as opaque so staging does not normalize those embedded CRs.
            (root/'.gitattributes').write_bytes(b'.private/fork.patch -text\n')
            before=b'@rem upstream\r\n@echo old\r\n'
            after=b'@rem upstream\r\n@echo reviewed\r\n'
            (root/'gradlew.bat').write_bytes(before)
            git('add','.')
            git('commit','--quiet','-m','Synthetic upstream CRLF blob')
            upstream=git('rev-parse','HEAD').decode().strip()
            for mode in ('true','false'):
                with self.subTest(autocrlf=mode):
                    git('config','core.autocrlf',mode)
                    (root/'gradlew.bat').write_bytes(after)
                    git('add','gradlew.bat')
                    generated=refresh_source.generate_patch(root,upstream,
                        ['.',':(exclude).private'])
                    self.assertIn(b'-@echo old\r\n+@echo reviewed\r\n',generated)
                    patches.append(generated)
                    patch_file=root/'.private/fork.patch'
                    patch_file.write_bytes(generated)
                    git('add','.private/fork.patch')
                    self.assertEqual(generated,git('show',':.private/fork.patch'))
                    # Simulate Linux's unconverted checkout of the upstream blob.
                    git('config','core.autocrlf','false')
                    git('checkout',upstream,'--','gradlew.bat')
                    self.assertEqual(before,(root/'gradlew.bat').read_bytes())
                    git('apply','--unidiff-zero','--check',str(patch_file))
                    git('apply','--unidiff-zero',str(patch_file))
                    self.assertEqual(after,(root/'gradlew.bat').read_bytes())
                    git('checkout',upstream,'--','gradlew.bat')
            self.assertEqual(patches[0],patches[1])
            (root/'gradlew.bat').write_bytes(after)
            with self.assertRaisesRegex(RuntimeError,'Stage reviewed source changes'):
                refresh_source.generate_patch(root,upstream,['.',':(exclude).private'])


class SyncTests(unittest.TestCase):
    def setUp(self):
        self.state=json.loads((sync.ROOT/'.private/upstream.json').read_text())
        self.before=(sync.ROOT/'.private/upstream.json').read_bytes()
        major,minor,patch_version,build=sync.version(self.state['tag'])
        self.next_tag=f'{major}.{minor}.{patch_version}-{build+1}'
        versions = patch.object(sync, 'fork_release_versions', return_value=[(20, 3234020), (21, 3234021)])
        versions.start()
        self.addCleanup(versions.stop)

    def test_version_allocation_uses_all_pages_and_prevents_downgrades(self):
        from release_metadata import next_release_version, wallet_release_versions

        versions = wallet_release_versions([
            [{'tag_name': 'maintenance-fixtures', 'body': ''},
             {'tag_name': 'v3.19.0-private.21', 'body': 'Android versionCode: 3234021\n'}],
            [{'tag_name': 'v3.18.0-private.23', 'body': 'Android versionCode: 4000000\n'}],
        ])
        self.assertEqual(next_release_version(versions, 21, 3234021, 1), (24, 4000001))
        with self.assertRaisesRegex(ValueError, 'outside reviewed bounds'):
            next_release_version(versions, 999, 3234021, 1)

    def release(self,tag,published='2026-11-04T00:00:00Z',**extra):
        return dict(tag_name=tag,published_at=published,draft=False,prerelease=False,
                    html_url='https://github.com/zodl-inc/zodl-android/releases/tag/'+tag,**extra)

    def test_github_overlay_preserves_all_fork_metadata_and_removes_upstream_files(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/'controller'
            candidate=Path(temp)/'candidate'
            expected={
                'FUNDING.yml':b'custom: fork-donation\n',
                'pull_request_template.md':b'Fork review\r\n',
                'workflows/review.yml':b'name: trusted\n',
                'actions/setup/action.yml':b'name: setup\n',
                'scripts/check.sh':b'echo check\n',
            }
            for name,data in expected.items():
                path=root/'.github'/name
                path.parent.mkdir(parents=True,exist_ok=True)
                path.write_bytes(data)
            stale=candidate/'.github/workflows/upstream-only.yml'
            stale.parent.mkdir(parents=True)
            stale.write_text('untrusted upstream workflow')
            (candidate/'.github/FUNDING.yml').write_text('upstream funding')
            with patch.object(sync,'ROOT',root):
                sync.overlay_github(candidate)
            actual={p.relative_to(candidate/'.github').as_posix():p.read_bytes()
                    for p in (candidate/'.github').rglob('*') if p.is_file()}
            self.assertEqual(expected,actual)

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
                       'donation','updates','swap','reviewtransaction','signkeystonetransaction','receive','ironwood'):
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
                properties = upstream/'gradle.properties'
                text = properties.read_text()
                self.assertIn('ZCASH_VERSION_CODE=1\n', text)
                properties.write_text(text.replace('ZCASH_VERSION_CODE=1\n', 'ZCASH_VERSION_CODE=2\n'))
                original('git', 'add', 'gradle.properties', cwd=upstream)
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
                    self.assertIn('.github/dependabot.yml',tree)
                    self.assertEqual(original('git','show',output.call_args.kwargs['source_sha']+':.github/dependabot.yml'),
                                     (sync.ROOT/'.github/dependabot.yml').read_text().strip())
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
                self.assertEqual('updates/'+self.next_tag+'-'+original('git','rev-parse','HEAD')[:12],
                                 output.call_args.kwargs['branch'])
                self.assertEqual(1,len(writes))
                tree=original('git','ls-tree','-r','--name-only',output.call_args.kwargs['source_sha']).splitlines()
                self.assertFalse(any('__pycache__' in name or name.endswith('.pyc') for name in tree))
                self.assertFalse(any(name.startswith('feature-voting/') for name in tree))
                self.assertFalse(any('/ui/screen/flexa/' in name for name in tree))
                self.assertIn('wallet-foundation-lib/build.gradle.kts', tree)
                metadata={name for name in tree if name.startswith('.github/')}
                expected={p.relative_to(sync.ROOT).as_posix()
                          for p in (sync.ROOT/'.github').rglob('*') if p.is_file()
                          and '__pycache__' not in p.parts and p.suffix!='.pyc'}
                self.assertEqual(expected,metadata)
                for name in sorted(expected):
                    self.assertEqual(original('git','show',output.call_args.kwargs['source_sha']+':'+name),
                                     (sync.ROOT/name).read_text().strip())
                self.assertIn('app/src/main/java/co/electriccoin/zcash/app/ZecPrivateProductModule.kt', tree)
                sha = output.call_args.kwargs['source_sha']
                self.assertEqual(22, json.loads(original('git','show',sha+':.private/release-revision.json'))['private_revision'])
                patch_text = original('git','show',sha+':.private/fork.patch')
                self.assertNotIn('+ZCASH_VERSION_CODE=', patch_text)
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
