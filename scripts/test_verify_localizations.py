"""verify-localizations.py 的行为测试：每个用例对应守卫必须拦截或必须放行的一类写法。"""

from __future__ import annotations

import importlib.util
import json
from unittest.mock import patch
import plistlib
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "verify-localizations.py"
spec = importlib.util.spec_from_file_location("verify_localizations", SCRIPT)
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


class LiteralScanTests(unittest.TestCase):
    """check_untranslated_literals：中文字面量必须经过本地化入口或带 i18n-ignore 说明。"""

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.saved = (verify.ROOT, verify.SCAN_ROOTS)
        verify.ROOT = self.root
        verify.SCAN_ROOTS = (
            (self.root / "ios", ".swift", "ios"),
            (self.root / "android", ".kt", "android"),
            (self.root / "shared", ".kt", "shared"),
        )

    def tearDown(self) -> None:
        verify.ROOT, verify.SCAN_ROOTS = self.saved
        self.temp.cleanup()

    def scan(self, platform: str, source: str, ios_keys: set[str] = frozenset(),
             android_keys: set[str] = frozenset()) -> list[str]:
        suffix = ".swift" if platform == "ios" else ".kt"
        path = self.root / platform / f"Sample{suffix}"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source)
        errors: list[str] = []
        verify.check_untranslated_literals(set(ios_keys), set(android_keys), {platform}, errors)
        return errors

    def test_swift_plain_literal_is_rejected(self) -> None:
        errors = self.scan("ios", 'let title = "你好"\n')
        self.assertEqual(len(errors), 1)
        self.assertIn("Sample.swift:1", errors[0])
        self.assertIn("未本地化的中文字面量", errors[0])

    def test_swift_localized_call_needs_catalog_entry(self) -> None:
        source = 'let title = String(localized: "你好")\n'
        self.assertEqual(self.scan("ios", source, ios_keys={"你好"}), [])
        missing = self.scan("ios", source)
        self.assertEqual(len(missing), 1)
        self.assertIn("未收录到 Localizable.xcstrings", missing[0])

    def test_swift_interpolated_key_matches_format_entry(self) -> None:
        source = 'let title = String(localized: "共 \\(count) 项")\n'
        self.assertEqual(self.scan("ios", source, ios_keys={"共 %lld 项"}), [])

    def test_swift_format_arguments_are_not_exempt(self) -> None:
        source = 'let title = String(format: String(localized: "%@ 已完成"), "任务")\n'
        errors = self.scan("ios", source, ios_keys={"%@ 已完成"})
        self.assertEqual(len(errors), 1)
        self.assertIn("'任务'", errors[0])

    def test_swift_text_fallback_after_nil_coalescing_is_rejected(self) -> None:
        errors = self.scan("ios", 'Text(name ?? "未命名")\n', ios_keys={"未命名"})
        self.assertEqual(len(errors), 1)
        self.assertIn("未本地化的中文字面量", errors[0])

    def test_ignore_comment_needs_a_real_comment_on_the_same_line(self) -> None:
        self.assertEqual(self.scan("ios", 'let pattern = "第(\\\\d+)页" // i18n-ignore: 匹配旧数据\n'), [])
        inside_string = self.scan("ios", 'let text = "你好 // i18n-ignore: x"\n')
        self.assertEqual(len(inside_string), 1)
        next_line = self.scan("ios", '// i18n-ignore: 上一行\nlet text = "你好"\n')
        self.assertEqual(len(next_line), 1)

    def test_comments_are_not_scanned(self) -> None:
        self.assertEqual(self.scan("ios", '// 中文注释 "你好"\n/* 块注释 "再见" */\nlet a = 1\n'), [])

    def test_android_app_text_needs_catalog_entry(self) -> None:
        source = 'val title = appText("你好")\nval label = appTextKey("再见")\n'
        self.assertEqual(self.scan("android", source, android_keys={"你好", "再见"}), [])
        missing = self.scan("android", source, android_keys={"你好"})
        self.assertEqual(len(missing), 1)
        self.assertIn("未收录到 AndroidStrings", missing[0])

    def test_android_app_text_rejects_string_templates(self) -> None:
        errors = self.scan("android", 'val title = appText("你好 ${name}")\n')
        self.assertEqual(len(errors), 1)
        self.assertIn("%1$s", errors[0])

    def test_android_plain_compose_text_is_rejected(self) -> None:
        errors = self.scan("android", 'Text("你好")\n', android_keys={"你好"})
        self.assertEqual(len(errors), 1)
        self.assertIn("未本地化的中文字面量", errors[0])

    def test_shared_code_may_not_emit_chinese(self) -> None:
        errors = self.scan("shared", 'val message = UiMessage("请重试")\n')
        self.assertEqual(len(errors), 1)
        self.assertIn("未本地化的中文字面量", errors[0])


