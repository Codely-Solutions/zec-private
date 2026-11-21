"""Release display metadata checks; no signing, build or publication."""
import importlib.util
import io
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile
from unittest.mock import MagicMock, patch
from urllib.error import HTTPError

spec=importlib.util.spec_from_file_location('private_publish',Path(__file__).with_name('publish.py'))
publish=importlib.util.module_from_spec(spec)
with patch.dict(os.environ,{'GITHUB_REPOSITORY':'example/zec-private'}):
    spec.loader.exec_module(publish)
    import integrate_release
    from integrate_release import state_commit


class PublishMetadataTest(unittest.TestCase):
    def test_publication_recovery_integrates_actual_sibling_graph(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)

            def git(*args):
                return subprocess.check_output(
                    ['git', '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
                     '-c', 'core.autocrlf=false', *args], cwd=root, text=True, timeout=30).strip()

            def commit(message):
                git('add', '-A')
                git('commit', '-qm', message)
                return git('rev-parse', 'HEAD')

            git('init', '-q')
            private = root / '.private'
            private.mkdir()
            properties = root / 'gradle.properties'
            properties.write_bytes(b'ZCASH_VERSION_NAME=3.19.0\nZCASH_VERSION_CODE=3234033\n')
            upstream = commit('upstream')
            (private / 'upstream.json').write_text(
                json.dumps(dict(tag='3.19.0-3234', versionCode=3234033, sha=upstream)))
            (private / 'excluded-features.json').write_text('{"forbidden_projects":[]}')
            (private / 'release-revision.json').write_text('{"private_revision":30}')
            original = commit('original controller')
            (private / 'custom-candidate.json').write_text(json.dumps(dict(kind='custom', controller=original)))
            (private / 'fork.patch').touch()
            (private / 'release-revision.json').write_text('{"private_revision":31}')
            properties.write_bytes(b'ZCASH_VERSION_NAME=3.19.0\nZCASH_VERSION_CODE=3234034\n')
            candidate = commit('published candidate')
            self.assertEqual(integrate_release.integration_base(candidate, original, original, root), candidate)
            git('checkout', '-q', '--detach', original)
            (private / 'verify_public_release.py').write_text('# verifier repair')
            controller = commit('recovery-only controller repair')
            result = integrate_release.integration_base(candidate, controller, original, root)
            self.assertEqual(git('rev-list', '--parents', '-n', '1', result).split(), [result, controller, candidate])
            self.assertEqual(git('show', f'{result}:.private/verify_public_release.py'), '# verifier repair')
            self.assertIn('ZCASH_VERSION_CODE=3234034', git('show', f'{result}:gradle.properties'))
            self.assertEqual(git('rev-parse', 'HEAD'), controller)
            properties.write_bytes(properties.read_bytes() + b'UNREVIEWED_SETTING=true\n')
            advanced = commit('unrelated main update')
            with self.assertRaisesRegex(RuntimeError, 'outside publication recovery controls'):
                integrate_release.integration_base(candidate, advanced, original, root)

    def test_attestation_requires_exact_bytes(self):
        with tempfile.TemporaryDirectory() as scratch:
            path = Path(scratch) / 'release-attestation.json'
            data = b'{"release_tag":"v3.19.0-private.21"}'
            digest = hashlib.sha256(data).hexdigest()
            path.write_bytes(data)
            self.assertEqual(publish.read_attestation(path, digest), json.loads(data))
            path.write_bytes(data + b'\n')
            with self.assertRaisesRegex(RuntimeError, 'digest or size mismatch'):
                publish.read_attestation(path, digest)

    def test_state_commit_changes_only_release_metadata_without_checking_out_candidate(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch) / 'repo'
            root.mkdir()

            def git(*args):
                return subprocess.check_output(['git', *args], cwd=root, text=True).strip()

            git('init', '-q')
            git('config', 'user.name', 'Test')
            git('config', 'user.email', 'test@example.invalid')
            git('commit', '--allow-empty', '-qm', 'controller')
            controller = git('rev-parse', 'HEAD')
            (root / 'candidate.txt').write_text('test payload')
            (root / '.private').mkdir()
            marker = '.private/custom-candidate.json'
            (root / marker).write_text('{}')
            git('add', 'candidate.txt', marker)
            git('commit', '-qm', 'candidate')
            candidate = git('rev-parse', 'HEAD')
            git('checkout', '-q', '--detach', controller)
            tag = 'v3.19.0-private.21'
            path = f'.private/release-evidence/{tag}.json'
            proof = dict(release_tag=tag, upstream={'tag': '3.19.0-3235'}, maintenance={'kind': 'custom'})
            commit = state_commit(candidate, proof, Path(scratch), root)
            self.assertEqual(git('rev-parse', 'HEAD'), controller)
            self.assertEqual(git('status', '--porcelain'), '')
            self.assertEqual(git('rev-parse', commit + '^'), candidate)
            self.assertEqual(git('diff', '--name-only', candidate, commit).splitlines(),
                             [marker, path, '.private/upstream.json'])
            self.assertEqual(json.loads(git('show', f'{commit}:{path}')), proof)
            with self.assertRaisesRegex(RuntimeError, 'existing release evidence'):
                state_commit(commit, proof, Path(scratch), root)

    def test_artifact_release_identity_is_bound_to_the_source(self):
        source = 'a' * 40
        metadata = '.private/candidate.json'
        upstream = {'tag': '3.19.1-3235', 'sha': 'b' * 40, 'versionCode': 3235021}
        proof = {'upstream': upstream, 'release_tag': 'v3.19.1-private.21',
                 'versionCode': 3235021, 'versionName': '3.19.1'}
        objects = {
            ('git', 'show', f'{source}:gradle.properties'):
                'ZCASH_VERSION_CODE=3235021\nZCASH_VERSION_NAME=3.19.1',
            ('git', 'show', f'{source}:.private/release-revision.json'): '{"private_revision": 21}',
            ('git', 'ls-tree', '--name-only', source, '--', metadata): metadata,
            ('git', 'show', f'{source}:{metadata}'): json.dumps(upstream),
        }
        with patch.object(publish, 'run', side_effect=lambda *args: objects[args]):
            publish.verify_release_identity(proof, source)
            for field, value in (('versionCode', 3235022), ('versionName', '3.20.0'),
                                 ('release_tag', 'v3.19.1-private.22'),
                                 ('upstream', dict(upstream, sha='c' * 40))):
                with self.subTest(field=field), self.assertRaisesRegex(
                        RuntimeError, 'differs from immutable source'):
                    publish.verify_release_identity(dict(proof, **{field: value}), source)

    def test_signing_requires_current_main_controller(self):
        controller = 'a' * 40
        for ref, local, remote, allowed in (
            ('refs/heads/main', controller, controller, True),
            ('refs/heads/candidate', controller, controller, False),
            ('refs/tags/main', controller, controller, False),
            ('refs/heads/main', 'b' * 40, controller, False),
            ('refs/heads/main', controller, 'b' * 40, False),
        ):
            with self.subTest(ref=ref, local=local, remote=remote), \
                    patch.dict(os.environ, {'GITHUB_REF': ref}), \
                    patch.object(publish, 'run', side_effect=(local, remote)):
                if allowed:
                    publish.require_current_controller(controller)
                else:
                    with self.assertRaises(RuntimeError):
                        publish.require_current_controller(controller)

    def test_title_uses_private_revision(self):
        self.assertEqual('ZEC Private — Release 18',publish.release_title('v2.3.4-private.18'))
        self.assertEqual('ZEC Private — Release 120',publish.release_title('v9.10.11-private.120'))

    def test_recovery_only_accepts_the_matching_main_publisher(self):
        from reconcile_release import publisher_job

        repo = 'Codely-Solutions/zec-private'
        run = dict(id=7, path='.github/workflows/sync-upstream.yml', event='schedule',
                   head_branch='main', head_repository={'full_name': repo}, status='completed',
                   head_sha='a'*40)
        job = dict(name='build / publish', run_id=7, head_sha=run['head_sha'],
                   status='completed', conclusion='failure')
        self.assertEqual(publisher_job(run, [job], 7, repo), job)
        self.assertEqual(publisher_job(dict(run, event='push'), [job], 7, repo), job)
        custom = dict(run, path='.github/workflows/custom-release.yml', event='workflow_dispatch')
        self.assertEqual(publisher_job(custom, [job], 7, repo), job)
        with self.assertRaisesRegex(ValueError, 'trusted main'):
            publisher_job(dict(custom, event='schedule'), [job], 7, repo)
        for change in ({'event': 'pull_request'}, {'head_branch': 'candidate'},
                       {'head_repository': {'full_name': 'fork/zec-private'}}, {'id': 8}):
            with self.subTest(change=change), self.assertRaisesRegex(ValueError, 'trusted main'):
                publisher_job(dict(run, **change), [job], 7, repo)
        for change in ({'name': 'publish'}, {'conclusion': 'skipped'}, {'head_sha': 'b'*40}):
            with self.subTest(change=change), self.assertRaisesRegex(ValueError, 'publisher job'):
                publisher_job(run, [dict(job, **change)], 7, repo)

    def test_resolver_uses_the_retained_original_attempt_and_exact_asset_bytes(self):
        import reconcile_release as reconcile

        source = 'a' * 40
        controller = 'b' * 40
        tag = 'v3.19.0-private.31'
        apk_digest = 'c' * 64
        proof = dict(release_tag=tag, source_sha=source, versionCode=3234034, apk_sha256=apk_digest,
                     controller_sha=controller, maintenance={'kind': 'routine'})
        buffer = io.BytesIO()
        proof_bytes = json.dumps(proof).encode()
        with zipfile.ZipFile(buffer, 'w') as archive:
            archive.writestr('release-attestation.json', proof_bytes)
        raw = buffer.getvalue()
        artifact = dict(id=53, name='release-attestation-1', expired=False,
                        workflow_run=dict(id=7, head_sha=controller), size_in_bytes=len(raw),
                        digest='sha256:' + hashlib.sha256(raw).hexdigest())
        run = dict(id=7, run_attempt=1, path='.github/workflows/sync-upstream.yml', event='schedule',
                   head_branch='main', head_repository={'full_name': 'example/zec-private'},
                   status='completed', head_sha=controller)
        job = dict(name='build / publish', conclusion='success', status='completed', run_id=7, head_sha=controller)
        live = self.draft(proof)
        live['draft'] = False
        live['assets'] = self.assets(proof)
        routes = {
            'actions/runs/7/artifacts?per_page=100': [{'artifacts': [artifact]}],
            'actions/runs/7/attempts/1': run,
            'actions/runs/7/attempts/1/jobs?per_page=100': [{'jobs': [job]}],
            'releases/tags/' + tag: live,
            'git/matching-refs/tags/' + tag: [{'ref': 'refs/tags/' + tag,
                                                'object': {'sha': source, 'type': 'commit'}}],
        }
        with patch.dict(os.environ, GITHUB_REPOSITORY='example/zec-private'), \
                patch.object(reconcile, 'gh', side_effect=lambda path, *_: routes[path]) as api, \
                patch.object(reconcile.subprocess, 'check_output', return_value=raw):
            result = reconcile.resolve('7', '1', '53')
            self.assertEqual((result['attestation_attempt'], result['attestation_id']), (1, 53))
            self.assertEqual(reconcile.resolve('7'), result)
            self.assertNotIn('actions/runs/7', [call.args[0] for call in api.call_args_list])
            for attempt, artifact_id in (('2', '53'), ('1', '54'), ('', '53')):
                with self.subTest(attempt=attempt, artifact_id=artifact_id), self.assertRaises(ValueError):
                    reconcile.resolve('7', attempt, artifact_id)
            live['assets'][1]['digest'] = 'sha256:' + '0' * 64
            with self.assertRaisesRegex(ValueError, 'differs from the original publisher'):
                reconcile.resolve('7', '1', '53')

    def test_reconciliation_main_writes_each_actions_output_on_its_own_line(self):
        import reconcile_release as reconcile

        proof = {
            'release_tag': 'v3.19.0-private.31',
            'attestation_id': 53,
            'attestation_digest': 'a' * 64,
            'attestation_attempt': 1,
            'controller_sha': 'b' * 40,
        }
        with tempfile.TemporaryDirectory() as scratch:
            output = Path(scratch) / 'github-output'
            output.write_bytes(b'prior_output=retained\n')
            env = dict(GITHUB_OUTPUT=str(output), RECONCILIATION_RUN='7', RECONCILIATION_ATTEMPT='1',
                       RECONCILIATION_ARTIFACT='53')
            with patch.dict(os.environ, env, clear=True), patch.object(reconcile, 'resolve', return_value=proof) as resolve:
                reconcile.main()
            resolve.assert_called_once_with('7', '1', '53')
            expected = {key: str(value) for key, value in proof.items()}
            self.assertEqual(output.read_bytes(), b'prior_output=retained\n' +
                             b''.join(f'{key}={value}\n'.encode() for key, value in expected.items()))
            self.assertEqual(dict(line.split('=', 1) for line in output.read_text().splitlines()),
                             {'prior_output': 'retained', **expected})

    @staticmethod
    def draft(proof):
        return dict(id=43, tag_name=proof['release_tag'], prerelease=False, draft=True,
                    body=f'Android versionCode: {proof["versionCode"]}\n', assets=[])

    @staticmethod
    def assets(proof):
        checksum = publish.checksum_bytes(proof['apk_sha256'])
        return [dict(id=1, name=publish.APK, state='uploaded', size=9, digest='sha256:' + proof['apk_sha256']),
                dict(id=2, name=publish.CHECKSUM, state='uploaded', size=len(checksum),
                     digest='sha256:' + hashlib.sha256(checksum).hexdigest())]

    def test_interrupted_draft_upload_reuses_exact_existing_apk_and_finishes_release(self):
        proof = dict(release_tag='v3.19.0-private.31', source_sha='a' * 40, versionCode=3234034,
                     apk_sha256='c' * 64)
        draft = self.draft(proof)
        complete = dict(draft, assets=self.assets(proof))
        draft['assets'] = [complete['assets'][0], dict(id=3, name=publish.CHECKSUM, state='starter', size=0)]
        with tempfile.TemporaryDirectory() as scratch, \
                patch.object(publish, 'release_for_tag', side_effect=[draft, complete]), \
                patch.object(publish, 'require_current_controller'), patch.object(publish, 'verify_release_identity'), \
                patch.object(publish, 'require_advancing_version'), \
                patch.object(publish, 'verify_tag', return_value={'type': 'commit', 'sha': proof['source_sha']}), \
                patch.object(publish, 'release_api', return_value=dict(complete, draft=False)) as api, \
                patch.object(publish, 'run') as cli:
            dist = Path(scratch)
            publish.expose_release(proof, dist, controller='b' * 40, existing=draft)
            self.assertFalse((dist / publish.APK).exists())
            self.assertEqual((dist / publish.CHECKSUM).read_bytes(), publish.checksum_bytes(proof['apk_sha256']))
            self.assertEqual([call.args[:3] for call in cli.call_args_list],
                             [('gh', 'api', '-X'), ('gh', 'release', 'upload')])
            self.assertNotIn('--clobber', cli.call_args.args)
            api.assert_called_once_with('releases/43', method='PATCH', data={'draft': False, 'make_latest': 'true'})

    def test_draft_missing_apk_requires_byte_exact_protected_signing(self):
        import reconcile_release as reconcile

        raw = io.BytesIO()
        with zipfile.ZipFile(raw, 'w') as bundle:
            bundle.writestr('synthetic.apk-entry', 'unsigned payload')
        unsigned = raw.getvalue()
        proof = dict(release_tag='v3.19.0-private.31', source_sha='a' * 40, controller_sha='b' * 40,
                     versionCode=3234034, apk_sha256=hashlib.sha256(unsigned).hexdigest())
        inputs = dict(resume_draft='true', reconciliation_run='7', reconciliation_attempt='1',
                      reconciliation_artifact='53')
        smoke = ['bash', '.private/verify_signing_key.sh']
        for scenario in ('failed-smoke', 'different-digest', 'matching-digest', 'already-uploaded'):
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as scratch:
                draft = self.draft(proof)
                if scenario == 'already-uploaded':
                    draft['assets'] = [dict(self.assets(proof)[0], size=len(unsigned))]
                event = Path(scratch) / 'event.json'
                event.write_text(json.dumps(dict(sender={'id': reconcile.OPERATOR_ID}, inputs=inputs)))
                env = dict(GITHUB_REPOSITORY=reconcile.REPOSITORY, GITHUB_REF='refs/heads/main',
                           GITHUB_EVENT_NAME='workflow_dispatch', GITHUB_EVENT_PATH=str(event),
                           GITHUB_SHA='b' * 40, RUNNER_TEMP=scratch, ANDROID_HOME=scratch)
                key_checks = []

                def sign(_, path, _tools):
                    key_checks.append('sign')
                    path.write_bytes(unsigned if scenario == 'matching-digest' else unsigned + b'tampered')

                def run(command, **kwargs):
                    if command == smoke:
                        self.assertTrue(kwargs['check'])
                        self.assertEqual(kwargs['timeout'], 60)
                        key_checks.append('smoke')
                        if scenario == 'failed-smoke':
                            raise subprocess.CalledProcessError(1, command)

                with patch.dict(os.environ, env), patch.object(reconcile, 'lookup', return_value=({}, proof, draft)), \
                        patch.object(reconcile, 'unsigned_proof', return_value=(unsigned, proof, {})), \
                        patch.object(reconcile, 'verify_tag'), patch.object(reconcile, 'expose_release') as expose, \
                        patch.object(reconcile.subprocess, 'run', side_effect=run), \
                        patch.object(reconcile.subprocess, 'check_output', return_value=unsigned), \
                        patch.object(integrate_release, 'integration_base'), \
                        patch('publish.require_current_controller'), patch('publish.sign_apk', side_effect=sign), \
                        patch('signing_wiring.environment_wiring', side_effect=lambda _: key_checks.append('wiring')):
                    if scenario in ('matching-digest', 'already-uploaded'):
                        reconcile.resume_draft('7', '1', '53')
                        expose.assert_called_once()
                    elif scenario == 'failed-smoke':
                        with self.assertRaises(subprocess.CalledProcessError):
                            reconcile.resume_draft('7', '1', '53')
                        expose.assert_not_called()
                    else:
                        with self.assertRaisesRegex(ValueError, 'differs from the original'):
                            reconcile.resume_draft('7', '1', '53')
                        expose.assert_not_called()
                expected = {
                    'failed-smoke': ['wiring', 'smoke'],
                    'different-digest': ['wiring', 'smoke', 'sign'],
                    'matching-digest': ['wiring', 'smoke', 'sign'],
                    'already-uploaded': [],
                }
                self.assertEqual(key_checks, expected[scenario])

    def test_draft_with_wrong_asset_and_public_assets_are_immutable(self):
        proof = dict(release_tag='v3.19.0-private.31', source_sha='a' * 40, versionCode=3234034,
                     apk_sha256='c' * 64)
        for is_draft, failure in ((True, 'differs from'), (False, 'exact unpublished')):
            draft = dict(self.draft(proof), draft=is_draft)
            draft['assets'] = [dict(self.assets(proof)[0], digest='sha256:' + '0' * 64)]
            with tempfile.TemporaryDirectory() as scratch, \
                    patch.object(publish, 'release_for_tag', return_value=draft), \
                    patch.object(publish, 'require_advancing_version'), \
                    patch.object(publish, 'verify_release_identity'), patch.object(publish, 'run') as cli:
                with self.subTest(is_draft=is_draft), self.assertRaisesRegex(ValueError, failure):
                    publish.expose_release(proof, Path(scratch), controller='b' * 40, existing=draft)
                cli.assert_not_called()

    def test_orphan_tags_must_point_to_the_exact_attested_commit(self):
        tag = 'v3.19.0-private.31'
        source = 'a' * 40
        matching = [{'ref': 'refs/tags/' + tag, 'object': {'sha': source, 'type': 'commit'}}]
        api = MagicMock(side_effect=[[], matching[0], matching])
        self.assertEqual(publish.verify_tag(tag, source, api=api, create=True), matching[0]['object'])
        self.assertEqual(api.call_args_list[1].kwargs, {
            'method': 'POST', 'data': {'ref': 'refs/tags/' + tag, 'sha': source}})
        api = MagicMock(return_value=matching)
        with self.assertRaisesRegex(ValueError, 'points to a different source'):
            publish.verify_tag(tag, 'b' * 40, api=api, create=True)
        api.assert_called_once()

    def test_unsigned_archive_uses_the_same_compressed_budget_before_and_after_signing(self):
        import hosted_release
        import verify_public_release as verify
        from release_metadata import ARCHIVE_LIMITS

        role = 'validated-unsigned-apk'
        sha = 'a' * 40
        run = dict(path='.github/workflows/build-release.yml', event='workflow_dispatch',
                   head_branch='main', head_sha=sha, head_repository={'full_name': verify.REPO})
        production = dict(id=5, name=role, expired=False, size_in_bytes=ARCHIVE_LIMITS[role][0],
                          digest='sha256:' + 'b' * 64, workflow_run={'id': 7, 'head_sha': sha})
        jobs = [{'jobs': [dict(name='unsigned', conclusion='success', run_id=7, head_sha=sha)]}]
        artifact_response = [{'artifacts': [production]}]
        evidence = dict(artifact_run_id=7, source_sha=sha, controller_sha=sha)
        metadata = MagicMock()
        metadata.api.return_value = production
        self.assertEqual(hosted_release.artifact(metadata, 5, role, 7, sha)['size_in_bytes'],
                         ARCHIVE_LIMITS[role][0])
        production['size_in_bytes'] += 1
        with self.assertRaisesRegex(ValueError, 'exact trusted run and role'):
            hosted_release.artifact(metadata, 5, role, 7, sha)
        with patch.object(verify, 'gh', side_effect=[run, jobs, artifact_response]), \
                patch.object(verify.subprocess, 'check_output') as download:
            with self.assertRaisesRegex(ValueError, 'Unsigned artifact run or size mismatch'):
                verify.unsigned_proof(evidence)
            download.assert_not_called()

    def test_unsigned_archive_checks_expanded_entries_before_reading_proof(self):
        import verify_public_release as verify
        from release_metadata import ARCHIVE_LIMITS

        sha = 'a' * 40
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, 'w') as archive:
            archive.writestr('oversized.log', b'0' * 64)
        raw = buffer.getvalue()
        asset = dict(id=5, name='validated-unsigned-apk', expired=False, size_in_bytes=len(raw),
                     digest='sha256:' + hashlib.sha256(raw).hexdigest(), workflow_run={'id': 7, 'head_sha': sha})
        run = dict(path='.github/workflows/build-release.yml', event='workflow_dispatch',
                   head_branch='main', head_sha=sha, head_repository={'full_name': verify.REPO})
        jobs = [{'jobs': [dict(name='unsigned', conclusion='success', run_id=7, head_sha=sha)]}]
        metadata = [run, jobs, [{'artifacts': [asset]}]]
        evidence = dict(artifact_run_id=7, source_sha=sha, controller_sha=sha)
        with patch.dict(ARCHIVE_LIMITS, {'validated-unsigned-apk': (len(raw), 63)}), \
                patch.object(verify.subprocess, 'check_output', return_value=raw), \
                patch.object(verify, 'gh', side_effect=metadata):
            with self.assertRaisesRegex(ValueError, 'oversized archive entries'):
                verify.unsigned_proof(evidence)

    def test_public_verification_rejects_routine_receipt_from_a_security_review_push(self):
        import verify_public_release as verify

        sha = 'a' * 40
        run = dict(path='.github/workflows/sync-upstream.yml', event='push', head_branch='main', head_sha=sha)
        evidence = dict(artifact_run_id=7, source_sha=sha, controller_sha=sha, maintenance={'kind': 'routine'})
        with patch.object(verify, 'gh', return_value=run) as gh:
            with self.assertRaisesRegex(ValueError, 'main-branch release producer'):
                verify.unsigned_proof(evidence)
            gh.assert_called_once_with('actions/runs/7')

    def test_public_discovery_uses_a_matching_public_redirect_on_rate_limit(self):
        import verify_public_release as verify

        tag = 'v3.19.0-private.27'
        record = {'tag_name': tag}
        response = MagicMock()
        response.__enter__.return_value.geturl.return_value = verify.PUBLIC + '/releases/tag/' + tag
        limited = HTTPError('https://api.github.com', 403, 'rate limited', {'X-RateLimit-Remaining': '0'}, None)
        with patch.object(verify, 'public_read', side_effect=limited), \
                patch.object(verify, 'urlopen', return_value=response), patch.object(verify, 'gh', return_value=record):
            self.assertEqual(verify.latest(tag), (record, 'public-latest-redirect-and-read-only-api'))
            response.__enter__.return_value.geturl.return_value = verify.PUBLIC + '/releases/tag/older'
            with self.assertRaisesRegex(ValueError, 'Public latest release redirect differs'):
                verify.latest(tag)

    def test_public_discovery_propagates_other_forbidden_responses(self):
        import verify_public_release as verify

        forbidden = HTTPError('https://api.github.com', 403, 'forbidden', {'X-RateLimit-Remaining': '12'}, None)
        with patch.object(verify, 'public_read', side_effect=forbidden), patch.object(verify, 'gh') as gh:
            with self.assertRaises(HTTPError):
                verify.latest('v3.19.0-private.27')
            gh.assert_not_called()

    def test_title_rejects_invalid_tags(self):
        for tag in (None,18,'v2.3.4','v2.3.4-private.18-extra','v2.3.4-private.18\n',
                    'v2.3.4-private.-1','v2.3.4-private.１８','v2.3.4-private.18; command'):
            with self.subTest(tag=tag),self.assertRaisesRegex(RuntimeError,'Invalid private release tag'):
                publish.release_title(tag)

    def test_notes_keep_technical_links_and_version_code(self):
        proof={'release_tag':'v2.3.4-private.18','versionCode':123456,'upstream':{'tag':'v2.3.4'}}
        with patch.object(Path,'read_text',return_value=json.dumps({proof['release_tag']:['Correct quote validation.']})):
            notes=publish.release_notes(proof)
        self.assertTrue(notes.startswith('ZEC Private — Release 18\n\n## What’s new\n\n- Correct quote validation.\n'))
        self.assertIn('Android versionCode: 123456',notes)
        self.assertIn('/blob/v2.3.4-private.18/docs/WALLET-GUIDE.md',notes)
        self.assertIn('/.private/release-evidence/v2.3.4-private.18.json',notes)
        self.assertIn('zec-private-latest.apk',notes)

    def test_notes_preserve_change_limits(self):
        proof={'release_tag':'v2.3.4-private.18','versionCode':123456,'upstream':{'tag':'v2.3.4'}}
        for changes in (['Change']*7,['x'*301],[''],[1],{}):
            with self.subTest(changes=changes):
                with patch.object(Path,'read_text',return_value=json.dumps({proof['release_tag']:changes})):
                    with self.assertRaisesRegex(ValueError, 'Release changes must contain'):
                        publish.release_notes(proof)


if __name__=='__main__': unittest.main()
