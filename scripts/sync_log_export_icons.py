#!/usr/bin/env python3
"""从 Figma 导出的同一组 SVG 生成双端图标资源；--check 仅校验，不写入。"""

import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "Design/log-export-icons"


def generated_files():
    files = {}
    for name in ("download", "share"):
        svg = (SOURCE / f"{name}.svg").read_text()
        root = ET.fromstring(svg)
        assert root.attrib["viewBox"] == "0 0 24 24"
        image_set = ROOT / f"DeepSeekHarnessMobile/Resources/Assets.xcassets/Log{name.title()}.imageset"
        files[image_set / "icon.svg"] = svg
        files[image_set / "Contents.json"] = json.dumps({
            "images": [{"filename": "icon.svg", "idiom": "universal"}],
            "info": {"author": "xcode", "version": 1},
            "properties": {"preserves-vector-representation": True, "template-rendering-intent": "template"},
        }, indent=2) + "\n"
        paths = []
        for path in root:
            assert path.tag.endswith("path")
            assert path.attrib["stroke-width"] == "2"
            paths.append(
                f'    <path android:pathData="{path.attrib["d"]}" '
                'android:fillColor="@android:color/transparent" android:strokeColor="#1E1E1E" '
                'android:strokeWidth="2" android:strokeLineCap="round" android:strokeLineJoin="round" />'
            )
        files[ROOT / f"androidApp/src/main/res/drawable/ic_log_{name}.xml"] = (
            '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" '
            'android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n'
            + "\n".join(paths) + "\n</vector>\n"
        )
    license_text = (SOURCE / "LICENSE").read_text()
    files[ROOT / "androidApp/src/main/assets/LogExportIcons.LICENSE.txt"] = license_text
    data_set = ROOT / "DeepSeekHarnessMobile/Resources/Assets.xcassets/LogExportIconsLicense.dataset"
    files[data_set / "LICENSE.txt"] = license_text
    files[data_set / "Contents.json"] = json.dumps({
        "data": [{"filename": "LICENSE.txt", "idiom": "universal"}],
        "info": {"author": "xcode", "version": 1},
    }, indent=2) + "\n"
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    mismatches = []
    for path, text in generated_files().items():
        if args.check:
            if not path.exists() or path.read_text() != text:
                mismatches.append(str(path.relative_to(ROOT)))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
    if mismatches:
        raise SystemExit("双端图标资源与源文件不一致：\n" + "\n".join(mismatches))
    print("双端日志图标资源一致" if args.check else "已同步双端日志图标资源")


if __name__ == "__main__":
    main()
