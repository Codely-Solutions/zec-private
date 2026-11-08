"""Offline preflight regressions; no Java, Gradle, network or emulator."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import preflight


class PreflightTests(unittest.TestCase):
    def test_broken_controller_and_missing_binding_are_actionable(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / '.private').mkdir()
            (root / '.github/workflows').mkdir(parents=True)
            (root / '.private/controller.py').write_text('def broken(:\n')
            (root / '.github/workflows/build.yml').write_text('run: python3 .private/missing.py\n')
            errors = preflight.controller_checks(root)
            self.assertTrue(any('controller.py:1' in error for error in errors))
            self.assertTrue(any('missing.py' in error for error in errors))

    def test_active_document_links_resolve_and_external_links_are_ignored(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'README.md').write_text('[guide](guide%20one.md) [web](https://example.org/x)')
            errors = preflight.documentation_links(root, ('README.md',))
            self.assertEqual(len(errors), 1)
            self.assertIn('guide one.md', errors[0])
            (root / 'guide one.md').write_text('Guide')
            self.assertEqual(preflight.documentation_links(root, ('README.md',)), [])
            self.assertTrue(preflight.documentation_links(root, ('missing.md',)))

    def test_changed_long_kotlin_declaration_reports_exact_line(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'module/src/main').mkdir(parents=True)
            (root / 'module/src/main/Flow.kt').write_text('val a = 1\n')
            responses = ['module/src/main/Flow.kt\0', '@@ -2,0 +3 @@\n+' + 'x' * 121 + '\n']
            with patch.object(preflight, 'git', side_effect=responses):
                self.assertEqual(preflight.changed_kotlin(root, 'HEAD'),
                                 ['module/src/main/Flow.kt:3: changed Kotlin line exceeds 120 columns'])

    def test_deleted_kotlin_file_needs_no_read(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(preflight, 'git', return_value='Gone.kt\0'):
            self.assertEqual(preflight.changed_kotlin(Path(temp), 'HEAD'), [])

    def test_standalone_state_requires_matching_file_but_named_compose_pair_is_allowed(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'DonationView.kt'
            path.write_text('internal data class DonationViewState(val enabled: Boolean)\n')
            self.assertTrue(preflight.declaration_name_errors(path, path.name))
            path.write_text('internal data class DonationViewState(val enabled: Boolean)\n'
                            '@Composable\nfun DonationView() {}\n')
            self.assertEqual(preflight.declaration_name_errors(path, path.name), [])
            state = Path(temp) / 'DonationViewState.kt'
            state.write_text('internal data class DonationViewState(val enabled: Boolean)\n')
            self.assertEqual(preflight.declaration_name_errors(state, state.name), [])
            path.write_text('@Composable\nfun DonationView() {}\n')
            self.assertEqual(preflight.declaration_name_errors(path, path.name), [])

    def test_named_compose_control_can_share_its_defaults(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'ExampleControl.kt'
            path.write_text('@Composable\nfun ExampleControl() {}\nobject ExampleControlDefaults {}\n')
            self.assertEqual(preflight.declaration_name_errors(path, path.name), [])


if __name__ == '__main__':
    unittest.main()
