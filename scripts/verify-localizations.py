#!/usr/bin/env python3
"""检查中英文目录、格式参数，以及 Swift / Android 的静态本地化调用。"""

from __future__ import annotations

import bisect
import json
import argparse
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
# 语义键：点分英文 ID（`voice.hint`、`wire.decodeFailed.missingFields`）；其余键是中文源文本。
SEMANTIC_KEY = re.compile(r"^[a-z][A-Za-z0-9]*(?:\.[A-Za-z0-9]+)+$")
LOCALIZED_CALL = re.compile(r'String\s*\(\s*localized:\s*"((?:\\.|[^"\\])*)"')
UI_CALL_NAMES = (
    "Text", "Button", "Label", "TextField", "SecureField", "Toggle", "Picker",
    "Section", "navigationTitle", "accessibilityLabel", "accessibilityValue",
    "confirmationDialog", "ContentUnavailableView", "ProgressView", "DatePicker",
    "alert", "Menu",
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


def arguments(value: str, pattern: re.Pattern[str]) -> list[str]:
    return sorted(re.sub(r"^%\d+\$", "%", match) for match in pattern.findall(value))


def value(entry: dict, language: str) -> str | None:
    return entry.get("localizations", {}).get(language, {}).get("stringUnit", {}).get("value")


def check_catalog(name: str, errors: list[str]) -> set[str]:
    data = json.loads((IOS_RESOURCES / f"{name}.xcstrings").read_text())
    strings = data["strings"]
    for key, entry in strings.items():
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
    return set(strings)


def check_swift_calls(keys: set[str], errors: list[str]) -> None:
    for path in IOS_SOURCES.rglob("*.swift"):
        source = path.read_text()
        for pattern in (LOCALIZED_CALL, UI_CALL):
            for match in pattern.finditer(source):
                key = match.group(1)
                if key and "\\(" not in key and key not in keys:
                    errors.append(f"{path.relative_to(ROOT)}: {key!r} 未收录到 Localizable.xcstrings")


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
    text = path.read_text()
    # `chinese` 只收语义键（如共享 UiMessage 的 `voice.hint`）的中文文案；中文源键在 zh 下直接显示键本身。
    chinese_source = text.split("internal val chinese:", 1)[1].split("internal val english:", 1)[0]
    source = text.split("internal val english:", 1)[1]
    chinese: dict[str, str] = {}
    for match in KOTLIN_TRANSLATION.finditer(chinese_source):
        key, value = (kotlin_string(raw) for raw in match.groups())
        if key in chinese:
            errors.append(f"Android UI: {key!r} 中文重复收录")
        if not SEMANTIC_KEY.match(key):
            errors.append(f"Android UI: {key!r} 不是语义键，不应写入 chinese 表")
        chinese[key] = value
    catalog: dict[str, str] = {}
    for match in KOTLIN_TRANSLATION.finditer(source):
        key, english = (kotlin_string(raw) for raw in match.groups())
        if key in catalog:
            errors.append(f"Android UI: {key!r} 重复收录")
        catalog[key] = english
        if not english.strip() or HAN.search(english):
            errors.append(f"Android UI: {key!r} 缺少完整英文译文")
        semantic = bool(SEMANTIC_KEY.match(key))
        zh = chinese.get(key) if semantic else key
        if semantic and (not zh or not zh.strip()):
            errors.append(f"Android UI: 语义键 {key!r} 缺少中文译文（chinese 表）")
        elif arguments(zh, ANDROID_ARGUMENT) != arguments(english, ANDROID_ARGUMENT):
            errors.append(f"Android UI: {key!r} 的中英文格式参数不一致")
    for key in sorted(chinese.keys() - catalog.keys()):
        errors.append(f"Android UI: 语义键 {key!r} 缺少英文译文（english 表）")
    for path in ANDROID_SOURCES.rglob("*.kt"):
        if path.name == "AndroidStrings.kt":
            continue
        source = path.read_text()
        for match in KOTLIN_LOCALIZED_CALL.finditer(source):
            key = kotlin_string(match.group(1))
            if key not in catalog:
                errors.append(f"{path.relative_to(ROOT)}: {key!r} 未收录到 AndroidStrings")
    return len(catalog), set(catalog)


# ---- 未本地化中文字面量扫描 ----
# 规则：任何包含中文的字符串字面量都必须经过本地化入口，或在同一行写明
# `// i18n-ignore: <原因>`（仅限正则、匹配旧数据、中文专属格式等非展示用途）。
SCAN_ROOTS = (
    (IOS_SOURCES, ".swift", "ios"),
    (ROOT / "AgentLiveActivityWidget", ".swift", "widget"),
    (ANDROID_SOURCES, ".kt", "android"),
    (ROOT / "shared" / "src" / "commonMain", ".kt", "shared"),
    (ROOT / "shared" / "src" / "androidMain", ".kt", "shared"),
    (ROOT / "shared" / "src" / "iosMain", ".kt", "shared"),
)
SWIFT_LOCALIZING_CALLS = ("String(localized:", "LocalizedStringKey(", "String.LocalizationValue(",
                          "LocalizedStringResource(")
SWIFT_UI_CALLS = tuple(f"{name}(" for name in UI_CALL_NAMES)
SWIFT_FORMAT = IOS_ARGUMENT.pattern
INTERPOLATION = re.compile(r"\\\((?:[^()]|\((?:[^()]|\([^()]*\))*\))*\)")


def string_literals(source: str, kotlin: bool,
                    line_comments: list[int] | None = None) -> list[tuple[int, int, str]]:
    """返回 (起始偏移, 结束偏移, 原始内容)，跳过注释与 Kotlin 字符字面量；
    若传入 line_comments，记录每个真正的 `//` 行注释的起始偏移。"""
    out: list[tuple[int, int, str]] = []
    i, n = 0, len(source)
    while i < n:
        if source.startswith("//", i):
            if line_comments is not None:
                line_comments.append(i)
            end = source.find("\n", i)
            i = n if end < 0 else end
        elif source.startswith("/*", i):
            end = source.find("*/", i + 2)
            i = n if end < 0 else end + 2
        elif kotlin and source[i] == "'":
            end = source.find("'", i + 2 if source[i + 1:i + 2] == "\\" else i + 1)
            i = n if end < 0 else end + 1
        elif source.startswith('"""', i) or source.startswith('#"""', i):
            opener = '#"""' if source[i] == "#" else '"""'
            closer = '"""#' if opener.startswith("#") else '"""'
            end = source.find(closer, i + len(opener))
            end = n if end < 0 else end
            out.append((i, end + len(closer), source[i + len(opener):end]))
            i = end + len(closer)
        elif source.startswith('#"', i):
            end = source.find('"#', i + 2)
            end = n if end < 0 else end
            out.append((i, end + 2, source[i + 2:end]))
            i = end + 2
        elif source[i] == '"':
            j = i + 1
            while j < n and source[j] not in '"\n':
                if source.startswith("\\(", j) or source.startswith("${", j):
                    opening, closing = ("(", ")") if source[j] == "\\" else ("{", "}")
                    depth, j = 1, j + 2
                    while j < n and depth:
                        if source[j] == '"':
                            # 插值内的嵌套字符串
                            k = j + 1
                            while k < n and source[k] not in '"\n':
                                k += 2 if source[k] == "\\" else 1
                            j = k + 1
                            continue
                        depth += (source[j] == opening) - (source[j] == closing)
                        j += 1
                elif source[j] == "\\":
                    j += 2
                else:
                    j += 1
            out.append((i, j + 1, source[i + 1:j]))
            i = j + 1
        else:
            i += 1
    return out


def string_mask(source: str, literals: list[tuple[int, int, str]]) -> bytearray:
    mask = bytearray(len(source))
    for start, end, _ in literals:
        mask[start:end] = b"\x01" * (end - start)
    return mask


def key_argument_spans(source: str, openers: tuple[str, ...], mask: bytearray) -> list[tuple[int, int]]:
    """返回 opener 之后第一个参数的区间（到同层逗号或配对右括号为止），即本地化键表达式。
    之后的格式化参数不会被查表，因此不享受豁免。"""
    spans = []
    for opener in openers:
        pattern = re.compile(r"(?<![A-Za-z0-9_])" + re.escape(opener))
        for match in pattern.finditer(source):
            if mask[match.start()]:
                continue
            depth, j = 1, match.end()
            while j < len(source):
                if not mask[j]:
                    char = source[j]
                    if char in "([{":
                        depth += 1
                    elif char in ")]}":
                        depth -= 1
                        if depth == 0:
                            break
                    elif char == "," and depth == 1:
                        break
                j += 1
            spans.append((match.end(), j))
    return spans


def inside(position: int, spans: list[tuple[int, int]]) -> bool:
    return any(start <= position < end for start, end in spans)


IGNORE_COMMENT = re.compile(r"i18n-ignore:\s*\S")


def has_ignore_comment(source: str, line_comments: list[int], line_start: int, line_end: int) -> bool:
    """同一行有真正的 `//` 行注释（非字符串、非块注释内），且写有 `i18n-ignore: <原因>`。"""
    index = bisect.bisect_left(line_comments, line_start)
    if index < len(line_comments) and line_comments[index] < line_end:
        return bool(IGNORE_COMMENT.search(source, line_comments[index], line_end))
    return False


def swift_key_known(raw: str, keys: set[str], key_patterns: list[str]) -> bool:
    text = raw.replace('\\"', '"').replace("\\n", "\n").replace("\\\\", "\\")
    if not INTERPOLATION.search(raw):
        return text in keys
    parts = INTERPOLATION.split(text)
    pattern = re.compile("^" + SWIFT_FORMAT.join(re.escape(part) for part in parts) + "$")
    return any(pattern.match(key) for key in key_patterns)


def check_untranslated_literals(ios_keys: set[str], android_keys: set[str], platforms: set[str],
                                errors: list[str]) -> int:
    key_patterns = [key for key in ios_keys if IOS_ARGUMENT.search(key)]
    scanned = 0
    for directory, suffix, platform in SCAN_ROOTS:
        if platform not in platforms or not directory.exists():
            continue
        for path in sorted(directory.rglob(f"*{suffix}")):
            if path.name == "AndroidStrings.kt":
                continue
            source = path.read_text()
            kotlin = suffix == ".kt"
            line_comments: list[int] = []
            literals = string_literals(source, kotlin, line_comments)
            if not any(HAN.search(raw) for _, _, raw in literals):
                continue
            scanned += 1
            mask = string_mask(source, literals)
            if platform == "android":
                localized = key_argument_spans(source, ("appText(", "appTextKey("), mask)
            elif platform == "ios":
                localized = key_argument_spans(source, SWIFT_LOCALIZING_CALLS, mask)
                ui = key_argument_spans(source, SWIFT_UI_CALLS, mask)
            else:
                localized = []
            for start, _, raw in literals:
                if not HAN.search(raw):
                    continue
                line_start = source.rfind("\n", 0, start) + 1
                line_end = source.find("\n", start)
                line_end = len(source) if line_end < 0 else line_end
                if has_ignore_comment(source, line_comments, line_start, line_end):
                    continue
                number = source.count("\n", 0, start) + 1
                location = f"{path.relative_to(ROOT)}:{number}"
                preceding = source[max(0, start - 200):start]
                if platform == "android" and inside(start, localized):
                    # 模板插值中的中文必须改用 %1$s 占位符，否则无法查表
                    if "${" in raw or "$" in raw.replace("\\$", ""):
                        errors.append(f"{location} {raw[:40]!r} appText 参数含字符串模板，请改用 %1$s 占位符")
                    elif kotlin_string(raw) not in android_keys:
                        errors.append(f"{location} {raw[:40]!r} 未收录到 AndroidStrings")
                    continue
                if platform == "ios":
                    if re.search(r'localized:\s*"[^"]*",\s*defaultValue:\s*$', preceding):
                        continue  # 语义键的默认值，由键本身校验
                    in_ui = inside(start, ui) and not re.search(r"\?\?\s*$", preceding)
                    if inside(start, localized) or in_ui:
                        if not swift_key_known(raw, ios_keys, key_patterns):
                            errors.append(f"{location} {raw[:40]!r} 未收录到 Localizable.xcstrings（或缺英文）")
                        continue
                errors.append(f"{location} {raw[:40]!r} 未本地化的中文字面量")
    return scanned
def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-only", action="store_true", help="仅检查 Android 本地化")
    parser.add_argument("--platform", action="append", choices=("ios", "widget", "android", "shared"),
                        help="仅扫描指定平台的中文字面量（可重复）；默认全部")
    options = parser.parse_args()
    errors: list[str] = []
    keys: set[str] = set()
    if not options.android_only:
        keys = check_catalog("Localizable", errors)
        check_catalog("InfoPlist", errors)
        check_swift_calls(keys, errors)
    android_count = check_android(errors)
    android_ui_count, android_keys = check_android_ui(errors)
    platforms = set(options.platform or (("android",) if options.android_only
                                         else ("ios", "widget", "android", "shared")))
    check_untranslated_literals(keys, android_keys, platforms, errors)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"本地化检查通过：{len(keys)} 条 iOS 文案，{android_count} 条 Android 系统文案，"
          f"{android_ui_count} 条 Android 界面文案。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
