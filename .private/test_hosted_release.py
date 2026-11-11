import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import hosted_claim
import hosted_core
import hosted_release


SOURCE = 'a' * 40
CONTROLLER = 'b' * 40
RUN = 71
VERSION = 3234022


class HostedReleaseTests(unittest.TestCase):
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
            hosted_claim.rehearsal_version(VERSION - 1, CONTROLLER, metadata, number=7)

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
            (directory/'.private/hosted-fixture.json').write_text('{}')
            production = dict(id=5, digest='sha256:'+'c'*64, size_in_bytes=321)
            producer = dict(id=6, digest='sha256:'+'d'*64, size_in_bytes=123)
            quotes = dict(id=7, digest='sha256:'+'e'*64, size_in_bytes=456)
            common = dict(build_source_sha=SOURCE, candidate_version_code=VERSION,
                          production_artifact=production, workflow_run_id=RUN)
            core = dict(common, rehearsal=False, funding_setup={'claim_requests': 1},
                        hosted_reservation='d'*40, producer=producer)
            quote = dict(common, producer=quotes)
            proof = dict(workflow_run_id=str(RUN), versionCode=VERSION)

            def read(core, quote, proof=proof):
                (directory/'hosted-core.json').write_text(json.dumps(core))
                (directory/'hosted-quotes.json').write_text(json.dumps(quote))
                return hosted_release.read_receipts(directory, proof=proof, source=SOURCE, controller=CONTROLLER,
                                                    production_id=5, core_id=6, quote_id=7, metadata=Mock())

            with patch.dict(os.environ, {'GITHUB_RUN_ID': str(RUN)}), patch.object(hosted_release, 'ROOT', directory), \
                    patch.object(hosted_release, 'version_code', return_value=VERSION), \
                    patch.object(hosted_release, 'artifact', side_effect=lambda _, i, *a: {5: production, 6: producer, 7: quotes}[i]), \
                    patch.object(hosted_release, 'verify'), patch.object(hosted_release, 'validate_core_sources'), \
                    patch.object(hosted_release, 'validate_quotes'), patch.object(hosted_release, 'input_fingerprint'):
                accepted, _ = read(core, quote)
                self.assertEqual(accepted['artifact_id'], 6)
                for change in ({'candidate_version_code': VERSION+1}, {'workflow_run_id': RUN+1},
                               {'production_artifact': dict(production, id=8)}, {'build_source_sha': CONTROLLER}):
                    with self.subTest(change=change), self.assertRaisesRegex(ValueError, 'not bound'):
                        read(core, dict(quote, **change))
                with self.assertRaisesRegex(ValueError, 'one funded'):
                    read(dict(core, rehearsal=True), quote)
                with self.assertRaisesRegex(ValueError, 'different test APK producer'):
                    read(dict(core, producer=quotes), quote)


if __name__ == '__main__':
    unittest.main()
