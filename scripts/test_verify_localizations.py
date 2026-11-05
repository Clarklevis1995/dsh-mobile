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
                verifier.check_swift_extractions(root, {"Localizable": {"%@ 个附件"}}, errors)
                self.assertEqual(len(errors), 1)
                errors = []
                verifier.check_swift_extractions(root, {"Localizable": {"%d 个附件"}}, errors)
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
                verifier.check_swift_calls({"Localizable": keys}, errors)
                self.assertEqual(errors, [])
                verifier.check_swift_calls({"Localizable": keys - {"查看详情"}}, errors)
                self.assertEqual(len(errors), 1)
                self.assertIn("AgentLiveActivityWidget/Example.swift", errors[0])

    def test_swift_unescape_handles_swift_only_escapes(self):
        self.assertEqual(verifier.swift_unescape(r"a\tb\nc\u{1F600}\'d"), "a\tb\nc\U0001F600'd")
        with self.assertRaises(ValueError):
            verifier.swift_unescape(r"\q")

    def test_diagnostics_table_is_checked_separately(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app = root / "App"
            app.mkdir()
            (app / "Example.swift").write_text(
                'let a = String(localized: "诊断说明", table: "Diagnostics")\n'
                'let b = String(localized: "用户文案")\n'
            )
            with patch.object(verifier, "ROOT", root), patch.object(verifier, "IOS_SOURCES", app):
                errors = []
                verifier.check_swift_calls(
                    {"Localizable": {"用户文案"}, "Diagnostics": {"诊断说明"}}, errors
                )
                self.assertEqual(errors, [])
                errors = []
                # 同一个 key 放错表也必须报错。
                verifier.check_swift_calls(
                    {"Localizable": {"用户文案", "诊断说明"}, "Diagnostics": set()}, errors
                )
                self.assertEqual(len(errors), 1)
                self.assertIn("Diagnostics.xcstrings", errors[0])

    def test_compiler_extractions_are_checked_per_table(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "App" / "Example.swift"
            (root / "Example.stringsdata").write_text(json.dumps({
                "source": str(source),
                "tables": {"Diagnostics": [{"key": "诊断说明"}]},
            }))
            with patch.object(verifier, "ROOT", root), patch.object(verifier, "IOS_SOURCES", source.parent):
                errors = []
                verifier.check_swift_extractions(
                    root, {"Localizable": {"诊断说明"}, "Diagnostics": {"诊断说明"}}, errors
                )
                self.assertEqual(errors, [])
                errors = []
                verifier.check_swift_extractions(root, {"Localizable": {"诊断说明"}, "Diagnostics": set()}, errors)
                self.assertEqual(len(errors), 1)
                self.assertIn("Diagnostics.xcstrings", errors[0])


class CatalogChecks(unittest.TestCase):
    def catalog(self, root: Path, entries: dict) -> None:
        (root / "Localizable.xcstrings").write_text(json.dumps({
            "sourceLanguage": "zh-Hans",
            "strings": entries,
            "version": "1.0",
        }))

    def test_stale_entries_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            resources = Path(directory)
            self.catalog(resources, {
                "写入": {
                    "extractionState": "stale",
                    "localizations": {"en": {"stringUnit": {"state": "translated", "value": "Write"}}},
                },
            })
            with patch.object(verifier, "IOS_RESOURCES", resources):
                errors = []
                keys = verifier.check_catalog("Localizable", errors)
            self.assertEqual(keys, set())
            self.assertEqual(len(errors), 1)
            self.assertIn("已过时", errors[0])

    def test_translation_states_and_arguments_are_checked(self):
        with tempfile.TemporaryDirectory() as directory:
            resources = Path(directory)
            self.catalog(resources, {
                "写入": {
                    "extractionState": "manual",
                    "localizations": {
                        "en": {"stringUnit": {"state": "translated", "value": "Write"}},
                        "zh-Hans": {"stringUnit": {"state": "needs_review", "value": "写入"}},
                    },
                },
                "%lld 个文件": {
                    "extractionState": "manual",
                    "localizations": {
                        "en": {"stringUnit": {"state": "translated", "value": "%@ files"}},
                    },
                },
            })
            with patch.object(verifier, "IOS_RESOURCES", resources):
                errors = []
                keys = verifier.check_catalog("Localizable", errors)
            self.assertEqual(keys, {"写入", "%lld 个文件"})
            self.assertTrue(any("未审定" in error for error in errors))
            self.assertTrue(any("格式参数不一致" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
