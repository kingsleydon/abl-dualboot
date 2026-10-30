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


class DeviceTestCase(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="abl-dualboot-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.device = self.root / "devinfo"
        self.device.write_bytes(devinfo())
        for slot in ("a", "b"):
            (self.root / f"abl_{slot}").write_bytes((FIXTURES / "rocknix.bin").read_bytes())
        for name, value in (("DEV", str(self.device)), ("BYNAME", str(self.root)),
                            ("MISC", str(self.root / "misc"))):
            p = patch.object(dualboot, name, value)
            p.start()
            self.addCleanup(p.stop)
        self.real_catalog = dualboot.openabl_catalog
        image = (FIXTURES / "openabl-hash.bin").read_bytes()
        p = patch.object(dualboot, "openabl_catalog", return_value=[(len(image), hashlib.sha256(image).hexdigest())])
        p.start()
        self.addCleanup(p.stop)

    def assert_refused(self):
        for action in (dualboot.status, dualboot.targets, lambda: dualboot.switch("android"), lambda: dualboot.switch("linux", "usb")):
            with self.assertRaisesRegex(dualboot.SwitchError, "OpenABL is not supported yet"):
                action()
        self.assertEqual(devinfo(), self.device.read_bytes())


class DetectionTests(DeviceTestCase):
    def test_slot_gate_uses_hashes_before_markers(self):
        for name, expected in (("openabl-marker", True), ("rocknix", False), ("stock", False),
                               ("unknown", False), ("invalid-lzma", False)):
            with self.subTest(name=name):
                for slot in ("a", "b"):
                    (self.root / f"abl_{slot}").write_bytes((FIXTURES / f"{name}.bin").read_bytes())
                self.assertEqual(expected, dualboot.uses_openabl())

    def test_release_hash_ignores_partition_padding_but_not_image_changes(self):
        data = (FIXTURES / "openabl-hash.bin").read_bytes() + bytes(4096)
        for slot in ("a", "b"):
            path = self.root / f"abl_{slot}"
            path.write_bytes(data)
            self.assert_refused()
            path.write_bytes(bytes([0]) + data[1:])
        self.assertFalse(dualboot.uses_openabl())

    def test_missing_lzma_refuses_only_when_marker_inspection_is_needed(self):
        before = self.device.read_bytes()
        with patch.dict(sys.modules, {"lzma": None}):
            self.assertFalse(dualboot.uses_openabl())
            (self.root / "abl_a").write_bytes((FIXTURES / "openabl-hash.bin").read_bytes())
            self.assert_refused()
            (self.root / "abl_a").write_bytes((FIXTURES / "openabl-marker.bin").read_bytes())
            with self.assertRaisesRegex(dualboot.SwitchError, "cannot inspect bootloader: Python lacks lzma - nothing written"):
                dualboot.switch("linux")
        self.assertEqual(before, self.device.read_bytes())

    def test_marker_decompression_is_bounded(self):
        self.assertFalse(dualboot.openabl_marker((FIXTURES / "oversized-marker.bin").read_bytes()))
        self.assertFalse(dualboot.openabl_marker(bytes(1048576) + (FIXTURES / "openabl-marker.bin").read_bytes()))

    def test_release_catalog_includes_all_chips_and_historical_hash(self):
        rows = [line.split() for line in (ROOT / "shared/openabl-images.txt").read_text().splitlines()
                if line and not line.startswith("#")]
        # Read the actual catalog rather than the fixture override.
        with patch.object(dualboot, "openabl_catalog", self.real_catalog):
            self.assertEqual(28, len(dualboot.openabl_catalog()))
        for version in ("1.0", "1.1", "1.2"):
            self.assertEqual(9, len({chip for v, chip, _, _ in rows if v == version}))


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

    def test_cli_exits_without_rebooting(self):
        (self.root / "abl_b").write_bytes((FIXTURES / "openabl-marker.bin").read_bytes())
        with patch.object(sys, "argv", ["dualboot.py", "android"]), patch.object(subprocess, "run") as reboot:
            with self.assertRaisesRegex(SystemExit, "OpenABL is not supported yet"):
                dualboot.main()
            reboot.assert_not_called()

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
            for script, args in (("abl.sh", ("flash", "absent.elf", "bad-hash", str(self.root / "backup"), "rocknix", "rocknix")),
                                 ("dualboot.sh", ("status",)), ("dualboot.sh", ("targets",)),
                                 ("dualboot.sh", ("android", "", "rocknix", "rocknix")),
                                 ("dualboot.sh", ("linux", "usb", "rocknix", "rocknix"))):
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
        self.assertNotEqual(0, self.run_script("dualboot.sh", "linux", "usb", "rocknix", "stock").returncode)
        self.assertNotEqual(0, self.run_script("dualboot.sh", "linux", "usb", "rocknix", "rocknix").returncode)
        self.assertEqual(devinfo(), (self.root / "devinfo").read_bytes())
        (self.root / "abl_b").write_bytes((FIXTURES / "rocknix.bin").read_bytes())
        result = self.run_script("dualboot.sh", "linux", "usb", "rocknix", "rocknix")
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
        (self.root / "abl_a").write_bytes((FIXTURES / "openabl-hash.bin").read_bytes())
        for command in ("locations", "internal"):
            result = self.run_script("dualboot.sh", command)
            self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("usb Linux", self.run_script("dualboot.sh", "locations").stdout)
        self.assertNotEqual(0, self.run_script("dualboot.sh", "targets").returncode)
        (self.root / "abl.sh").unlink()
        for command in ("locations", "internal"):
            self.assertEqual(0, self.run_script("dualboot.sh", command).returncode)

    def test_mutations_refuse_missing_invalid_and_disallowed_kind_tokens(self):
        for kinds in ([], ["rocknix"], ["rocknix", "stock", "extra"], ["rocknix", "unknown"],
                      ["rocknix", "rocknix;touch injected"], ["rocknix", "rocknix\n"], ["", "rocknix"]):
            for script, args in (
                ("dualboot.sh", ["linux", "usb", *kinds]),
                ("abl.sh", ["flash", "absent.elf", "0" * 64, str(self.root / "backup"), *kinds]),
            ):
                with self.subTest(script=script, kinds=kinds):
                    result = self.run_script(script, *args)
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual(devinfo(), (self.root / "devinfo").read_bytes())
                    self.assertFalse((self.root / "backup").exists())

    def test_kotlin_openabl_and_unknown_classifications_block_both_mutation_paths(self):
        for name in ("openabl-marker", "unknown"):
            for slot in ("a", "b"):
                path = self.root / f"abl_{slot}"
                old = path.read_bytes()
                path.write_bytes((FIXTURES / f"{name}.bin").read_bytes())
                kinds = ["rocknix", "rocknix"]
                kinds[0 if slot == "a" else 1] = "openabl" if name == "openabl-marker" else "unknown"
                for script, args in (
                    ("dualboot.sh", ["linux", "usb", *kinds]),
                    ("abl.sh", ["flash", "absent.elf", "0" * 64, str(self.root / "backup"), *kinds]),
                ):
                    result = self.run_script(script, *args)
                    self.assertNotEqual(0, result.returncode)
                    if name == "openabl-marker":
                        self.assertIn("OpenABL is not supported yet", result.stderr)
                    self.assertEqual(devinfo(), (self.root / "devinfo").read_bytes())
                    self.assertFalse((self.root / "backup").exists())
                path.write_bytes(old)

    def test_flash_accepts_confirmed_rocknix_and_stock_and_preserves_stock_backup(self):
        # Simulate block-device predicates only; all I/O stays on regular fixture files.
        for slot, kind in (("a", "stock"), ("b", "rocknix")):
            image = bytearray((FIXTURES / f"{kind}.bin").read_bytes() + bytes(512))
            image[4] = 1
            (self.root / f"abl_{slot}").write_bytes(image)
        original_stock = (self.root / "abl_a").read_bytes()
        kinds = ["stock", "rocknix"]
        elf = FIXTURES / "rocknix.bin"
        digest = hashlib.sha256(elf.read_bytes()).hexdigest()
        (self.root / "blockdev").write_text('#!/bin/sh\n[ "$1" = --getsize64 ] || exit 1\nstat -c %s "$2"\n')
        (self.root / "sync").write_text('#!/bin/sh\nexit 0\n')
        for command in ("blockdev", "sync"):
            (self.root / command).chmod(0o755)
        wrapper = '''function [ {
  if command [ "$1" = -b ]; then command [ -f "$2" ]; else command [ "$@"; fi
}
. "$0"
'''
        result = subprocess.run(["bash", "-c", wrapper, str(self.root / "abl.sh"), "flash", str(elf), digest,
                                 str(self.root / "backup"), *kinds],
                                env=self.env, capture_output=True, text=True, check=False)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("abl_b flashed and verified", result.stdout)
        self.assertEqual(original_stock, (self.root / "backup/stock-abl_a.img").read_bytes())
        for slot in ("a", "b"):
            self.assertEqual(elf.read_bytes(), (self.root / f"abl_{slot}").read_bytes())


if __name__ == "__main__":
    unittest.main()
