"""Exercise release copy without importing the signing controller or calling GitHub."""
import ast
import json
import os
import re
from pathlib import Path
import unittest
from unittest.mock import patch

from release_changelog import changes_for, custom_changes, maintenance_changes

ROOT=Path(__file__).resolve().parents[1]
tree=ast.parse((ROOT/'.private/publish.py').read_text())
functions=[node for node in tree.body if isinstance(node,ast.FunctionDef)
           and node.name in {'release_title','release_notes'}]
namespace={'ROOT':ROOT,'REPO':'example/wallet','json':json,'re':re,'os':os,'changes_for':changes_for}
exec(compile(ast.Module(body=functions,type_ignores=[]),'release_notes','exec'),namespace)


class ReleaseNotesTest(unittest.TestCase):
    def test_curated_changes_and_update_marker(self):
        proof={'release_tag':'v3.19.0-private.11','versionCode':3234011,'upstream':{'tag':'3.19.0'}}
        body=namespace['release_notes'](proof)
        self.assertIn('Correct swap amount precision',body)
        self.assertIn('Android versionCode: 3234011\n',body)
        self.assertIn('/release-evidence/v3.19.0-private.11.json',body)
        self.assertNotIn('Validated:',body)
        self.assertLess(len(body),1200)

    def test_new_upstream_describes_the_verified_changes(self):
        proof={'release_tag':'v3.20.0-private.1','versionCode':3240001,'upstream':{'tag':'3.20.0-3240'}}
        changes=maintenance_changes('3.20.0-3240', {}, upstream_changed=True, wrapper_changed=True)
        body=namespace['release_notes'](proof, changes)
        self.assertNotIn('Correct swap amount precision',body)
        self.assertIn('Update the upstream wallet to 3.20.0-3240.',body)
        self.assertIn('Update the Gradle build toolchain.',body)
        self.assertIn('ARM64',body)

    def test_unlisted_custom_release_cannot_publish_empty_notes(self):
        proof={'release_tag':'v3.20.0-private.1','versionCode':3240001,'upstream':{'tag':'3.20.0'}}
        with self.assertRaisesRegex(ValueError, 'Add a user-facing changelog'):
            namespace['release_notes'](proof)

    def test_custom_changelog_can_only_add_its_own_tag(self):
        original = {'v3.19.0-private.21': ['Previous release']}
        cases = (
            (dict(original, **{'v3.19.0-private.22': ['Show the minimum deposit.']}), None),
            (original, 'Add a user-facing changelog'),
            ({'v3.19.0-private.21': ['Changed history'], 'v3.19.0-private.22': ['Current']}, 'only its own'),
            (dict(original, **{'v3.19.0-private.22': ['Line one\nLine two']}), 'single-line'),
        )
        for proposed, error in cases:
            with self.subTest(error=error), patch('release_changelog.git', side_effect=[
                json.dumps(original).encode(), json.dumps(proposed).encode(),
                b'ZCASH_VERSION_NAME=3.19.0\n', b'{"private_revision":22}',
            ]):
                if error:
                    with self.assertRaisesRegex(ValueError, error):
                        custom_changes(ROOT, 'source', 'controller', required=True)
                else:
                    self.assertEqual(custom_changes(ROOT, 'source', 'controller', required=True),
                                     ['Show the minimum deposit.'])


if __name__=='__main__': unittest.main()
