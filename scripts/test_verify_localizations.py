"""verify-localizations.py 的行为测试：每个用例对应守卫必须拦截或必须放行的一类写法。"""

from __future__ import annotations

import importlib.util
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


if __name__ == "__main__":
    unittest.main()
