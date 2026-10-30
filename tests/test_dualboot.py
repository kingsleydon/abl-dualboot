import asyncio
import hashlib
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "tests/fixtures"
spec = importlib.util.spec_from_file_location("dualboot", ROOT / "linux/dualboot.py")
dualboot = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dualboot)


def devinfo():
    data = bytearray(4096)
    data[:13] = b"ANDROID-BOOT!"
    data[0xA54:0xA62] = b"\x01\x00\x08\x00\x01\x00BootMode"
    data[0xAB2:0xAC6] = b"\x01\x00\x0e\x00\x01\x00BootSourceMode"
    data[dualboot.BOOT_MODE] = 1
    return bytes(data)


class DetectionTests(unittest.TestCase):
    def setUp(self):
        data = (FIXTURES / "openabl-hash.bin").read_bytes()
        self.catalog = [(len(data), hashlib.sha256(data).hexdigest())]

    def test_classifies_all_kinds_before_signing_certificates(self):
        for name, kind in (("openabl-hash", "openabl"), ("openabl-marker", "openabl"),
                           ("rocknix", "rocknix"), ("stock", "stock"),
                           ("unknown", "unknown"), ("invalid-lzma", "rocknix")):
            with self.subTest(name=name):
                self.assertEqual(kind, dualboot.abl_kind((FIXTURES / f"{name}.bin").read_bytes(), self.catalog))

    def test_prefix_hash_ignores_partition_padding_but_not_image_changes(self):
        data = (FIXTURES / "openabl-hash.bin").read_bytes() + bytes(4096)
        self.assertEqual("openabl", dualboot.abl_kind(data, self.catalog))
        self.assertNotEqual("openabl", dualboot.abl_kind(bytes([0]) + data[1:], self.catalog))

    def test_marker_decompression_is_bounded(self):
        self.assertFalse(dualboot.openabl_marker((FIXTURES / "oversized-marker.bin").read_bytes()))
        self.assertFalse(dualboot.openabl_marker(bytes(1048576) + (FIXTURES / "openabl-marker.bin").read_bytes()))

    def test_release_catalog_includes_all_chips_and_historical_hash(self):
        rows = [line.split() for line in (ROOT / "shared/openabl-images.txt").read_text().splitlines()
                if line and not line.startswith("#")]
        self.assertEqual(28, len(dualboot.openabl_catalog()))
        for version in ("1.0", "1.1", "1.2"):
            self.assertEqual(9, len({chip for v, chip, _, _ in rows if v == version}))