class InfoPlistTests(unittest.TestCase):
    """check_info_plist：系统直接展示的 Info.plist 中文必须收录到 InfoPlist.xcstrings。"""

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.saved = verify.IOS_RESOURCES
        verify.IOS_RESOURCES = Path(self.temp.name)
        with (verify.IOS_RESOURCES / "Info.plist").open("wb") as handle:
            plistlib.dump({"NSCameraUsageDescription": "扫描二维码", "CFBundleName": "Whaleness"}, handle)

    def tearDown(self) -> None:
        verify.IOS_RESOURCES = self.saved
        self.temp.cleanup()

    def test_chinese_usage_description_needs_catalog_entry(self) -> None:
        errors: list[str] = []
        verify.check_info_plist(set(), errors)
        self.assertEqual(errors, ["Info.plist: 'NSCameraUsageDescription' 未收录到 InfoPlist.xcstrings"])
        errors.clear()
        verify.check_info_plist({"NSCameraUsageDescription"}, errors)
        self.assertEqual(errors, [])


class TranslationScopeTests(unittest.TestCase):
    def test_catalog_checks_chinese_and_ids_but_not_numeric_formats(self) -> None:
        strings = {
            "+%@": {}, "−%@": {}, "+%@  −%@": {"localizations": {"zh-Hans": {"stringUnit": {"state": "new", "value": ""}}}},
            "1 / 3": {}, "100%": {}, "Hello": {},
            "%@ 个文件": {}, "voice.hint": {},
            "Count %@": {"localizations": {"zh-Hans": {"stringUnit": {"state": "translated", "value": "%@ 个文件"}}}},
            "第 %@ 行": {"localizations": {"en": {"stringUnit": {"state": "translated", "value": "Line %d"}}}},
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "Localizable.xcstrings").write_text(json.dumps({"strings": strings}))
            with patch.object(verify, "IOS_RESOURCES", root):
                errors: list[str] = []
                keys = verify.check_catalog("Localizable", errors)
        self.assertEqual(keys, set(strings))
        self.assertEqual(len(errors), 5)
        self.assertTrue(any("%@ 个文件" in e for e in errors))
        self.assertTrue(any("voice.hint" in e and "英文" in e for e in errors))
        self.assertTrue(any("voice.hint" in e and "中文" in e for e in errors))
        self.assertTrue(any("Count %@" in e for e in errors))
        self.assertTrue(any("格式参数不一致" in e for e in errors))

    def test_swift_calls_only_require_chinese_text_or_semantic_keys(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "Sample.swift").write_text('Text("+%@"); Text("Hello"); Text("%@ 个文件"); Text("voice.hint")')
            with patch.object(verify, "ROOT", root), patch.object(verify, "IOS_SOURCES", root):
                errors: list[str] = []
                verify.check_swift_calls(set(), errors)
        self.assertEqual(len(errors), 2)
        self.assertTrue(any("%@ 个文件" in e for e in errors))
        self.assertTrue(any("voice.hint" in e for e in errors))

    def test_android_numeric_resource_needs_no_english_but_chinese_does(self) -> None:
        with patch.object(verify, "android_strings", side_effect=[
            {"delta": "+%s", "files": "%s 个文件"}, {}
        ]):
            errors: list[str] = []
            verify.check_android(errors)
        self.assertEqual(errors, ["Android: 'files' 缺少中文或英文译文"])

    def test_android_calls_and_catalog_follow_same_scope(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            catalog = root / "com/clarklevis/dsh/android/localization/AndroidStrings.kt"
            catalog.parent.mkdir(parents=True)
            catalog.write_text('internal val chinese: Map = mapOf()\ninternal val english: Map = mapOf("+%s" to "")')
            (root / "Sample.kt").write_text('appText("+%d"); appText("Hello"); appText("模型"); appText("voice.hint")')
            with patch.object(verify, "ROOT", root), patch.object(verify, "ANDROID_SOURCES", root):
                errors: list[str] = []
                verify.check_android_ui(errors)
        self.assertEqual(len(errors), 2)
        self.assertTrue(any("模型" in e for e in errors))
        self.assertTrue(any("voice.hint" in e for e in errors))


if __name__ == "__main__":
    unittest.main()
