import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile
from test_support import patch_environment, public_baseline_metadata

import hosted_claim
import hosted_core
import hosted_fixture
import hosted_release


SOURCE = 'a' * 40
CONTROLLER = 'b' * 40
RUN = 71
VERSION = 3234022


class HostedReleaseTests(unittest.TestCase):
    def test_latest_upgrade_binds_integrated_source_version_and_public_assets(self):
        fixture = dict(release='v3.19.0-private.30', source_sha='a' * 40, version_code=3234033,
                       candidate_apk_sha256='c' * 64, instrumentation_apk_sha256='d' * 64)
        pair = dict(passed=True, real_funds_used=False, personal_wallet_access=False,
                    build_source_sha=fixture['source_sha'], candidate_version_code=fixture['version_code'],
                    candidate_apk_sha256='c' * 64, instrumentation_apk_sha256='d' * 64)
        record = dict(release_tag=fixture['release'], source_sha=fixture['source_sha'],
                      versionCode=fixture['version_code'], apk_sha256='e' * 64, core=pair)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            evidence = root / '.private/release-evidence'
            evidence.mkdir(parents=True)
            (root / '.private/hosted-fixture.json').write_text(json.dumps(fixture))
            (evidence / (fixture['release'] + '.json')).write_text(json.dumps(record))
            for drift in ('none', 'apk', 'checksum', 'tag', 'version-space', 'version-duplicate'):
                with self.subTest(drift=drift):
                    endpoints = public_baseline_metadata(fixture, record['apk_sha256'])
                    if drift == 'tag':
                        endpoints['/git/matching-refs/tags/' + fixture['release']][0]['object']['sha'] = 'f' * 40
                    elif drift in ('apk', 'checksum'):
                        index = int(drift == 'checksum')
                        endpoints['/releases/latest']['assets'][index]['digest'] = 'sha256:' + 'f' * 64
                    elif drift == 'version-space':
                        endpoints['/releases/latest']['body'] += ' '
                    elif drift == 'version-duplicate':
                        endpoints['/releases/latest']['body'] += '\nAndroid versionCode: invalid'
                    metadata = Mock()
                    metadata.api.side_effect = endpoints.__getitem__
                    if drift == 'none':
                        self.assertEqual(hosted_release.require_current_hosted_upgrade(root, metadata), fixture)
                    else:
                        with self.assertRaisesRegex(ValueError, 'release (assets|tag|version) differ'):
                            hosted_release.require_current_hosted_upgrade(root, metadata)

    def test_core_pair_matches_current_and_previous_producer_manifest_paths(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            files = [directory / 'app.apk', directory / 'app-androidTest.apk']
            for path in files:
                path.write_bytes(path.name.encode())
            (directory / 'source-sha.txt').write_text(SOURCE + '\n')
            manifest = directory / 'SHA256SUMS'
            for prefix in ('./', 'core-artifact/'):
                manifest.write_text('\n'.join(f'{hosted_core.sha(path)}  {prefix}{path.name}' for path in files))
                self.assertEqual(hosted_core.candidate_pair(directory, SOURCE), sorted(files))
            manifest.write_text(manifest.read_text().replace('core-artifact/', '../'))
            with self.assertRaisesRegex(ValueError, 'checksums differ'):
                hosted_core.candidate_pair(directory, SOURCE)

    def test_atomic_reservation_rejects_replay_and_changed_run(self):
        value = hosted_claim.binding(SOURCE, CONTROLLER, VERSION, RUN, False)
        metadata = Mock()
        metadata.api.side_effect = [{'object': {'sha': CONTROLLER}}, {'tree': {'sha': 'c' * 40}}]
        ref = hosted_claim.ref_for(value)
        marker = 'd' * 40
        post = Mock(side_effect=[{'sha': marker}, {'ref': ref, 'object': {'sha': marker}}])
        self.assertEqual(hosted_claim.reserve(value, metadata, post), marker)
        post.assert_called_with('/git/refs', {'ref': ref, 'sha': marker})
        changed = hosted_claim.binding('e' * 40, CONTROLLER, VERSION, RUN + 1, False)
        self.assertEqual(hosted_claim.ref_for(changed), ref)
        metadata.api.side_effect = [{'object': {'sha': CONTROLLER}}, {'tree': {'sha': 'c' * 40}}]
        post.side_effect = [{'sha': 'f' * 40}, RuntimeError('existing ref')]
        with self.assertRaisesRegex(RuntimeError, 'existing ref'):
            hosted_claim.reserve(changed, metadata, post)
        metadata.api.side_effect = [{'object': {'sha': marker}}, {'message': json.dumps(value)}]
        with self.assertRaisesRegex(ValueError, 'different inputs'):
            hosted_claim.verify(changed, marker, metadata)

    def test_followup_rehearsal_preserves_original_reservation(self):
        prior = hosted_claim.binding(SOURCE, SOURCE, VERSION, RUN, True)
        marker = 'd' * 40
        metadata = Mock()
        outcome = dict(path='.github/workflows/hosted-core.yml', head_sha=SOURCE,
                       event='workflow_dispatch', status='completed', conclusion='failure')

        def respond(path):
            if path.startswith('/git/ref/'):
                return {'object': {'sha': marker}}
            if path == '/git/commits/' + marker:
                return {'message': json.dumps(prior)}
            if path.startswith('/rules/branches/'):
                return [{'type': 'update'}, {'type': 'deletion'}]
            if path == f'/actions/runs/{RUN}':
                return outcome
            raise AssertionError(path)

        metadata.api.side_effect = respond
        self.assertEqual(hosted_claim.rehearsal_version(VERSION - 1, CONTROLLER, metadata, number=2), VERSION + 1)
        with self.assertRaisesRegex(ValueError, 'earlier controller'):
            hosted_claim.rehearsal_version(VERSION - 1, SOURCE, metadata, number=2)
        outcome['conclusion'] = 'success'
        with self.assertRaisesRegex(ValueError, 'completed with a failure'):
            hosted_claim.rehearsal_version(VERSION - 1, CONTROLLER, metadata, number=2)
        with self.assertRaisesRegex(ValueError, 'bounded'):
            hosted_claim.rehearsal_version(VERSION - 1, CONTROLLER, metadata, number=9)

    def test_artifact_rejects_wrong_run_controller_role_or_digest(self):
        metadata = Mock()
        identity = dict(id=23, digest='sha256:'+'f'*64, size_in_bytes=100)
        value = dict(identity, name='validated-unsigned-apk', expired=False,
                     workflow_run={'id': RUN, 'head_sha': CONTROLLER})
        metadata.api.return_value = value
        self.assertEqual(hosted_release.artifact(metadata, 23, value['name'], RUN, CONTROLLER), identity)
        for field, changed in [('workflow_run', {'id': 72, 'head_sha': CONTROLLER}),
                               ('workflow_run', {'id': RUN, 'head_sha': SOURCE}),
                               ('expired', True), ('digest', ''), ('name', 'other')]:
            with self.subTest(field=field, changed=changed):
                metadata.api.return_value = dict(value, **{field: changed})
                with self.assertRaisesRegex(ValueError, 'exact trusted run and role'):
                    hosted_release.artifact(metadata, 23, value['name'], RUN, CONTROLLER)

    def test_signing_boundary_rejects_rehearsal_and_mismatched_evidence(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            (directory/'.private').mkdir()
            fixture = dict(source_sha='b'*40, candidate_apk_sha256='a'*64)
            (directory/'.private/hosted-fixture.json').write_text(json.dumps(fixture))
            production = dict(id=5, digest='sha256:'+'c'*64, size_in_bytes=321)
            producer = dict(id=6, digest='sha256:'+'d'*64, size_in_bytes=123)
            quotes = dict(id=7, digest='sha256:'+'e'*64, size_in_bytes=456)
            prepared = dict(id=8, digest='sha256:'+'f'*64, size_in_bytes=789)
            common = dict(build_source_sha=SOURCE, candidate_version_code=VERSION,
                          production_artifact=production, workflow_run_id=RUN)
            core = dict(common, rehearsal=False, funding_setup={'claim_requests': 1},
                        hosted_reservation='d'*40, producer=prepared, candidate_apk_sha256='c'*64,
                        instrumentation_apk_sha256='d'*64,
                        direct_preparation=dict(source=SOURCE, harness_controller=CONTROLLER, original_core=producer,
                                                baseline_source=fixture['source_sha'], baseline_apk_sha256='a'*64,
                                                candidate_apk_sha256='c'*64, candidate_instrumentation_sha256='d'*64))
            quote = dict(common, producer=quotes)
            proof = dict(workflow_run_id=str(RUN), versionCode=VERSION)

            def read(core, quote, proof=proof):
                (directory/'hosted-core.json').write_text(json.dumps(core))
                (directory/'hosted-quotes.json').write_text(json.dumps(quote))
                return hosted_release.read_receipts(directory, proof=proof, source=SOURCE, controller=CONTROLLER,
                                                    production_id=5, core_id=6, quote_id=7, metadata=Mock(),
                                                    direct_core_id=8)

            with patch_environment({'GITHUB_RUN_ID': str(RUN)}), patch.object(hosted_release, 'ROOT', directory), \
                    patch.object(hosted_release, 'version_code', return_value=VERSION), \
                    patch.object(hosted_release, 'artifact', side_effect=lambda _, i, *a: {5: production, 6: producer, 7: quotes, 8: prepared}[i]), \
                    patch.object(hosted_release, 'verify'), patch.object(hosted_release, 'validate_core_sources'), \
                    patch.object(hosted_release, 'validate_quotes'), patch.object(hosted_release, 'input_fingerprint'):
                accepted, _ = read(core, quote)
                self.assertEqual(accepted['artifact_id'], 8)
                with self.assertRaisesRegex(ValueError, 'exact APK and instrumentation'):
                    read(dict(core, candidate_apk_sha256='e'*64), quote)
                for field, value in (
                        ('original_core', prepared), ('harness_controller', SOURCE),
                        ('candidate_instrumentation_sha256', 'e' * 64),
                ):
                    wrong = dict(core['direct_preparation'], **{field: value})
                    with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'exact APK and instrumentation'):
                        read(dict(core, direct_preparation=wrong), quote)
                for change in ({'candidate_version_code': VERSION+1}, {'workflow_run_id': RUN+1},
                               {'production_artifact': dict(production, id=8)}, {'build_source_sha': CONTROLLER}):
                    with self.subTest(change=change), self.assertRaisesRegex(ValueError, 'not bound'):
                        read(core, dict(quote, **change))
                with self.assertRaisesRegex(ValueError, 'one funded'):
                    read(dict(core, rehearsal=True), quote)
                with self.assertRaisesRegex(ValueError, 'different test APK producer'):
                    read(dict(core, producer=quotes), quote)

    def test_signing_reuses_original_funding_and_binds_fresh_upgrade_and_quotes(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            production, original, quotes, producer = ({'id': n} for n in range(1, 5))
            core = {'build_source_sha': 'c' * 40, 'candidate_version_code': VERSION - 1}
            fixture = {'release': 'v3.19.0-private.26'}
            origin = {'release': fixture['release'], 'sha256': 'd' * 64, 'source': core['build_source_sha']}
            selected = {'core': core, 'fixture': fixture, 'origin': origin}
            quote = dict(build_source_sha=SOURCE, candidate_version_code=VERSION, production_artifact=production,
                         producer=quotes, workflow_run_id=RUN - 1, quote_controller_sha='e' * 40,
                         artifact_run_id=RUN - 2)
            upgrade = {'build_source_sha': SOURCE, 'candidate_version_code': VERSION}
            (directory/'hosted-upgrade.json').write_text(json.dumps(upgrade))
            recovery = dict(run=RUN - 2, original_controller='f' * 40, quote_run=RUN - 1,
                            quote_controller='e' * 40, prepared_id='4')
            proof = dict(workflow_run_id=str(RUN - 2), versionCode=VERSION)

            def read(current_quote, direct_core_id=4):
                (directory/'hosted-quotes.json').write_text(json.dumps(current_quote))
                return hosted_release.read_receipts(directory, proof=proof, source=SOURCE, controller=CONTROLLER,
                                                    production_id=1, core_id=2, quote_id=3, metadata=Mock(),
                                                    direct_core_id=direct_core_id, reuse_funded=True, recovery=recovery)

            with patch_environment({'GITHUB_RUN_ID': str(RUN)}), \
                    patch.object(hosted_release, 'ROOT', directory), \
                    patch.object(hosted_release, 'version_code', return_value=VERSION), \
                    patch('funded_reuse.select', return_value=selected) as select, \
                    patch.object(hosted_core, 'validate_upgrade') as validate_upgrade, \
                    patch.object(hosted_release, 'artifact', side_effect=lambda _, n, *a:
                                 {1: production, 2: original, 3: quotes, 4: producer}[n]) as artifact, \
                    patch.object(hosted_release, 'validate_quotes') as validate_quotes, \
                    patch.object(hosted_release, 'input_fingerprint', return_value='candidate-inputs'):
                accepted_core, accepted_quote, extra = read(quote)
                self.assertIs(accepted_core, core)
                self.assertEqual(accepted_quote, dict(quote, producer_controller_sha='f' * 40))
                self.assertEqual(extra, {'funded_reuse': origin, 'installed_upgrade': upgrade})
                select.assert_called_once()
                validate_upgrade.assert_called_once_with(upgrade, source=SOURCE, version=VERSION,
                    production=production, producer=producer, original_core=original, fixture=fixture,
                    run=RUN, controller=CONTROLLER, producer_run=RUN - 2, producer_controller='f' * 40)
                validate_quotes.assert_called_once_with(accepted_quote, 'candidate-inputs', use_tor=False)
                self.assertEqual(artifact.call_args.args[1:], (4, 'core-direct-candidate', str(RUN - 2), 'f' * 40))
                for change in ({'build_source_sha': 'c' * 40}, {'workflow_run_id': RUN},
                               {'quote_controller_sha': CONTROLLER}, {'artifact_run_id': RUN},
                               {'production_artifact': original}):
                    with self.subTest(change=change), self.assertRaisesRegex(ValueError, 'trusted run|execution inputs'):
                        read(dict(quote, **change))
                with self.assertRaisesRegex(ValueError, 'prepared test APK'):
                    read(quote, direct_core_id=2)
                select.return_value = None
                with self.assertRaisesRegex(ValueError, 'qualify for reuse'):
                    read(quote)

    def test_admission_and_signing_bind_the_linked_wallet_receipt_and_its_predecessor(self):
        build_run, core_run, quote_run = '7', '8', '9'
        original, wallet_controller, quote_controller = 'c' * 40, 'd' * 40, 'e' * 40
        primary, linked = '1' * 40, '2' * 40
        metadata = Mock(gh='gh', repo='repos/fixture')
        records = {}
        archives = {}

        def artifact(artifact_id, name, run, controller, data=None):
            archive = io.BytesIO()
            with zipfile.ZipFile(archive, 'w') as bundle:
                bundle.writestr(hosted_release.RECEIPT_FILES.get(name, 'other'), data or b'test')
            content = archive.getvalue()
            identity = dict(id=artifact_id, digest='sha256:' + hashlib.sha256(content).hexdigest(),
                            size_in_bytes=len(content))
            records[f'/actions/artifacts/{artifact_id}'] = dict(identity, name=name, expired=False,
                                                               workflow_run=dict(id=int(run), head_sha=controller))
            archives[f'repos/fixture/actions/artifacts/{artifact_id}/zip'] = content
            return identity

        production = artifact(70, 'validated-unsigned-apk', build_run, original)
        original_core = artifact(71, 'core-testnet-candidate', build_run, original)
        quote_producer = artifact(72, 'isolated-ui-repro-apk', build_run, original)
        preparation = artifact(80, 'core-direct-candidate', core_run, wallet_controller)
        fixture = dict(source_sha='4' * 40, candidate_apk_sha256='a' * 64)
        core = dict(build_source_sha=SOURCE, production_artifact=production, candidate_version_code=VERSION,
                    workflow_run_id=int(core_run), artifact_run_id=int(core_run), producer=preparation,
                    rehearsal=False, tor_enabled=False, hosted_reservation=linked,
                    funding_setup={'claim_requests': 1}, candidate_apk_sha256='c' * 64, instrumentation_apk_sha256='d' * 64,
                    direct_preparation=dict(source=SOURCE, harness_controller=wallet_controller, original_core=original_core,
                                            baseline_source=fixture['source_sha'], baseline_apk_sha256='a' * 64,
                                            candidate_apk_sha256='c' * 64, candidate_instrumentation_sha256='d' * 64))
        quote = dict(build_source_sha=SOURCE, production_artifact=production, candidate_version_code=VERSION,
                     workflow_run_id=int(quote_run), artifact_run_id=int(build_run), producer=quote_producer,
                     quote_controller_sha=quote_controller)
        artifact(81, 'hosted-core-receipt', core_run, wallet_controller, json.dumps(core))
        artifact(91, 'hosted-quote-receipt', quote_run, quote_controller, json.dumps(quote))
        prior_binding = hosted_claim.binding(SOURCE, original, VERSION, int(build_run), False)
        next_binding = hosted_claim.binding(SOURCE, wallet_controller, VERSION, int(core_run), False, primary)
        primary_ref, linked_ref = hosted_claim.ref_for(prior_binding), hosted_claim.ref_for(next_binding)
        records['/git/matching-refs/' + primary_ref.removeprefix('refs/')] = [
            dict(ref=primary_ref, object={'sha': primary}), dict(ref=linked_ref, object={'sha': linked})]
        for ref, commit, value in ((primary_ref, primary, prior_binding), (linked_ref, linked, next_binding)):
            records['/git/ref/' + ref.removeprefix('refs/')] = {'object': {'sha': commit}}
            records['/git/commits/' + commit] = {'message': json.dumps(value)}
            records['/rules/branches/' + ref.removeprefix('refs/heads/').replace('/', '%2F')] = [
                {'type': 'update'}, {'type': 'deletion'}]
        metadata.api.side_effect = records.__getitem__
        outputs = dict(run=int(build_run), original_controller=original, production_id='70', core_id='71', quote_id='72',
                       core_receipt_id='81', core_run=int(core_run), core_controller=wallet_controller, prepared_id='80',
                       quote_receipt_id='91', quote_run=int(quote_run), quote_controller=quote_controller)

        def admit(root):
            return hosted_release.verify_recovered_receipts(root, SOURCE, CONTROLLER, build_run, outputs, metadata,
                                                            original_prepared='success', original_reserve='success')

        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / '.private').mkdir()
            (root / '.private/hosted-fixture.json').write_text(json.dumps(fixture))
            (root / 'hosted-core.json').write_bytes(json.dumps(core).encode())
            (root / 'hosted-quotes.json').write_bytes(json.dumps(quote).encode())
            with patch_environment({'GITHUB_RUN_ID': str(RUN)}), patch.object(hosted_release, 'ROOT', root), \
                    patch.object(hosted_release, 'version_code', return_value=VERSION), \
                    patch.object(hosted_release, 'input_fingerprint', return_value='candidate-inputs'), \
                    patch.object(hosted_release, 'validate_core_sources') as validate_core, \
                    patch.object(hosted_release, 'validate_quotes'), \
                    patch('hosted_claim.claim_revision', side_effect=lambda commit, **kwargs: commit), \
                    patch('subprocess.check_output', side_effect=lambda cmd, **kw: archives[cmd[-1]]):
                admit(root)
                self.assertEqual(outputs['previous_reservation'], primary)
                proof = dict(workflow_run_id=build_run, versionCode=VERSION)

                def sign_inputs():
                    return hosted_release.read_receipts(root, proof=proof, source=SOURCE, controller=CONTROLLER,
                                                        production_id=70, core_id=71, quote_id=72, metadata=metadata,
                                                        direct_core_id='80', recovery=outputs)

                accepted_core, accepted_quote = sign_inputs()
                self.assertEqual(accepted_core['producer_controller_sha'], wallet_controller)
                self.assertEqual(accepted_quote['producer_controller_sha'], original)
                self.assertEqual(validate_core.call_count, 2)
                (root / 'hosted-core.json').write_text(json.dumps(dict(core, workflow_run_id=int(build_run))))
                with self.assertRaisesRegex(ValueError, 'exact selected artifact'):
                    sign_inputs()
                for change, error in (
                    ({'build_source_sha': '9' * 40}, 'trusted run'),
                    ({'workflow_run_id': int(build_run)}, 'trusted run'),
                    ({'artifact_run_id': int(build_run)}, 'different artifact run'),
                    ({'hosted_reservation': primary}, 'different linked reservation'),
                    ({'funding_setup': {'claim_requests': 2}}, 'one funded testnet claim'),
                    ({'funding_setup': {'claim_requests': True}}, 'one funded testnet claim'),
                ):
                    artifact(81, 'hosted-core-receipt', core_run, wallet_controller, json.dumps(dict(core, **change)))
                    with self.subTest(change=change), self.assertRaisesRegex(ValueError, error):
                        admit(root)
                artifact(81, 'hosted-core-receipt', core_run, wallet_controller, json.dumps(core))
                artifact(91, 'hosted-quote-receipt', quote_run, quote_controller,
                         json.dumps(dict(quote, producer=original_core)))
                with self.assertRaisesRegex(ValueError, 'different execution inputs or APKs'):
                    admit(root)
                artifact(91, 'hosted-quote-receipt', quote_run, quote_controller, json.dumps(quote))
                records['/git/commits/' + linked]['message'] = json.dumps(dict(next_binding, previous=linked))
                with self.assertRaisesRegex(ValueError, 'Protected reservation belongs to different inputs'):
                    admit(root)

    def test_fixture_selects_release_upgrade_pair_when_funded_core_is_older(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            records = root / '.private/release-evidence'
            records.mkdir(parents=True)
            prior_tag, tag = 'v3.19.0-private.26', 'v3.19.0-private.27'
            core = dict(passed=True, real_funds_used=False, personal_wallet_access=False,
                        build_source_sha='c' * 40, candidate_version_code=VERSION - 1)
            producer = {'id': 11, 'digest': 'sha256:' + 'f' * 64, 'size_in_bytes': 100}
            upgrade = dict(core, evidence_class='unfunded-installed-upgrade', build_source_sha=SOURCE,
                           candidate_version_code=VERSION, artifact_id=11, producer=producer,
                           workflow_run_id=RUN, artifact_run_id=RUN - 1, producer_controller_sha=CONTROLLER)
            evidence = dict(release_tag=tag, source_sha=SOURCE, versionCode=VERSION, core=core)
            record = records / (tag + '.json')
            (root/'.private/hosted-fixture.json').write_text(json.dumps(
                {'release': prior_tag, 'version_code': VERSION - 1}))
            metadata = Mock()
            metadata.api.return_value = {'tag_name': tag}
            record.write_text(json.dumps(evidence))
            self.assertEqual(hosted_fixture.pending_pair(metadata, root), '')
            evidence.update(funded_reuse={'release': prior_tag}, installed_upgrade=upgrade)
            record.write_text(json.dumps(evidence))
            self.assertEqual(hosted_fixture.pending_pair(metadata, root), tag)
            self.assertEqual(hosted_fixture.recorded_pair(tag, root), (evidence, upgrade))
            live = dict(producer, expired=False, workflow_run={'id': RUN - 1, 'head_sha': CONTROLLER})
            self.assertTrue(hosted_fixture.artifact_matches_pair(live, upgrade))
            self.assertFalse(hosted_fixture.artifact_matches_pair(
                dict(live, workflow_run={'id': RUN, 'head_sha': CONTROLLER}), upgrade))
            upgrade['build_source_sha'] = core['build_source_sha']
            record.write_text(json.dumps(evidence))
            with self.assertRaisesRegex(ValueError, 'exact accepted APK pair'):
                hosted_fixture.recorded_pair(tag, root)


if __name__ == '__main__':
    unittest.main()
