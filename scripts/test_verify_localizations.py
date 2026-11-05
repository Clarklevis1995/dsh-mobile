import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


spec = importlib.util.spec_from_file_location(
    "verify_localizations", Path(__file__).with_name("verify-localizations.py")
)
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class SwiftLocalizationChecks(unittest.TestCase):
    def test_compiler_integer_keys_must_match_exactly(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "App" / "Example.swift"
            (root / "Example.stringsdata").write_text(json.dumps({
                "source": str(source),
                "tables": {"Localizable": [{"key": "%d 个附件"}]},
            }))
            with patch.object(verifier, "ROOT", root), patch.object(verifier, "IOS_SOURCES", source.parent):
                errors = []
                verifier.check_swift_extractions(root, {"%@ 个附件"}, errors)
                self.assertEqual(len(errors), 1)
                errors = []
                verifier.check_swift_extractions(root, {"%d 个附件"}, errors)
                self.assertEqual(errors, [])

    def test_escaped_literals_and_widget_labels_are_checked(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app = root / "App"
            widget = root / "AgentLiveActivityWidget"
            app.mkdir()
            widget.mkdir()
            (app / "Example.swift").write_text(
                'Text("第一行\\n第二行")\nString(localized: "带\\"引号\\"")\n'
            )
            (widget / "Example.swift").write_text('Text("查看详情")\n')
            keys = {"第一行\n第二行", '带"引号"', "查看详情"}
            with patch.object(verifier, "ROOT", root), patch.object(verifier, "IOS_SOURCES", app):
                errors = []
                verifier.check_swift_calls(keys, errors)
                self.assertEqual(errors, [])
                verifier.check_swift_calls(keys - {"查看详情"}, errors)
                self.assertEqual(len(errors), 1)
                self.assertIn("AgentLiveActivityWidget/Example.swift", errors[0])


if __name__ == "__main__":
    unittest.main()
