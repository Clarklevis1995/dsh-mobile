#!/usr/bin/env python3
"""让两端设备测试使用同一份 Bot 设计验收快照；不生成生产数据。"""
import argparse
import json
import base64
from pathlib import Path

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--check", action="store_true")
args = parser.parse_args()
swift = root / "DeepSeekHarnessMobile/Views/BotModeView.swift"
source = swift.read_text()
start = source.index('    private static let designFixtures: [String: String] = [')
end = source.index('\n    ]', start) + len('\n    ]')
lines = ['    private static let designFixtures: [String: String] = [']
android_files = []
for name, suffix in [("normal", ""), ("long", "-long"), ("empty", "-empty"), ("images", "-images"), ("markdown-images", "-markdown-images"), ("files", "-files"), ("bubble-widths", "-bubble-widths"), ("role-profile", "-role-profile"), ("page-parity", "-page-parity")]:
    fixture = (root / f"Design/bot-mode/preview-fixture{suffix}.json").read_text().strip()
    json.loads(fixture)
    encoded = base64.b64encode(fixture.encode()).decode()
    lines.append('        "' + name + '": String(data: Data(base64Encoded: "' + encoded + '")!, encoding: .utf8)!,')
    android_files.append((root / f"androidApp/src/androidTest/assets/bot-design-fixture{suffix}.json", fixture + "\n"))
lines.append('    ]')
expected_swift = source[:start] + '\n'.join(lines) + source[end:]
if args.check:
    if source != expected_swift or any(not p.exists() or p.read_text() != value for p, value in android_files):
        raise SystemExit("Bot 设计快照未同步，请运行 scripts/sync-bot-design-fixture.py")
    print("两端 Bot 设计快照已同步")
else:
    swift.write_text(expected_swift)
    for path, value in android_files:
        path.write_text(value)
