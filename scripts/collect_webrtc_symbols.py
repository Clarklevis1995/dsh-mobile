#!/usr/bin/env python3
"""下载并收集与 SwiftPM WebRTC 二进制匹配的官方符号，仅用于 iOS 归档。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile


VERSION = "154.0.0"
SYMBOLS_SHA256 = "e7a2322bf03020da703336b597aaa172cd8029acaa5588e8f6916d4e22a3afdb"
SYMBOLS_URL = (
    "https://api.github.com/repos/stasel/WebRTC/releases/assets/584352432"
)
ROOT = Path(__file__).resolve().parent.parent


def uuids(binary):
    output = subprocess.check_output(
        ["/usr/bin/xcrun", "dwarfdump", "--uuid", str(binary)], text=True
    )
    result = set(re.findall(r"UUID: ([0-9A-Fa-f-]+) \(([^)]+)\)", output))
    if not result:
        raise RuntimeError("无法读取 Mach-O UUID: " + str(binary))
    return result


def verify_download(archive):
    digest = hashlib.sha256()
    with archive.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    if digest.hexdigest() != SYMBOLS_SHA256:
        raise RuntimeError("WebRTC 官方符号压缩包 SHA-256 不匹配: " + str(archive))


def prepare_symbols(local_zip=None):
    # 工程本身锁定 exactVersion；不依赖 Xcode 可能正在重建的 Package.resolved。
    project = json.loads(subprocess.check_output(
        ["/usr/bin/plutil", "-convert", "json", "-o", "-",
         str(ROOT / "DeepSeekHarnessMobile.xcodeproj/project.pbxproj")], text=True
    ))
    packages = [item for item in project["objects"].values()
                if item.get("repositoryURL") == "https://github.com/stasel/WebRTC.git"]
    requirement = {"kind": "exactVersion", "version": VERSION}
    if len(packages) != 1 or packages[0].get("requirement") != requirement:
        raise RuntimeError("WebRTC 版本已变化，请同步更新官方 dSYM 下载地址和校验值")
    cache = ROOT / "build/ios-symbols" / ("webrtc-" + VERSION)
    cache.mkdir(parents=True, exist_ok=True)
    archive = Path(local_zip) if local_zip else cache / "WebRTC-dSYM.zip"
    if local_zip and not archive.is_file():
        raise RuntimeError("指定的官方符号压缩包不存在: " + str(archive))
    if not archive.exists():
        print("下载 WebRTC 官方符号（约 397 MiB，后续归档复用缓存）", flush=True)
        with tempfile.TemporaryDirectory(dir=cache) as temp:
            download = Path(temp) / "symbols.zip"
            subprocess.run(
                ["/usr/bin/curl", "--fail", "--location", "--retry", "3",
                 "--header", "Accept: application/octet-stream",
                 "--connect-timeout", "20", "--max-time", "600", "--output",
                 str(download), SYMBOLS_URL], check=True
            )
            verify_download(download)
            download.replace(archive)
    verify_download(archive)
    return archive


def collect_symbols(archive, framework, destination):
    expected = uuids(framework)
    # 从多个平台的符号中按 UUID 选择，避免把模拟器或 Catalyst 的符号放进真机归档。
    with tempfile.TemporaryDirectory() as temp:
        with zipfile.ZipFile(archive) as source:
            members = source.infolist()
            for member in members:
                path = Path(member.filename)
                if path.is_absolute() or ".." in path.parts:
                    raise RuntimeError("符号压缩包中包含非法路径")
            for member in members:
                if not member.filename.endswith(".dSYM/Contents/Resources/DWARF/WebRTC"):
                    continue
                binary = Path(source.extract(member, temp))
                if uuids(binary) != expected:
                    continue
                prefix = member.filename.split(".dSYM/")[0] + ".dSYM/"
                for entry in members:
                    if entry.filename.startswith(prefix):
                        source.extract(entry, temp)
                bundle = Path(temp) / prefix
                destination.mkdir(parents=True, exist_ok=True)
                target = destination / "WebRTC.framework.dSYM"
                if target.exists():
                    if uuids(target / "Contents/Resources/DWARF/WebRTC") != expected:
                        raise RuntimeError("目标目录已有 UUID 不匹配的 WebRTC dSYM")
                    return
                shutil.copytree(bundle, target)
                print("已收集 WebRTC dSYM: " + str(sorted(expected)), flush=True)
                return
    raise RuntimeError("官方符号包中没有与 WebRTC.framework UUID 完全匹配的 dSYM")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--symbols-zip", type=Path)
    parser.add_argument("--framework", type=Path)
    parser.add_argument("--destination", type=Path)
    args = parser.parse_args()
    explicit = args.framework is not None or args.destination is not None
    if explicit and (args.framework is None or args.destination is None):
        parser.error("--framework 和 --destination 必须一起提供")
    if not explicit and (
        os.environ.get("ACTION") != "install"
        or os.environ.get("PLATFORM_NAME") != "iphoneos"
    ):
        print("跳过 WebRTC 符号收集：仅 iOS 真机 Archive 需要")
        return
    framework = args.framework or (
        Path(os.environ["BUILT_PRODUCTS_DIR"]) / "WebRTC.framework/WebRTC"
    )
    destination = args.destination or Path(os.environ["DWARF_DSYM_FOLDER_PATH"])
    collect_symbols(prepare_symbols(args.symbols_zip), framework, destination)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        raise SystemExit("error: WebRTC 符号收集失败：" + str(error)) from error
