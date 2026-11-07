"""Exercise release copy without importing the signing controller or calling GitHub."""
import ast
import json
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]
tree=ast.parse((ROOT/'.private/publish.py').read_text())
function=next(node for node in tree.body if isinstance(node,ast.FunctionDef) and node.name=='release_notes')
namespace={'ROOT':ROOT,'REPO':'example/wallet','json':json}
exec(compile(ast.Module(body=[function],type_ignores=[]),'release_notes','exec'),namespace)


class ReleaseNotesTest(unittest.TestCase):
    def test_curated_changes_and_update_marker(self):
        proof={'release_tag':'v3.19.0-private.11','versionCode':3234011,'upstream':{'tag':'3.19.0'}}
        body=namespace['release_notes'](proof)
        self.assertIn('Correct swap amount precision',body)
        self.assertIn('Android versionCode: 3234011\n',body)
        self.assertIn('/release-evidence/v3.19.0-private.11.json',body)
        self.assertNotIn('Validated:',body)
        self.assertLess(len(body),1200)

    def test_new_upstream_does_not_repeat_previous_changes(self):
        proof={'release_tag':'v3.20.0-private.1','versionCode':3240001,'upstream':{'tag':'3.20.0'}}
        body=namespace['release_notes'](proof)
        self.assertNotIn('Correct swap amount precision',body)
        self.assertIn('/releases/tag/3.20.0',body)
        self.assertIn('ARM64',body)


if __name__=='__main__': unittest.main()
