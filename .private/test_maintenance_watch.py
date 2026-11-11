import unittest

from maintenance_watch import classify_paths, trusted_proposal, wrapper_patch


class MaintenanceWatchTest(unittest.TestCase):
    def test_wrapper_patch_requires_exact_official_coordinates_and_settings(self):
        base = ('distributionUrl=https\\://services.gradle.org/distributions/gradle-9.9.1-bin.zip\n'
                'distributionSha256Sum=' + 'a'*64 + '\nnetworkTimeout=10000\n')
        proposed = base.replace('9.9.1', '9.9.2').replace('a'*64, 'b'*64)
        versions = []

        def checksum(version):
            versions.append(version)
            return 'b'*64

        self.assertEqual(wrapper_patch(base, proposed, checksum), '9.9.2')
        self.assertEqual(versions, ['9.9.2'])
        for untrusted in (proposed.replace('services.gradle.org', 'example.invalid'),
                          proposed.replace('9.9.2', '9.10.1'),
                          proposed.replace('10000', '20000'),
                          proposed + ' distributionUrl=http://example.invalid\n',
                          proposed.replace('b'*64, 'c'*64)):
            with self.subTest(untrusted=untrusted), self.assertRaises(ValueError):
                wrapper_patch(base, untrusted, checksum)

    def test_controller_file_cannot_enter_the_dependency_queue(self):
        files = [{'filename': 'gradle.properties', 'status': 'modified'}]
        self.assertIn('needs provenance', classify_paths(files, 1))
        self.assertEqual('incomplete diff', classify_paths(files, 2))
        files[0].update(status='renamed', previous_filename='.private/publish.py')
        self.assertIn('controller change', classify_paths(files, 1))
        files[0].update(filename='.github/workflows/build-release.yml', previous_filename='gradle.properties')
        self.assertIn('controller change', classify_paths(files, 1))

    def test_repository_and_bot_identity_are_required(self):
        pr = {'state': 'open', 'draft': False, 'user': {'login': 'dependabot[bot]', 'type': 'Bot'},
              'head': {'repo': {'id': 7}, 'ref': 'dependabot/gradle/update', 'sha': 'a' * 40},
              'base': {'repo': {'id': 7}, 'ref': 'main'}}
        self.assertTrue(trusted_proposal(pr, 7))
        self.assertFalse(trusted_proposal(dict(pr, user={'login': 'dependabot[bot]', 'type': 'User'}), 7))
        self.assertFalse(trusted_proposal(dict(pr, head=dict(pr['head'], repo={'id': 8})), 7))


if __name__ == '__main__':
    unittest.main()