class DeviceTestCase(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="abl-dualboot-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.device = self.root / "devinfo"
        self.device.write_bytes(devinfo())
        for slot in ("a", "b"):
            (self.root / f"abl_{slot}").write_bytes((FIXTURES / "rocknix.bin").read_bytes())
        self.efivars = self.root / "efivars"
        self.efivars.mkdir()
        for name, value in (("DEV", str(self.device)), ("BYNAME", str(self.root)), ("EFIVARS", str(self.efivars)),
                            ("MISC", str(self.root / "misc"))):
            p = patch.object(dualboot, name, value)
            p.start()
            self.addCleanup(p.stop)
        image = (FIXTURES / "openabl-hash.bin").read_bytes()
        p = patch.object(dualboot, "openabl_catalog", return_value=[(len(image), hashlib.sha256(image).hexdigest())])
        p.start()
        self.addCleanup(p.stop)

    def assert_refused(self):
        for action in (dualboot.status, dualboot.targets, lambda: dualboot.switch("android"), lambda: dualboot.switch("linux", "usb")):
            with self.assertRaisesRegex(dualboot.SwitchError, "OpenABL is not supported yet"):
                action()
        self.assertEqual(devinfo(), self.device.read_bytes())


class RefusalTests(DeviceTestCase):
    def test_refuses_hash_or_marker_in_either_slot_before_touching_devinfo(self):
        for name in ("openabl-hash", "openabl-marker"):
            for slot in ("a", "b"):
                with self.subTest(name=name, slot=slot):
                    path = self.root / f"abl_{slot}"
                    before = path.read_bytes()
                    path.write_bytes((FIXTURES / f"{name}.bin").read_bytes() + bytes(4096))
                    self.assert_refused()
                    path.write_bytes(before)

    def test_refuses_running_openabl_from_either_efivar_signal(self):
        for name, value in (
            ("SettingsSchema-8c5f2b7e-3a41-4d6c-9e2f-5b7a1c0d4e93", b"\x01"),
            ("LoaderInfo-4a67b082-0a4c-41cf-b6c7-440b29bb8c4f", "OpenABL 1.2\0".encode("utf-16-le")),
        ):
            with self.subTest(name=name):
                path = self.efivars / name
                path.write_bytes(bytes(4) + value)
                self.assert_refused()
                path.unlink()

    def test_cli_exits_without_rebooting(self):
        (self.root / "abl_b").write_bytes((FIXTURES / "openabl-marker.bin").read_bytes())
        with patch.object(sys, "argv", ["dualboot.py", "android"]), patch.object(subprocess, "run") as reboot:
            with self.assertRaisesRegex(SystemExit, "OpenABL is not supported yet"):
                dualboot.main()
            reboot.assert_not_called()

    def test_malformed_loader_info_does_not_block_rocknix(self):
        loader = self.efivars / "LoaderInfo-4a67b082-0a4c-41cf-b6c7-440b29bb8c4f"
        with patch.object(dualboot, "internal_linux", return_value=False):
            for value in (b"\xff", b"\x00\xd8", "OpenABL 1.2".encode("utf-16-le") + b"\xff"):
                with self.subTest(value=value):
                    loader.write_bytes(bytes(4) + value)
                    self.device.write_bytes(devinfo())
                    self.assertEqual("android sd", dualboot.status())
                    self.assertEqual("linux sd", dualboot.switch("linux"))
                    expected = bytearray(devinfo())
                    expected[dualboot.BOOT_MODE] = 0
                    expected[dualboot.BOOT_SOURCE] = 3
                    self.assertEqual(bytes(expected), self.device.read_bytes())
                    self.device.write_bytes(devinfo())
                    (self.root / "abl_b").write_bytes((FIXTURES / "openabl-marker.bin").read_bytes())
                    self.assert_refused()
                    (self.root / "abl_b").write_bytes((FIXTURES / "rocknix.bin").read_bytes())

    def test_decky_reports_error_without_rebooting(self):
        (self.root / "abl_a").write_bytes((FIXTURES / "openabl-marker.bin").read_bytes())
        with patch.dict(sys.modules, {"decky": Mock(DECKY_PLUGIN_DIR=str(ROOT / "linux"))}):
            plugin_spec = importlib.util.spec_from_file_location("plugin", ROOT / "decky/main.py")
            plugin = importlib.util.module_from_spec(plugin_spec)
            plugin_spec.loader.exec_module(plugin)
        plugin.dualboot = dualboot
        with patch.object(subprocess, "Popen") as reboot:
            self.assertIn("OpenABL is not supported yet", asyncio.run(plugin.Plugin().get_info())["error"])
            result = asyncio.run(plugin.Plugin().restart_into("android"))
            self.assertFalse(result["ok"])
            self.assertIn("OpenABL is not supported yet", result["error"])
            reboot.assert_not_called()

    def test_rocknix_and_stock_keep_existing_devinfo_behavior(self):
        with patch.object(dualboot, "internal_linux", return_value=False):
            for kind in ("rocknix", "stock"):
                for slot in ("a", "b"):
                    (self.root / f"abl_{slot}").write_bytes((FIXTURES / f"{kind}.bin").read_bytes())
                self.device.write_bytes(devinfo())
                self.assertEqual("android sd", dualboot.status())
                self.assertEqual("linux sd", dualboot.switch("linux"))
                expected = bytearray(devinfo())
                expected[dualboot.BOOT_MODE] = 0
                expected[dualboot.BOOT_SOURCE] = 3
                self.assertEqual(bytes(expected), self.device.read_bytes())
                self.assertEqual("android internal", dualboot.switch("android"))
                self.assertEqual(devinfo(), self.device.read_bytes())


class ShellTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="abl-dualboot-shell-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in ("abl.sh", "dualboot.sh"):
            shutil.copyfile(ROOT / "shared" / name, self.root / name)
        shutil.copyfile(FIXTURES / "fixture-images.txt", self.root / "openabl-images.txt")
        for slot in ("a", "b"):
            (self.root / f"abl_{slot}").write_bytes((FIXTURES / "rocknix.bin").read_bytes())
        (self.root / "devinfo").write_bytes(devinfo())
        (self.root / "getprop").write_text("#!/bin/sh\necho kalama\n")
        (self.root / "getprop").chmod(0o755)
        self.env = dict(os.environ, ABL_BY_NAME=str(self.root), DEVINFO=str(self.root / "devinfo"),
                        DUALBOOT_TMP=str(self.root), PATH=str(self.root) + os.pathsep + os.environ["PATH"])

    def run_script(self, name, *args):
        return subprocess.run(["sh", str(self.root / name), *args], env=self.env, capture_output=True, text=True, check=False)

    def test_hash_precedes_signing_certificate_and_refuses_both_write_paths(self):
        for slot in ("a", "b"):
            path = self.root / f"abl_{slot}"
            original = path.read_bytes()
            image = (FIXTURES / "openabl-hash.bin").read_bytes() + bytes(4096)
            path.write_bytes(image)
            status = self.run_script("abl.sh", "status")
            self.assertEqual(0, status.returncode, status.stderr)
            self.assertIn(f"{slot}=openabl", status.stdout)
            for script, args in (("abl.sh", ("flash", "absent.elf", "bad-hash", str(self.root / "backup"))),
                                 ("dualboot.sh", ("status",)), ("dualboot.sh", ("targets",)),
                                 ("dualboot.sh", ("android",)), ("dualboot.sh", ("linux", "usb"))):
                result = self.run_script(script, *args)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("OpenABL is not supported yet", result.stderr)
                self.assertEqual(image, path.read_bytes())
                self.assertEqual(devinfo(), (self.root / "devinfo").read_bytes())
                self.assertFalse((self.root / "backup").exists())
            path.write_bytes(original)

    def test_legacy_classification_and_two_byte_switch_are_unchanged(self):
        (self.root / "abl_b").write_bytes((FIXTURES / "stock.bin").read_bytes())
        self.assertEqual("soc=SM8550 a=rocknix b=stock\n", self.run_script("abl.sh", "status").stdout)
        result = self.run_script("dualboot.sh", "linux", "usb")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("linux usb ok\n", result.stdout)
        expected = bytearray(devinfo())
        expected[dualboot.BOOT_MODE] = 0
        expected[dualboot.BOOT_SOURCE] = 2
        self.assertEqual(bytes(expected), (self.root / "devinfo").read_bytes())
        (self.root / "abl_b").write_bytes((FIXTURES / "unknown.bin").read_bytes())
        self.assertIn("b=unknown", self.run_script("abl.sh", "status").stdout)

    def test_app_location_helpers_work_without_devinfo_but_legacy_targets_still_validate_it(self):
        (self.root / "devinfo").unlink()
        for command in ("locations", "internal"):
            result = self.run_script("dualboot.sh", command)
            self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("usb Linux", self.run_script("dualboot.sh", "locations").stdout)
        self.assertNotEqual(0, self.run_script("dualboot.sh", "targets").returncode)


if __name__ == "__main__":
    unittest.main()
