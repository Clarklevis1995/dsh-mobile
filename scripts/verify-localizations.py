#!/usr/bin/env python3
"""检查中英文目录、格式参数，以及 Swift / Android 的静态本地化调用。"""

from __future__ import annotations

import argparse
import json
import plistlib
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
IOS_RESOURCES = ROOT / "DeepSeekHarnessMobile" / "Resources"
IOS_SOURCES = ROOT / "DeepSeekHarnessMobile"
ANDROID_RESOURCES = ROOT / "androidApp" / "src" / "main" / "res"
ANDROID_SOURCES = ROOT / "androidApp" / "src" / "main" / "kotlin"
HAN = re.compile(r"[\u3400-\u9fff]")
IOS_ARGUMENT = re.compile(r"%(?:\d+\$)?(?:@|lld|ld|zd|d|lu|u|f|g|s|c|i)")
ANDROID_ARGUMENT = re.compile(r"%(?:\d+\$)?(?:s|d|f)")
# 本地化表：用户文案、KMP 诊断、Info.plist 权限说明各用一份，避免互相污染。
LOCALIZATION_TABLES = ("Localizable", "Diagnostics", "InfoPlist")
LOCALIZED_CALL = re.compile(
    r'String\s*\(\s*localized:\s*"((?:\\.|[^"\\])*)",?\s*(?:table:\s*"([^"]+)")?'
)
UI_CALL_NAMES = (
    "Text", "Button", "Label", "TextField", "SecureField", "Toggle", "Picker",
    "Section", "navigationTitle", "accessibilityLabel", "accessibilityValue",
    "confirmationDialog", "ContentUnavailableView", "ProgressView", "DatePicker",
    "alert", "Menu", "LabeledContent", "IntentDescription",
)
UI_CALL = re.compile(
    r"(?<![A-Za-z0-9_])(?:" + "|".join(UI_CALL_NAMES) + r')\s*\(\s*"((?:\\.|[^"\\])*)"'
)
KOTLIN_STRING = r'"((?:\\.|[^"\\])*)"'
KOTLIN_TRANSLATION = re.compile(KOTLIN_STRING + r"\s+to\s+" + KOTLIN_STRING)
KOTLIN_LOCALIZED_CALL = re.compile(r"\bappText\(\s*" + KOTLIN_STRING)
KOTLIN_UI_LITERAL = re.compile(
    r"(?:\b(?:Text|BasicText|DshMarkdownText)\(\s*|"
    r"\b(?:title|description|contentDescription|confirmLabel|dismissLabel)\s*=\s*)"
    + KOTLIN_STRING
)


def swift_unescape(raw: str) -> str:
    """解码 Swift 字符串字面量内容，覆盖界面文案会用到的转义。"""
    result: list[str] = []
    index = 0
    while index < len(raw):
        character = raw[index]
        if character != "\\":
            result.append(character)
            index += 1
            continue
        index += 1
        if index >= len(raw):
            raise ValueError("字符串以未完成的转义结尾")
        escape = raw[index]
        simple = {"n": "\n", "t": "\t", "r": "\r", "0": "\0", '"': '"', "'": "'", "\\": "\\"}
        if escape in simple:
            result.append(simple[escape])
            index += 1
        elif escape == "u" and raw[index + 1:index + 2] == "{":
            end = raw.find("}", index)
            if end < 0:
                raise ValueError("未闭合的 \\u{...} 转义")
            result.append(chr(int(raw[index + 2:end], 16)))
            index = end + 1
        else:
            raise ValueError(f"不支持的转义 \\{escape}")
    return "".join(result)


def arguments(value: str, pattern: re.Pattern[str]) -> list[str]:
    return sorted(re.sub(r"^%\d+\$", "%", match) for match in pattern.findall(value))


def value(entry: dict, language: str) -> str | None:
    return entry.get("localizations", {}).get(language, {}).get("stringUnit", {}).get("value")


def check_catalog(name: str, errors: list[str]) -> set[str]:
    data = json.loads((IOS_RESOURCES / f"{name}.xcstrings").read_text())
    strings = data["strings"]
    usable: set[str] = set()
    for key, entry in strings.items():
        if entry.get("extractionState") == "stale":
            errors.append(f"{name}: {key!r} 已过时，请删除或改为 manual")
            continue
        usable.add(key)
        english = value(entry, "en")
        chinese = value(entry, "zh-Hans")
        if not english or not english.strip():
            errors.append(f"{name}: {key!r} 缺少英文译文")
        if (name == "InfoPlist" or not HAN.search(key)) and (not chinese or not chinese.strip()):
            errors.append(f"{name}: {key!r} 缺少简体中文译文")
        if english and HAN.search(english):
            errors.append(f"{name}: {key!r} 的英文译文含中文")
        for language, localization in entry.get("localizations", {}).items():
            unit = localization.get("stringUnit", {})
            if not unit.get("value", "").strip() or unit.get("state") != "translated":
                errors.append(f"{name}: {key!r} 的 {language} 译文为空或未审定")
        if english:
            source = chinese if chinese is not None else key
            if arguments(source, IOS_ARGUMENT) != arguments(english, IOS_ARGUMENT):
                errors.append(f"{name}: {key!r} 的中英文格式参数不一致")
    return usable


