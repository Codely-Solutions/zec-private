"""Release display metadata checks; no signing, build or publication."""
from contextlib import chdir
import importlib.util
import io
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile
from unittest.mock import MagicMock, patch
from urllib.error import HTTPError
from test_support import patch_environment

spec=importlib.util.spec_from_file_location('private_publish',Path(__file__).with_name('publish.py'))
publish=importlib.util.module_from_spec(spec)
with patch_environment({'GITHUB_REPOSITORY':'example/zec-private'}):
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
            proof = dict(release_tag='v3.19.0-private.31', source_sha=candidate, controller_sha=original,
                         versionCode=3234034, versionName='3.19.0',
                         upstream={'tag': '3.19.0-3234', 'versionCode': 3234034, 'sha': upstream},
                         maintenance={'kind': 'custom'}, publisher={'run': '7', 'attempt': '1'},
                         artifact_run_id='11', production_artifact={'id': 12},
                         production_job_id=13, apk_sha256='a' * 64)
            with tempfile.TemporaryDirectory() as temp:
                runner = Path(temp)
                publication = runner / 'publication'
                publication.mkdir()
                output = runner / 'output'

                def integrate(head, attestation=proof, attempt='1', remote=None):
                    git('checkout', '-q', '--detach', head)
                    data = integrate_release.evidence_bytes(attestation)
                    (publication / 'release-attestation.json').write_bytes(data)
                    output.unlink(missing_ok=True)
                    env = dict(GITHUB_REF='refs/heads/main', GITHUB_SHA=head, GITHUB_OUTPUT=str(output),
                               RUNNER_TEMP=str(runner), ATTESTATION_DIGEST=hashlib.sha256(data).hexdigest(),
                               ATTESTATION_CONTROLLER=original, ATTESTATION_RUN='7', ATTESTATION_ATTEMPT=attempt)
                    with chdir(root), patch_environment(env), \
                            patch('publish.current_main', return_value=remote or head):
                        integrate_release.main(root)
                    return dict(line.split('=', 1) for line in output.read_text().splitlines())

                first = integrate(controller)
                self.assertEqual(first['already_integrated'], 'false')
                integrated = first['commit']
                self.assertEqual(git('rev-parse', integrated + '^^1'), controller)
                self.assertEqual(git('rev-parse', integrated + '^^2'), candidate)
                self.assertEqual(git('rev-parse', integrated + '^1^{tree}'), git('rev-parse', result + '^{tree}'))
                self.assertEqual(integrate(integrated), dict(commit=integrated, already_integrated='true'))
                with self.assertRaisesRegex(ValueError, 'different publisher run or attempt'):
                    integrate(integrated, attempt='2')
                with self.assertRaisesRegex(RuntimeError, 'Main changed since validation began'):
                    integrate(integrated, remote=controller)
                for changed in (dict(production_job_id=14), dict(production_artifact={'id': 99}),
                                dict(artifact_run_id='15'), dict(core={'passed': False})):
                    with self.subTest(changed=changed), self.assertRaisesRegex(RuntimeError, 'evidence conflicts'):
                        integrate(integrated, dict(proof, **changed))
                    self.assertFalse(output.exists())
                # A later update can retain the exact approved attestation and both ancestors.
                git('checkout', '-q', '--detach', integrated)
                (root / 'later.txt').write_text('Later protected change')
                later = commit('later protected main')
                self.assertEqual(integrate(later), dict(commit=later, already_integrated='true'))
                # An identical blob on a branch missing the attested source cannot acknowledge integration.
                with tempfile.TemporaryDirectory() as index:
                    incomplete = state_commit(controller, proof, Path(index), root)
                with self.assertRaises(subprocess.CalledProcessError):
                    integrate(incomplete)
                self.assertFalse(output.exists())
                legacy = {key: value for key, value in proof.items() if key != 'publisher'}
                with tempfile.TemporaryDirectory() as index:
                    legacy_head = state_commit(result, legacy, Path(index), root)
                self.assertEqual(integrate(legacy_head, legacy),
                                 dict(commit=legacy_head, already_integrated='true'))
                git('checkout', '-q', '--detach', controller)
            properties.write_bytes(properties.read_bytes() + b'UNREVIEWED_SETTING=true\n')
            advanced = commit('unrelated main update')
            with self.assertRaisesRegex(RuntimeError, 'outside publication recovery controls'):
                integrate_release.integration_base(candidate, advanced, original, root)

    def test_push_readback_requires_the_exact_prepared_commit(self):
        commit = 'a' * 40
        command = ['git', 'push', 'origin', f'{commit}:refs/heads/main']
        for error in (None, subprocess.CalledProcessError(1, command), subprocess.TimeoutExpired(command, 90)):
            with self.subTest(error=error), patch.object(integrate_release.subprocess, 'run', side_effect=error) as push, \
                    patch.object(integrate_release, 'current_main', return_value=commit) as read:
                integrate_release.push_integration(commit)
                push.assert_called_once_with(command, check=True, timeout=90)
                if error is None:
                    read.assert_not_called()
                else:
                    read.assert_called_once_with()
        with patch.object(integrate_release.subprocess, 'run',
                          side_effect=subprocess.CalledProcessError(1, command)) as push:
            with patch.object(integrate_release, 'current_main', return_value='b' * 40), \
                    self.assertRaises(subprocess.CalledProcessError):
                integrate_release.push_integration(commit)
            with patch.object(integrate_release, 'current_main', side_effect=subprocess.TimeoutExpired('gh', 120)), \
                    self.assertRaises(subprocess.TimeoutExpired):
                integrate_release.push_integration(commit)
            push.reset_mock()
            with self.assertRaises(ValueError):
                integrate_release.push_integration('main')
            push.assert_not_called()

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
                    patch_environment({'GITHUB_REF': ref}), \
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
        admitted = dict(job, name='discover / build / publish')
        self.assertEqual(publisher_job(run, [admitted], 7, repo), admitted)
        with self.assertRaisesRegex(ValueError, 'publisher job'):
            publisher_job(run, [job, admitted], 7, repo)
        custom = dict(run, path='.github/workflows/custom-release.yml', event='workflow_dispatch')
        self.assertEqual(publisher_job(custom, [job], 7, repo), job)
        with self.assertRaisesRegex(ValueError, 'publisher job'):
            publisher_job(custom, [admitted], 7, repo)
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
                     controller_sha=controller, maintenance={'kind': 'routine'}, publisher={'run': '7', 'attempt': '1'})
        buffer = io.BytesIO()
        proof_bytes = json.dumps(proof).encode()
        with zipfile.ZipFile(buffer, 'w') as archive:
            archive.writestr('release-attestation.json', proof_bytes)
        raw = buffer.getvalue()
        artifact = dict(id=53, name='release-attestation-1', expired=False,
                        workflow_run=dict(id=7, head_sha=controller), size_in_bytes=len(raw),
                        digest='sha256:' + hashlib.sha256(raw).hexdigest(), created_at='2026-11-23T08:06:12Z')
        run = dict(id=7, run_attempt=1, path='.github/workflows/sync-upstream.yml', event='schedule',
                   head_branch='main', head_repository={'full_name': 'example/zec-private'},
                   status='completed', head_sha=controller, run_started_at='2026-11-23T08:00:00Z')
        steps = [
            dict(name='Sign and verify the validated APK', number=5, status='completed', conclusion='success',
                 started_at='2026-11-23T08:05:00Z', completed_at='2026-11-23T08:06:00Z'),
            dict(name='Run actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9',
                 number=6, status='completed', conclusion='success',
                 started_at='2026-11-23T08:06:10Z', completed_at='2026-11-23T08:06:15Z'),
            dict(name='Publish the signed APK and checksum', number=7, status='completed', conclusion='success',
                 started_at='2026-11-23T08:06:15Z', completed_at='2026-11-23T08:06:29Z'),
        ]
        job = dict(id=75, name='build / publish', conclusion='success', status='completed', run_id=7,
                   run_attempt=1, head_sha=controller, started_at='2026-11-23T08:03:00Z',
                   completed_at='2026-11-23T08:06:30Z', steps=steps, runner_id=85)
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
        with patch_environment(GITHUB_REPOSITORY='example/zec-private'), \
                patch.object(reconcile, 'gh', side_effect=lambda path, *_: routes[path]) as api, \
                patch.object(reconcile.subprocess, 'check_output', return_value=raw) as download:
            result = reconcile.resolve('7', '1', '53')
            self.assertEqual((result['attestation_attempt'], result['attestation_id']), (1, 53))
            self.assertEqual(reconcile.resolve('7'), result)
            self.assertNotIn('actions/runs/7', [call.args[0] for call in api.call_args_list])

            for interruption in ('failure', 'cancelled'):
                with self.subTest(interruption=interruption):
                    job.update(conclusion=interruption, steps=[*steps[:2], dict(steps[2], conclusion=interruption)])
                    self.assertEqual(reconcile.resolve('7', '1', '53'), result)
            draft = self.draft(proof)
            with patch.object(reconcile, 'release_for_tag', return_value=draft):
                self.assertEqual(reconcile.lookup('7', '1', '53', allow_draft=True), (result, proof, draft))

            for failing_step in (0, 1):
                with self.subTest(failing_step=failing_step):
                    failed = [step.copy() for step in steps]
                    failed[failing_step]['conclusion'] = 'failure'
                    job.update(conclusion='failure', steps=failed)
                    download.reset_mock()
                    with self.assertRaisesRegex(ValueError, 'successful publisher signing and attestation upload'):
                        reconcile.resolve('7', '1', '53')
                    download.assert_not_called()
            job.update(conclusion='success', steps=steps)

            # A copied job can retain the original step timestamps and status under a new attempt.
            copy = dict(job, id=76, run_attempt=2)
            second = dict(run, run_attempt=2, run_started_at='2026-11-23T08:20:00Z')
            copied_artifact = dict(artifact, id=54, name='release-attestation-2')
            routes['actions/runs/7/attempts/2'] = second
            routes['actions/runs/7/attempts/2/jobs?per_page=100'] = [{'jobs': [copy]}]
            routes['actions/runs/7/artifacts?per_page=100'][0]['artifacts'].append(copied_artifact)
            download.reset_mock()
            with self.assertRaisesRegex(ValueError, 'Expected one completed attestation producer'):
                reconcile.resolve('7', '2', '54')
            download.assert_not_called()
            self.assertEqual(reconcile.resolve('7'), result)

            # Another job can upload into the same run during the publisher's job window.
            publisher_archive_time = artifact['created_at']
            artifact['created_at'] = '2026-11-23T08:06:20Z'
            other_step = dict(steps[1], started_at='2026-11-23T08:06:18Z', completed_at='2026-11-23T08:06:23Z')
            other_job = dict(job, id=77, name='build / unrelated', steps=[other_step])
            routes['actions/runs/7/attempts/1/jobs?per_page=100'][0]['jobs'].append(other_job)
            download.reset_mock()
            with self.assertRaisesRegex(ValueError, 'original publisher upload step'):
                reconcile.resolve('7', '1', '53')
            download.assert_not_called()
            artifact['created_at'] = publisher_archive_time

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
            with patch_environment(env, clear=True), patch.object(reconcile, 'resolve', return_value=proof) as resolve:
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

                with patch_environment(env), patch.object(reconcile, 'lookup', return_value=({}, proof, draft)), \
                        patch.object(reconcile, 'unsigned_proof', return_value=(unsigned, proof, {}, 11)), \
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
        run = dict(id=7, path='.github/workflows/build-release.yml', event='workflow_dispatch',
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
                     created_at='2026-11-21T00:10:00Z',
                     digest='sha256:' + hashlib.sha256(raw).hexdigest(), workflow_run={'id': 7, 'head_sha': sha})
        run = dict(id=7, run_attempt=1, run_started_at='2026-11-21T00:00:00Z',
                   path='.github/workflows/build-release.yml', event='workflow_dispatch',
                   head_branch='main', head_sha=sha, head_repository={'full_name': verify.REPO})
        job = dict(id=11, name='unsigned', conclusion='success', status='completed', run_id=7, run_attempt=1, head_sha=sha,
                   started_at='2026-11-21T00:01:00Z', completed_at='2026-11-21T00:12:00Z')
        jobs = [{'jobs': [job]}]
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
        run = dict(id=7, path='.github/workflows/sync-upstream.yml', event='push', head_branch='main', head_sha=sha)
        evidence = dict(artifact_run_id=7, source_sha=sha, controller_sha=sha, maintenance={'kind': 'routine'})
        with patch.object(verify, 'gh', return_value=run) as gh:
            with self.assertRaisesRegex(ValueError, 'main-branch release producer'):
                verify.unsigned_proof(evidence)
            gh.assert_called_once_with('actions/runs/7')

    def test_public_verification_retains_the_exact_unsigned_producer_after_later_attempt(self):
        import verify_public_release as verify

        source, controller = 'a' * 40, 'b' * 40
        run = dict(id=7, run_attempt=2, run_started_at='2026-11-21T00:55:00Z',
                   path='.github/workflows/sync-upstream.yml', event='schedule',
                   head_branch='main', head_sha=controller, head_repository={'full_name': verify.REPO})
        first_attempt = dict(run, run_attempt=1, run_started_at='2026-11-21T00:00:00Z')
        candidate = io.BytesIO()
        with zipfile.ZipFile(candidate, 'w') as apk:
            apk.writestr('AndroidManifest.xml', b'original validated APK')
        apk = candidate.getvalue()
        proof = dict(source_sha=source, workflow_run_id='7', apk_sha256=verify.digest(apk))
        producer = dict(id=11, name='build / unsigned', conclusion='success', status='completed',
                        run_id=7, run_attempt=1, head_sha=controller, started_at='2026-11-21T00:01:00Z',
                        completed_at='2026-11-21T00:31:00Z')
        later_producer = dict(producer, id=12, run_attempt=2, started_at='2026-11-21T01:00:00Z',
                              completed_at='2026-11-21T01:31:00Z')

        def archive(artifact_id, created_at, raw_proof):
            buffer = io.BytesIO()
            with zipfile.ZipFile(buffer, 'w') as outer:
                outer.writestr('zec-private-unsigned.apk', apk)
                outer.writestr('proof.json', json.dumps(raw_proof).encode())
            data = buffer.getvalue()
            asset = dict(id=artifact_id, name='validated-unsigned-apk', expired=False, size_in_bytes=len(data),
                         digest='sha256:' + verify.digest(data), created_at=created_at,
                         workflow_run={'id': 7, 'head_sha': controller})
            return asset, data

        def binding(asset):
            return {key: asset[key] for key in ('id', 'digest', 'size_in_bytes')}

        original, raw = archive(53, '2026-11-21T00:30:00Z', proof)
        later, _ = archive(54, '2026-11-21T01:30:00Z', proof)
        wrong_source, wrong_raw = archive(53, original['created_at'], dict(proof, source_sha='c' * 40))
        jobs = [producer, later_producer]
        artifacts = [later, original]
        legacy = dict(artifact_run_id=7, source_sha=source, controller_sha=controller,
                      production_artifact=binding(original))
        exact = dict(legacy, production_job_id=11)
        for attestation in (legacy, exact):
            with self.subTest(attestation=attestation), \
                    patch.object(verify, 'gh', side_effect=[run, [{'jobs': jobs}], [{'artifacts': artifacts}],
                                                           first_attempt]), \
                    patch.object(verify.subprocess, 'check_output', return_value=raw) as download:
                self.assertEqual(verify.unsigned_proof(attestation), (apk, proof, binding(original), 11))
                self.assertTrue(download.call_args.args[0][-1].endswith('/actions/artifacts/53/zip'))

        bad_creator = dict(original, workflow_run={'id': 7, 'head_sha': 'c' * 40})
        unbound_legacy = {key: value for key, value in legacy.items() if key != 'production_artifact'}
        cases = (
            (unbound_legacy, jobs, artifacts, 'one live validated unsigned artifact'),
            (dict(exact, production_artifact=dict(binding(original), id=55)), jobs, artifacts,
             'one live validated unsigned artifact'),
            (dict(exact, production_artifact=dict(binding(original), digest='sha256:' + '0' * 64)),
             jobs, artifacts, 'accepted unsigned artifact'),
            (exact, jobs, [later, bad_creator], 'artifact run or size mismatch'),
            (exact, jobs, [original, dict(original), later], 'one live validated unsigned artifact'),
            (exact, [*jobs, dict(producer)], artifacts, 'one record per matching unsigned producer job'),
            (dict(exact, production_artifact=binding(later)), jobs, artifacts, 'exact attested producer job'),
            (dict(legacy, production_artifact=binding(later)),
             [producer, dict(later_producer, conclusion='failure')], artifacts,
             'one successful unsigned producer job'),
        )
        for attestation, reported_jobs, reported_artifacts, error in cases:
            with self.subTest(error=error, attestation=attestation), \
                    patch.object(verify, 'gh', side_effect=[run, [{'jobs': reported_jobs}],
                                                           [{'artifacts': reported_artifacts}], first_attempt]), \
                    patch.object(verify.subprocess, 'check_output') as download:
                with self.assertRaisesRegex(ValueError, error):
                    verify.unsigned_proof(attestation)
                download.assert_not_called()

        with patch.object(verify, 'gh', side_effect=[run, [{'jobs': jobs}], [{'artifacts': [wrong_source, later]}],
                                                    first_attempt]), \
                patch.object(verify.subprocess, 'check_output', return_value=wrong_raw):
            with self.assertRaisesRegex(ValueError, 'does not match recorded source'):
                verify.unsigned_proof(dict(exact, production_artifact=binding(wrong_source)))

    def test_copied_unsigned_job_resolves_only_to_the_original_execution(self):
        import verify_public_release as verify

        first = dict(id=7, run_attempt=1, head_sha='a' * 40, run_started_at='2026-11-20T19:00:36Z')
        second = dict(first, run_attempt=2, run_started_at='2026-11-20T19:31:31Z')
        artifact = {'created_at': '2026-11-20T19:16:13Z'}
        original = dict(
            id=11, run_attempt=1, status='completed', conclusion='success', runner_id=42,
            started_at='2026-11-20T19:03:26Z', completed_at='2026-11-20T19:16:17Z',
            steps=[dict(number=1, name='Build unsigned APK', started_at='2026-11-20T19:04:00Z',
                        completed_at='2026-11-20T19:15:00Z', status='completed', conclusion='success')],
        )
        copied = dict(original, id=12, run_attempt=2)
        with patch.object(verify, 'gh', return_value=first) as gh:
            self.assertEqual(verify.unsigned_producer_job(second, [copied, original], artifact), 11)
            gh.assert_called_once_with('actions/runs/7/attempts/1')
            with self.assertRaisesRegex(ValueError, 'exact attested producer job'):
                verify.unsigned_producer_job(second, [original, copied], artifact, expected_id=12)
        with patch.object(verify, 'gh', return_value=first):
            with self.assertRaisesRegex(ValueError, 'one successful unsigned producer'):
                verify.unsigned_producer_job(second, [copied], artifact)
            for altered in (dict(copied, runner_id=43), dict(copied, steps=[])):
                with self.subTest(altered=altered), self.assertRaisesRegex(ValueError, 'Copied unsigned producer'):
                    verify.unsigned_producer_job(second, [original, altered], artifact, expected_id=11)
            overlapping = dict(second, run_started_at='2026-11-20T19:08:00Z')
            genuine = dict(copied, started_at='2026-11-20T19:09:00Z', completed_at='2026-11-20T19:18:00Z')
            with self.assertRaisesRegex(ValueError, 'one successful unsigned producer'):
                verify.unsigned_producer_job(overlapping, [original, genuine], artifact, expected_id=11)

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
