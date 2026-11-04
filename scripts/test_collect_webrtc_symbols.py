import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import collect_webrtc_symbols as symbols


class CollectWebRtcSymbolsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.archive = self.root / "symbols.zip"
        self.destination = self.root / "dSYMs"
        self.framework = self.root / "WebRTC.framework/WebRTC"

    def create_zip(self, devices):
        with zipfile.ZipFile(self.archive, "w") as archive:
            for platform, uuid in devices:
                prefix = platform + "/WebRTC.framework.dSYM/Contents/"
                archive.writestr(prefix + "Resources/DWARF/WebRTC", uuid)
                archive.writestr(prefix + "Info.plist", "metadata")

    def fake_uuids(self, binary):
        if binary == self.framework:
            return {("device-uuid", "arm64")}
        return {(binary.read_text(), "arm64")}

    def test_selects_device_uuid_instead_of_simulator(self):
        self.create_zip([("simulator", "simulator-uuid"), ("iphoneos", "device-uuid")])
        with patch.object(symbols, "uuids", side_effect=self.fake_uuids):
            symbols.collect_symbols(self.archive, self.framework, self.destination)
            # 重复收集保持已有匹配符号可用。
            symbols.collect_symbols(self.archive, self.framework, self.destination)
        bundle = self.destination / "WebRTC.framework.dSYM/Contents"
        self.assertEqual((bundle / "Resources/DWARF/WebRTC").read_text(), "device-uuid")
        self.assertTrue((bundle / "Info.plist").exists())

    def test_mismatched_symbols_do_not_enter_archive(self):
        self.create_zip([("iphoneos", "wrong-uuid")])
        with patch.object(symbols, "uuids", side_effect=self.fake_uuids):
            with self.assertRaisesRegex(RuntimeError, "完全匹配"):
                symbols.collect_symbols(self.archive, self.framework, self.destination)
        self.assertFalse(self.destination.exists())

    def test_rejects_unsafe_zip_path(self):
        with zipfile.ZipFile(self.archive, "w") as archive:
            archive.writestr("../outside", "unsafe")
        with patch.object(symbols, "uuids", side_effect=self.fake_uuids):
            with self.assertRaisesRegex(RuntimeError, "非法路径"):
                symbols.collect_symbols(self.archive, self.framework, self.destination)
        self.assertFalse((self.root / "outside").exists())

    def test_rejects_corrupted_download(self):
        self.archive.write_bytes(b"original")
        digest = hashlib.sha256(b"original").hexdigest()
        with patch.object(symbols, "SYMBOLS_SHA256", digest):
            symbols.verify_download(self.archive)
            self.archive.write_bytes(b"corrupted")
            with self.assertRaisesRegex(RuntimeError, "SHA-256"):
                symbols.verify_download(self.archive)


if __name__ == "__main__":
    unittest.main()