def check_swift_calls(catalogs: dict[str, set[str]], errors: list[str]) -> None:
    paths = list(IOS_SOURCES.rglob("*.swift")) + list((ROOT / "AgentLiveActivityWidget").rglob("*.swift"))
    for path in paths:
        source = path.read_text()
        for pattern in (LOCALIZED_CALL, UI_CALL):
            for match in pattern.finditer(source):
                key = match.group(1)
                if "\\(" in key:
                    continue
                try:
                    key = swift_unescape(key)
                except ValueError as error:
                    errors.append(f"{path.relative_to(ROOT)}: {key!r} 无法解析：{error}")
                    continue
                table = (match.group(2) if pattern is LOCALIZED_CALL else None) or "Localizable"
                keys = catalogs.get(table)
                if keys is None:
                    errors.append(f"{path.relative_to(ROOT)}: 未知本地化表 {table!r}")
                elif key and key not in keys:
                    errors.append(f"{path.relative_to(ROOT)}: {key!r} 未收录到 {table}.xcstrings")


def check_swift_extractions(directory: Path, catalogs: dict[str, set[str]], errors: list[str]) -> int:
    """Check compiler-resolved keys, including interpolated integer formats and tables."""
    count = 0
    for path in directory.rglob("*.stringsdata"):
        data = json.loads(path.read_text())
        source = Path(data.get("source", ""))
        if not any(source.is_relative_to(root) for root in (IOS_SOURCES, ROOT / "AgentLiveActivityWidget")):
            continue
        count += 1
        for table, entries in data.get("tables", {}).items():
            keys = catalogs.get(table)
            if keys is None:
                errors.append(f"{source.relative_to(ROOT)}: 未知本地化表 {table!r}")
                continue
            for entry in entries:
                key = entry["key"]
                if key and key not in keys:
                    errors.append(f"{source.relative_to(ROOT)}: {key!r} 未收录到 {table}.xcstrings")
    if count == 0:
        errors.append(f"{directory}: 未找到应用的 Swift 编译器本地化输出")
    return count


def android_strings(language: str) -> dict[str, str]:
    suffix = "" if language == "zh-Hans" else "-en"
    path = ANDROID_RESOURCES / f"values{suffix}" / "strings.xml"
    return {node.attrib["name"]: "".join(node.itertext()) for node in ET.parse(path).getroot().findall("string")}


def check_android(errors: list[str]) -> int:
    chinese = android_strings("zh-Hans")
    english = android_strings("en")
    for key in sorted(chinese.keys() | english.keys()):
        zh, en = chinese.get(key), english.get(key)
        if not zh or not zh.strip() or not en or not en.strip():
            errors.append(f"Android: {key!r} 缺少中文或英文译文")
        elif arguments(zh, ANDROID_ARGUMENT) != arguments(en, ANDROID_ARGUMENT):
            errors.append(f"Android: {key!r} 的中英文格式参数不一致")
    return len(chinese.keys() | english.keys())


def kotlin_string(raw: str) -> str:
    return json.loads('"' + raw.replace(r"\$", "$") + '"')


def check_android_ui(errors: list[str]) -> int:
    path = ANDROID_SOURCES / "com/clarklevis/dsh/android/localization/AndroidStrings.kt"
    source = path.read_text().split("internal val english:", 1)[1]
    catalog: dict[str, str] = {}
    for match in KOTLIN_TRANSLATION.finditer(source):
        key, english = (kotlin_string(raw) for raw in match.groups())
        if key in catalog:
            errors.append(f"Android UI: {key!r} 重复收录")
        catalog[key] = english
        if not english.strip() or HAN.search(english):
            errors.append(f"Android UI: {key!r} 缺少完整英文译文")
        if arguments(key, ANDROID_ARGUMENT) != arguments(english, ANDROID_ARGUMENT):
            errors.append(f"Android UI: {key!r} 的中英文格式参数不一致")
    for path in ANDROID_SOURCES.rglob("*.kt"):
        if path.name == "AndroidStrings.kt":
            continue
        source = path.read_text()
        for match in KOTLIN_LOCALIZED_CALL.finditer(source):
            key = kotlin_string(match.group(1))
            if key not in catalog:
                errors.append(f"{path.relative_to(ROOT)}: {key!r} 未收录到 AndroidStrings")
        for match in KOTLIN_UI_LITERAL.finditer(source):
            if HAN.search(match.group(1)):
                line = source.count("\n", 0, match.start()) + 1
                errors.append(f"{path.relative_to(ROOT)}:{line} 界面文案仍为硬编码中文")
    return len(catalog)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-only", action="store_true", help="仅检查 Android 本地化")
    parser.add_argument("--swift-extractions", type=Path,
                        help="检查以 SWIFT_EMIT_LOC_STRINGS=YES 构建的 Xcode 中间产物目录")
    options = parser.parse_args()
    errors: list[str] = []
    catalogs: dict[str, set[str]] = {}
    if not options.android_only:
        for name in LOCALIZATION_TABLES:
            catalogs[name] = check_catalog(name, errors)
        with (IOS_RESOURCES / "Info.plist").open("rb") as plist:
            privacy_keys = {key for key in plistlib.load(plist) if key.endswith("UsageDescription")}
        for key in sorted(privacy_keys - catalogs["InfoPlist"]):
            errors.append(f"InfoPlist: {key!r} 缺少权限说明译文")
        check_swift_calls(catalogs, errors)
        if options.swift_extractions:
            check_swift_extractions(options.swift_extractions, catalogs, errors)
    android_count = check_android(errors)
    android_ui_count = check_android_ui(errors)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"本地化检查通过：{len(catalogs.get('Localizable', ()))} 条 iOS 文案，"
          f"{len(catalogs.get('Diagnostics', ()))} 条诊断文案，{android_count} 条 Android 系统文案，"
          f"{android_ui_count} 条 Android 界面文案。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
