"""Fixture transport preserves digest/source validation for both storage paths."""
import hashlib,io,json,tempfile,unittest,zipfile
from pathlib import Path
from unittest.mock import patch
from tor_differential_driver import fetch_baseline

class FixtureDownloadTests(unittest.TestCase):
    def archive(self):
        data=io.BytesIO()
        with zipfile.ZipFile(data,'w') as z:
            z.writestr('baseline.apk',b'synthetic unsigned test bytes')
            z.writestr('source-sha.txt','a'*40+'\n')
        return data.getvalue()

    def test_maintenance_asset_is_authenticated_and_source_bound(self):
        raw=self.archive();manifest=dict(release_asset_id=123,size_in_bytes=len(raw),digest='sha256:'+hashlib.sha256(raw).hexdigest(),source_sha='a'*40)
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'apks').mkdir()
            def download(command,**kwargs):
                self.assertIn('Accept: application/octet-stream',command)
                self.assertTrue(command[-1].endswith('/releases/assets/123'))
                kwargs['stdout'].write(raw)
            with patch('tor_differential_driver.subprocess.run',side_effect=download):
                self.assertEqual(fetch_baseline(root,manifest).read_bytes(),b'synthetic unsigned test bytes')
            manifest['digest']='sha256:'+'0'*64
            with patch('tor_differential_driver.subprocess.run',side_effect=download),self.assertRaisesRegex(ValueError,'digest mismatch'):
                fetch_baseline(root,manifest)

    def test_action_archive_still_rejects_wrong_source(self):
        raw=self.archive();manifest=dict(id=456,size_in_bytes=len(raw),digest='sha256:'+hashlib.sha256(raw).hexdigest(),source_sha='b'*40)
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'apks').mkdir()
            def download(command,**kwargs):
                self.assertTrue(command[-1].endswith('/actions/artifacts/456/zip'))
                kwargs['stdout'].write(raw)
            with patch('tor_differential_driver.subprocess.run',side_effect=download),self.assertRaisesRegex(ValueError,'source identity mismatch'):
                fetch_baseline(root,manifest)

    def test_pair_archive_selects_app_or_exact_source_matched_instrumentation(self):
        data=io.BytesIO()
        app=b'old app';test=b'version matched test'
        with zipfile.ZipFile(data,'w') as z:
            z.writestr('app.apk',app)
            z.writestr('app-androidTest.apk',test)
            z.writestr('source-sha.txt','a'*40+'\n')
        raw=data.getvalue()
        manifest=dict(id=456,size_in_bytes=len(raw),digest='sha256:'+hashlib.sha256(raw).hexdigest(),
                      source_sha='a'*40,apk_sha256=hashlib.sha256(test).hexdigest())
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'apks').mkdir()
            def download(command,**kwargs):kwargs['stdout'].write(raw)
            with patch('tor_differential_driver.subprocess.run',side_effect=download):
                self.assertEqual(app,fetch_baseline(root,{k:v for k,v in manifest.items() if k!='apk_sha256'}).read_bytes())
                self.assertEqual(test,fetch_baseline(root,manifest,'baseline-androidTest.apk',instrumentation=True).read_bytes())
                for change, message in (({'source_sha':'b'*40}, 'source identity mismatch'),
                                        ({'apk_sha256':'0'*64}, 'APK digest mismatch')):
                    with self.subTest(change=change),self.assertRaisesRegex(ValueError, message):
                        fetch_baseline(root,dict(manifest,**change),'baseline-androidTest.apk',instrumentation=True)
            self.assertFalse((root/'baseline.zip').exists())

    def test_instrumentation_requires_source_digest_and_exactly_one_test_apk(self):
        raw=self.archive();manifest=dict(id=456,size_in_bytes=len(raw),digest='sha256:'+hashlib.sha256(raw).hexdigest(),
                                         source_sha='a'*40,apk_sha256='1'*64)
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'apks').mkdir()
            for missing in ('source_sha','apk_sha256'):
                value={k:v for k,v in manifest.items() if k!=missing}
                with self.subTest(missing=missing),patch('tor_differential_driver.subprocess.run') as download:
                    with self.assertRaises(ValueError):fetch_baseline(root,value,instrumentation=True)
                    download.assert_not_called()
            def download(command,**kwargs):kwargs['stdout'].write(raw)
            with patch('tor_differential_driver.subprocess.run',side_effect=download),self.assertRaisesRegex(ValueError,'selected baseline APK'):
                fetch_baseline(root,manifest,instrumentation=True)
            self.assertFalse((root/'baseline.zip').exists())

    def test_preservation_requires_both_original_executed_apk_hashes(self):
        from preserve_upgrade_fixture import fixture_apks
        app=b'original app';test=b'original instrumentation'
        core=dict(build_source_sha='a'*40,candidate_apk_sha256=hashlib.sha256(app).hexdigest(),
                  instrumentation_apk_sha256=hashlib.sha256(test).hexdigest())
        def contents(extra=False,include_test=True):
            data=io.BytesIO()
            with zipfile.ZipFile(data,'w') as z:
                z.writestr('nested/app.apk',app)
                if include_test:z.writestr('nested/app-androidTest.apk',test)
                if extra:z.writestr('other-androidTest.apk',test)
                z.writestr('source-sha.txt','a'*40+'\n')
            return zipfile.ZipFile(io.BytesIO(data.getvalue()))
        with contents() as archive:
            self.assertEqual({'app.apk':app,'app-androidTest.apk':test},fixture_apks(archive,core))
        for change in ({'build_source_sha':'b'*40},{'candidate_apk_sha256':'0'*64},
                       {'instrumentation_apk_sha256':'0'*64}):
            with self.subTest(change=change),contents() as archive,self.assertRaises(ValueError):
                fixture_apks(archive,dict(core,**change))
        for options in ({'include_test':False},{'extra':True}):
            with self.subTest(options=options),contents(**options) as archive,self.assertRaises(ValueError):
                fixture_apks(archive,core)

    def test_local_preservation_verifies_the_public_record_before_reading_or_publishing_a_fixture(self):
        from preserve_upgrade_fixture import preserve
        from test_support import patch_environment, public_baseline_metadata

        tag = 'v3.19.0-private.30'
        selected = dict(release=tag, source_sha='a' * 40, version_code=330)
        proof = dict(release_tag=tag, apk_sha256='b' * 64, source_sha=selected['source_sha'], versionCode=330)
        public = public_baseline_metadata(selected, proof['apk_sha256'])
        release = public['/releases/latest']
        release['assets'][1]['digest'] = 'sha256:' + '0' * 64
        with tempfile.TemporaryDirectory() as root, patch_environment({'CODEX_GUARD_OUTPUT': root}), \
                patch('hosted_fixture.recorded_pair', return_value=(proof, {})), \
                patch('preserve_upgrade_fixture.api', return_value=release), \
                patch('preserve_upgrade_fixture.gh') as gh:
            with self.assertRaisesRegex(ValueError, 'release assets differ'):
                preserve(tag)
            self.assertEqual([], list(Path(root).iterdir()))
            gh.assert_not_called()

    def test_local_preservation_can_read_the_exact_immutable_registry_pair(self):
        from hosted_fixture import REGISTRY
        from preserve_upgrade_fixture import digest, retained_hosted_pair, write_seedless_pair

        tag, source = 'v3.19.0-private.30', 'a' * 40
        payloads = {'wallet.apk': b'app', 'wallet-androidTest.apk': b'test'}
        core = dict(build_source_sha=source, candidate_version_code=330,
                    candidate_apk_sha256=hashlib.sha256(payloads['wallet.apk']).hexdigest(),
                    instrumentation_apk_sha256=hashlib.sha256(payloads['wallet-androidTest.apk']).hexdigest())
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / '.private').mkdir()
            archive = root / 'fixture.zip'
            write_seedless_pair(archive, payloads, source)
            reference = REGISTRY + '@sha256:' + 'b' * 64
            manifest = dict(release=tag, source_sha=source, version_code=330, reference=reference,
                            size_in_bytes=archive.stat().st_size, digest=digest(archive),
                            candidate_apk_sha256=core['candidate_apk_sha256'],
                            instrumentation_apk_sha256=core['instrumentation_apk_sha256'])
            (root / '.private/hosted-fixture.json').write_text(json.dumps(manifest))
            self.assertEqual((payloads, reference), retained_hosted_pair(tag, core, archive, root))
            with self.assertRaisesRegex(ValueError, 'exact recorded pair'):
                retained_hosted_pair(tag, dict(core, candidate_version_code=331), archive, root)
            archive.write_bytes(bytes([0]) + archive.read_bytes()[1:])
            with self.assertRaisesRegex(ValueError, 'exact recorded pair'):
                retained_hosted_pair(tag, core, archive, root)

    def test_local_fixture_preservation_resumes_after_upload_acknowledgment_is_lost(self):
        import subprocess
        from preserve_upgrade_fixture import preserve
        from test_support import patch_environment

        tag, source = 'v3.19.0-private.30', 'a' * 40
        payloads = {'app.apk': b'accepted app', 'app-androidTest.apk': b'accepted test'}
        core = dict(build_source_sha=source, candidate_version_code=330, artifact_id=5, workflow_run_id=7,
                    candidate_apk_sha256=hashlib.sha256(payloads['app.apk']).hexdigest(),
                    instrumentation_apk_sha256=hashlib.sha256(payloads['app-androidTest.apk']).hexdigest())
        assets = {}
        uploads = []
        downloads = []
        draft_id = 10

        def gh(*args):
            if args[0] == 'release':
                file = Path(args[-1])
                self.assertNotIn(file.name, assets)
                raw = file.read_bytes()
                assets[file.name] = (dict(name=file.name, id=len(assets) + 1, state='uploaded', size=len(raw),
                                          digest='sha256:' + hashlib.sha256(raw).hexdigest()), raw)
                uploads.append(file.name)
                if len(uploads) == 1:
                    raise subprocess.CalledProcessError(1, ['gh', 'release', 'upload'])
                return b''
            requested = int(args[-1].split('/')[-1])
            downloads.append(requested)
            return next(raw for details, raw in assets.values() if details['id'] == requested)

        def api(path):
            if path == 'releases/' + str(draft_id):
                return dict(id=draft_id, draft=True, tag_name='maintenance-fixtures',
                            assets=[details for details, _ in assets.values()])
            self.assertEqual(path, 'releases/tags/' + tag)
            return {'tag_name': tag}

        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / '.private').mkdir()
            (root / '.private/maintenance-assets.json').write_text(json.dumps(dict(draft_release_id=draft_id, fixtures={})))
            with patch('preserve_upgrade_fixture.ROOT', root), \
                    patch('hosted_fixture.recorded_pair', return_value=({'source_sha': source}, core)), \
                    patch('preserve_upgrade_fixture.require_recorded_public_release'), \
                    patch('preserve_upgrade_fixture.api', side_effect=api), \
                    patch('preserve_upgrade_fixture.gh', side_effect=gh), \
                    patch('preserve_upgrade_fixture.original_pair', return_value=payloads) as original:
                for attempt in range(4):
                    output = root / str(attempt)
                    output.mkdir()
                    with patch_environment({'CODEX_GUARD_OUTPUT': str(output)}):
                        if attempt == 0:
                            with self.assertRaises(subprocess.CalledProcessError):
                                preserve(tag)
                        elif attempt == 3:
                            details, raw = assets[uploads[1]]
                            changed = raw.replace(b'"version_code": 330', b'"version_code": 331')
                            assets[uploads[1]] = (dict(details, digest='sha256:' + hashlib.sha256(changed).hexdigest()), changed)
                            with self.assertRaisesRegex(ValueError, 'differs from verified provenance'):
                                preserve(tag)
                        else:
                            preserve(tag)
                            receipt = json.loads((output / 'fixture-preservation.json').read_text())
                            self.assertEqual({1, 2}, {item['release_asset_id'] for item in receipt['assets'].values()})
                original.assert_called_once_with(core)
        self.assertEqual(2, len(uploads))
        self.assertEqual([1, 2, 1, 2, 1, 2], downloads)

    def test_preservation_replaces_only_the_exact_empty_starter(self):
        from preserve_upgrade_fixture import preserve
        from test_support import patch_environment

        tag, source, draft_id = 'v3.19.0-private.30', 'a' * 40, 10
        payloads = {'app.apk': b'app', 'app-androidTest.apk': b'test'}
        core = dict(build_source_sha=source, candidate_version_code=330, artifact_id=5, workflow_run_id=7,
                    candidate_apk_sha256=hashlib.sha256(payloads['app.apk']).hexdigest(),
                    instrumentation_apk_sha256=hashlib.sha256(payloads['app-androidTest.apk']).hexdigest())
        name = tag + '-TESTNET_UPGRADE_FIXTURE.zip'
        placeholder = dict(name=name, id=15, state='starter', size=0, digest=None)
        assets, payload = {name: placeholder}, {}
        operations = []

        def api(path):
            if path == 'releases/' + str(draft_id):
                return dict(id=draft_id, draft=True, tag_name='maintenance-fixtures', assets=list(assets.values()))
            return {'tag_name': tag}

        def gh(*args):
            if args[:3] == ('api', '-X', 'DELETE'):
                self.assertTrue(args[-1].endswith('/15'))
                self.assertEqual(placeholder, assets.pop(name))
                operations.append('remove empty starter')
            elif args[0] == 'release':
                file = Path(args[-1])
                self.assertNotIn(file.name, assets)
                asset_id = 20 + len(assets)
                raw = file.read_bytes()
                assets[file.name] = dict(name=file.name, id=asset_id, state='uploaded', size=len(raw),
                                         digest='sha256:' + hashlib.sha256(raw).hexdigest())
                payload[asset_id] = raw
                operations.append(file.name)
            else:
                return payload[int(args[-1].split('/')[-1])]
            return b''

        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / '.private').mkdir()
            (root / '.private/maintenance-assets.json').write_text(json.dumps(dict(draft_release_id=draft_id, fixtures={})))
            with patch('preserve_upgrade_fixture.ROOT', root), \
                    patch('hosted_fixture.recorded_pair', return_value=({'source_sha': source}, core)), \
                    patch('preserve_upgrade_fixture.require_recorded_public_release'), \
                    patch('preserve_upgrade_fixture.original_pair', return_value=payloads), \
                    patch('preserve_upgrade_fixture.api', side_effect=api), \
                    patch('preserve_upgrade_fixture.gh', side_effect=gh):
                for changes in ({'size': 42}, {'state': 'uploaded'}, {'digest': 'sha256:' + '0' * 64}):
                    assets[name] = dict(placeholder, **changes)
                    with patch_environment({'CODEX_GUARD_OUTPUT': str(root)}):
                        with self.assertRaisesRegex(ValueError, 'Invalid existing fixture asset'):
                            preserve(tag)
                    self.assertEqual([], operations)
                assets[name] = placeholder
                with patch_environment({'CODEX_GUARD_OUTPUT': str(root)}):
                    preserve(tag)
                self.assertEqual(['remove empty starter', name, name.replace('.zip', '.json')], operations)
                self.assertTrue((root / 'fixture-preservation.json').is_file())

    def test_hosted_pair_is_deterministic_and_rejects_unexpected_contents(self):
        from hosted_fixture import verify_pair
        from preserve_upgrade_fixture import write_seedless_pair

        app = b'accepted app'
        test = b'accepted instrumentation'
        payloads = {'app.apk': app, 'app-androidTest.apk': test}
        core = dict(build_source_sha='a'*40,
                    candidate_apk_sha256=hashlib.sha256(app).hexdigest(),
                    instrumentation_apk_sha256=hashlib.sha256(test).hexdigest())
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            first, second = root/'first.zip', root/'second.zip'
            write_seedless_pair(first, payloads, core['build_source_sha'])
            write_seedless_pair(second, dict(reversed(list(payloads.items()))), core['build_source_sha'])
            self.assertEqual(first.read_bytes(), second.read_bytes())
            self.assertEqual(payloads, verify_pair(first, core, prepared=True))
            with self.assertRaisesRegex(ValueError, 'APK mismatch'):
                verify_pair(first, dict(core, instrumentation_apk_sha256='0'*64), prepared=True)
            with zipfile.ZipFile(first, 'a') as archive:
                archive.writestr('wallet-data.json', '{}')
            with self.assertRaisesRegex(ValueError, 'Unexpected fixture entries'):
                verify_pair(first, core, prepared=True)

    def test_registry_only_recovery_checks_the_immutable_selection_and_both_accepted_apks(self):
        from hosted_fixture import MAX_ARCHIVE, REGISTRY, fixture_layer, recovery_digest, verify
        from preserve_upgrade_fixture import write_seedless_pair

        tag, oci = 'v3.19.0-private.22', 'sha256:' + 'a'*64
        app, instrumentation = b'exact app', b'exact test'
        core = dict(build_source_sha='b'*40, candidate_version_code=22,
                    candidate_apk_sha256=hashlib.sha256(app).hexdigest(),
                    instrumentation_apk_sha256=hashlib.sha256(instrumentation).hexdigest())
        with tempfile.TemporaryDirectory() as temp, patch('hosted_fixture.recorded_pair', return_value=({}, core)):
            archive = Path(temp) / 'fixture.zip'
            write_seedless_pair(archive, {'app.apk': app, 'app-androidTest.apk': instrumentation}, core['build_source_sha'])
            archive_digest = 'sha256:' + hashlib.sha256(archive.read_bytes()).hexdigest()
            layer = dict(size=archive.stat().st_size, mediaType='application/zip', digest=archive_digest,
                         annotations={'org.opencontainers.image.title': 'fixture.zip'})
            self.assertEqual(recovery_digest(tag, oci), oci)
            self.assertEqual(fixture_layer(dict(schemaVersion=2, layers=[layer])), archive_digest)
            binding = verify(tag, archive, archive_digest, oci)
            self.assertEqual(binding['reference'], REGISTRY + '@' + oci)
            self.assertEqual(binding['instrumentation_apk_sha256'], core['instrumentation_apk_sha256'])
            with self.assertRaisesRegex(ValueError, 'oversized'):
                fixture_layer(dict(schemaVersion=2, layers=[dict(layer, size=MAX_ARCHIVE + 1)]))
            with self.assertRaisesRegex(ValueError, 'exact release tag'):
                recovery_digest('', oci)
            with self.assertRaisesRegex(ValueError, 'Exact OCI manifest digest'):
                recovery_digest(tag, 'latest')
            with self.assertRaisesRegex(ValueError, 'differs from the prepared archive'):
                verify(tag, archive, 'sha256:' + '0'*64, oci)

    def test_scheduled_fixture_recovery_selects_only_an_unpreserved_recorded_pair(self):
        from hosted_fixture import pending_pair
        from release_metadata import Metadata

        tag = 'v3.19.0-private.22'
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            records = root / '.private/release-evidence'
            records.mkdir(parents=True)
            selected = root / '.private/hosted-fixture.json'
            selected.write_text(json.dumps(dict(release='v3.19.0-private.21', version_code=21)))
            with patch('hosted_fixture.Metadata.api', return_value={'tag_name': tag}):
                metadata = Metadata()
                self.assertEqual(pending_pair(metadata, root), '')
                record = records / (tag + '.json')
                record.write_text(json.dumps(dict(versionCode=22, core=dict(candidate_version_code=21))))
                self.assertEqual(pending_pair(metadata, root), '')
                record.write_text(json.dumps(dict(release_tag=tag, versionCode=22, core=dict(
                    candidate_version_code=22, passed=True, real_funds_used=False, personal_wallet_access=False))))
                self.assertEqual(pending_pair(metadata, root), tag)
                selected.write_text(json.dumps(dict(release=tag, version_code=22)))
                self.assertEqual(pending_pair(metadata, root), '')

    def test_hosted_binding_record_refuses_replacement_and_downgrade(self):
        from hosted_fixture import REGISTRY, record

        apk = 'a'*64
        core = dict(build_source_sha='b'*40, candidate_version_code=22,
                    candidate_apk_sha256=apk, instrumentation_apk_sha256='c'*64)
        binding = dict(release='v3.19.0-private.22', source_sha=core['build_source_sha'],
                       version_code=22, candidate_apk_sha256=apk,
                       instrumentation_apk_sha256=core['instrumentation_apk_sha256'],
                       reference=REGISTRY+'@sha256:'+'d'*64, digest='sha256:'+'e'*64, size_in_bytes=100)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root/'.private').mkdir()
            selected = root/'.private/hosted-fixture.json'
            selected.write_text('{"version_code":21}')
            from test_support import public_baseline_metadata

            public = public_baseline_metadata(binding, apk)
            proof = dict(release_tag=binding['release'], apk_sha256=apk,
                         versionCode=binding['version_code'], source_sha=binding['source_sha'])
            with patch('hosted_fixture.recorded_pair', return_value=(proof, core)), \
                    patch('hosted_fixture.Metadata.api', side_effect=public.__getitem__):
                from release_metadata import Metadata

                metadata = Metadata()
                public['/releases/latest']['body'] += ' '
                with self.assertRaisesRegex(ValueError, 'version differs'):
                    record(binding, metadata, root)
                self.assertEqual({'version_code': 21}, json.loads(selected.read_text()))
                public['/releases/latest']['body'] = public['/releases/latest']['body'].rstrip()
                self.assertTrue(record(binding, metadata, root))
                self.assertEqual(binding, json.loads(selected.read_text()))
                self.assertFalse(record(binding, metadata, root))
                with self.assertRaisesRegex(ValueError, 'replace or downgrade'):
                    record(dict(binding, digest='sha256:'+'f'*64), metadata, root)

if __name__=='__main__':unittest.main()
