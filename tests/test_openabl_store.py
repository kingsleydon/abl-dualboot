import asyncio
import hashlib
import importlib.util
import os
import stat
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

from test_dualboot import FIXTURES, ROOT, DeviceTestCase, dualboot


ROWS = [line.split() for line in (FIXTURES / "openabl-stores.txt").read_text().splitlines()
        if line and not line.startswith("#")]
BLOB = (FIXTURES / "openabl-stores.bin").read_bytes()
STORES = {name: BLOB[int(offset):int(offset) + int(length)]
          for kind, name, *fields in ROWS if kind == "frame" for offset, length in [fields]}


class StoreTests(unittest.TestCase):
    def test_shared_golden_edits_and_status(self):
        for kind, *fields in ROWS:
            with self.subTest(fields=fields):
                if kind == "edit":
                    before, target, location, internal, after, mode, source = fields
                    actual, _ = dualboot.edit_store(STORES[before], target, "" if location == "-" else location, internal == "true")
                    self.assertEqual(STORES[after], actual)
                    self.assertEqual((int(mode == "android"), {"auto": 0, "internal": 1, "sd": 2, "usb": 3}[source]),
                                     dualboot.store_values(actual))
                elif kind == "status":
                    name, mode, source = fields
                    m, s = dualboot.store_values(STORES[name])
                    self.assertEqual(f"{mode} {source}", f"{'android' if m else 'linux'} {dualboot.OPENABL_SOURCES[s]}")
                elif kind == "invalid":
                    with self.assertRaisesRegex(dualboot.SwitchError, "OpenABL is not supported yet"):
                        dualboot.store_records(STORES[fields[0]])

    def test_full_store_fails_without_mutating_input(self):
        before = bytearray(STORES["full"])
        with self.assertRaisesRegex(dualboot.SwitchError, "full"):
            dualboot.edit_store(before, "android")
        self.assertEqual(STORES["full"], before)


class MiscIntegrationTests(DeviceTestCase):
    def setUp(self):
        super().setUp()
        self.misc = self.root / "misc"
        self.prefix = bytes(range(256)) * 17
        self.misc.write_bytes(self.prefix + STORES["android-internal"])
        p = patch.object(dualboot, "MISC", str(self.misc))
        p.start()
        self.addCleanup(p.stop)
        for slot in ("a", "b"):
            (self.root / f"abl_{slot}").write_bytes((FIXTURES / "openabl-marker.bin").read_bytes())

    def test_supported_status_switch_and_targets_leave_devinfo_and_prefix_untouched(self):
        original = self.device.read_bytes()
        self.device.unlink()  # A supported OpenABL path must not depend on devinfo.
        with patch.object(dualboot, "internal_part", return_value=None), patch.object(dualboot, "sd_card", return_value=None):
            self.assertEqual("android internal", dualboot.status())
            self.assertEqual([("usb", "Linux")], dualboot.targets())
            self.assertEqual("linux sd", dualboot.switch("linux"))
            self.assertEqual(self.prefix + STORES["linux-sd"], self.misc.read_bytes())
            self.assertEqual("linux usb", dualboot.switch("linux", "usb"))
            self.assertEqual(self.prefix + STORES["linux-usb"], self.misc.read_bytes())
        self.device.write_bytes(original)

    def test_refuses_released_hash_in_either_slot_even_with_a_valid_store(self):
        for slot in ("a", "b"):
            path = self.root / f"abl_{slot}"
            old = path.read_bytes()
            path.write_bytes((FIXTURES / "openabl-hash.bin").read_bytes())
            before = self.misc.read_bytes()
            self.assert_refused()
            self.assertEqual(before, self.misc.read_bytes())
            path.write_bytes(old)

    def test_rejects_all_invalid_stores_and_missing_misc_without_writing(self):
        for kind, name, *unused in ROWS:
            if kind != "invalid":
                continue
            with self.subTest(name=name):
                self.misc.write_bytes(self.prefix + STORES[name])
                before = self.misc.read_bytes()
                self.assert_refused()
                self.assertEqual(before, self.misc.read_bytes())
        self.misc.unlink()
        self.assert_refused()
        self.assertFalse(self.misc.exists())

    def test_mixed_or_missing_slots_refuse_even_with_a_valid_store(self):
        before = self.misc.read_bytes()
        for slot in ("a", "b"):
            path = self.root / f"abl_{slot}"
            for kind in ("rocknix", "stock", "unknown", None):
                with self.subTest(slot=slot, kind=kind):
                    if kind is None:
                        path.unlink()
                    else:
                        path.write_bytes((FIXTURES / f"{kind}.bin").read_bytes())
                    self.assert_refused()
                    self.assertEqual(before, self.misc.read_bytes())
                    path.write_bytes((FIXTURES / "openabl-marker.bin").read_bytes())

    def test_leftover_store_alone_does_not_select_misc_for_rocknix(self):
        for slot in ("a", "b"):
            (self.root / f"abl_{slot}").write_bytes((FIXTURES / "rocknix.bin").read_bytes())
        before = self.misc.read_bytes()
        with patch.object(dualboot, "internal_linux", return_value=False):
            self.assertEqual("linux sd", dualboot.switch("linux"))
        self.assertEqual(before, self.misc.read_bytes())

    def test_fsync_failure_restores_original_store(self):
        before = self.misc.read_bytes()
        with patch.object(dualboot.os, "fsync", side_effect=[OSError("injected fsync failure"), None]):
            with self.assertRaisesRegex(dualboot.SwitchError, "original misc store restored"):
                dualboot.switch("linux", "usb")
        self.assertEqual(before, self.misc.read_bytes())

    def test_regular_files_do_not_attempt_block_cache_invalidation(self):
        with patch.object(dualboot.fcntl, "ioctl") as ioctl:
            dualboot.switch("linux", "sd")
        ioctl.assert_not_called()

    def test_block_cache_preflight_failure_never_mutates(self):
        before = self.misc.read_bytes()
        with patch.object(dualboot.os, "fstat", return_value=Mock(st_mode=stat.S_IFBLK)), \
                patch.object(dualboot.fcntl, "ioctl", side_effect=PermissionError("denied")) as ioctl:
            with self.assertRaisesRegex(dualboot.SwitchError, "nothing written"):
                dualboot.switch("linux", "sd")
        self.assertEqual(before, self.misc.read_bytes())
        self.assertEqual(1, ioctl.call_count)

    def test_block_cache_is_invalidated_after_write_and_before_rollback_readback(self):
        before = self.misc.read_bytes()
        snapshots = []
        def flush(fd, request):
            self.assertEqual(0x1261, request)
            snapshots.append(self.misc.read_bytes())
            if len(snapshots) == 2:
                raise OSError("injected post-write flush failure")
        with patch.object(dualboot.os, "fstat", return_value=Mock(st_mode=stat.S_IFBLK)), \
                patch.object(dualboot.fcntl, "ioctl", side_effect=flush):
            with self.assertRaisesRegex(dualboot.SwitchError, "original misc store restored"):
                dualboot.switch("linux", "sd")
        self.assertEqual([before, self.prefix + STORES["linux-sd"], before], snapshots)
        self.assertEqual(before, self.misc.read_bytes())

    def test_full_store_never_writes(self):
        before = self.prefix + STORES["full"]
        self.misc.write_bytes(before)
        with self.assertRaisesRegex(dualboot.SwitchError, "full"):
            dualboot.switch("android")
        self.assertEqual(before, self.misc.read_bytes())

    def test_cli_and_decky_supported_paths_use_misc(self):
        with patch.object(dualboot, "internal_linux", return_value=False):
            with patch.object(dualboot.sys, "argv", ["dualboot.py", "linux", "sd", "--no-reboot"]), patch.object(subprocess, "run") as reboot:
                dualboot.main()
                reboot.assert_not_called()
            self.assertEqual("linux sd", dualboot.status())
            with patch.dict(sys.modules, {"decky": Mock(DECKY_PLUGIN_DIR=str(ROOT / "linux"))}):
                spec = importlib.util.spec_from_file_location("plugin", ROOT / "decky/main.py")
                plugin = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(plugin)
            plugin.dualboot = dualboot
            with patch.object(dualboot, "internal_part", return_value=None), patch.object(dualboot, "sd_card", return_value=None):
                self.assertEqual("linux", asyncio.run(plugin.Plugin().get_info())["mode"])
            with patch.object(subprocess, "Popen") as reboot:
                self.assertEqual({"ok": True}, asyncio.run(plugin.Plugin().restart_into("android")))
                reboot.assert_called_once_with(["systemctl", "reboot"])
            self.assertEqual("android internal", dualboot.status())


class MiscShellTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="abl-dualboot-misc-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.partition = self.root / "misc"
        self.prefix = b"untouched Android misc data" * 197
        self.partition.write_bytes(self.prefix + STORES["android-internal"])
        self.env = dict(os.environ, MISC=str(self.partition))
        self.fake_block = False
        self.snapshot = self.run_script("read")
        self.assertEqual(0, self.snapshot.returncode, self.snapshot.stderr)
        self.next = STORES["linux-sd"]
        (self.root / "next.bin").write_bytes(self.next)

    def run_script(self, command, *args):
        invocation = ["sh", str(ROOT / "shared/misc.sh"), command, str(self.root), *args]
        if self.fake_block:
            # Only simulate the block predicate. The root script still reads/writes this fixture file.
            wrapper = '''function [ {
  if command [ "$1" = -b ] && command [ "$2" = "$MISC" ]; then return 0; fi
  command [ "$@"
}
. "$0"
'''
            invocation = ["bash", "-c", wrapper, *invocation[1:]]
        return subprocess.run(invocation,
                              env=self.env, capture_output=True, text=True, check=False)

    def simulate_block_cache(self, fail_at):
        self.fake_block = True
        blockdev = self.root / "blockdev"
        blockdev.write_text('''#!/bin/sh
case "$1" in
  --getsize64) stat -c %s "$2" ;;
  --flushbufs)
    sha256sum "$2" | cut -d' ' -f1 >> "$MISC.flushes"
    [ "$(wc -l < "$MISC.flushes")" != "$FAIL_FLUSH_AT" ] ;;
  *) exit 1 ;;
esac
''')
        blockdev.chmod(0o755)
        self.env.update(PATH=str(self.root) + os.pathsep + os.environ["PATH"], FAIL_FLUSH_AT=str(fail_at))

    def test_block_cache_preflight_failure_never_mutates(self):
        original = self.partition.read_bytes()
        self.simulate_block_cache(fail_at=1)
        result = self.write()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("cannot invalidate misc cache - nothing written", result.stderr)
        self.assertEqual(original, self.partition.read_bytes())

    def test_block_cache_flushes_preflight_write_and_rollback_before_verification(self):
        original = self.partition.read_bytes()
        self.simulate_block_cache(fail_at=2)
        result = self.write()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("original misc store restored", result.stderr)
        snapshots = Path(str(self.partition) + ".flushes").read_text().splitlines()
        self.assertEqual([hashlib.sha256(data).hexdigest() for data in
                          (original, self.prefix + self.next, original)], snapshots)
        self.assertEqual(original, self.partition.read_bytes())

    def write(self, digest=None):
        return self.run_script("write", *self.snapshot.stdout.split(), digest or hashlib.sha256(self.next).hexdigest())

    def test_bounded_write_readback_and_prefix_preservation(self):
        self.assertEqual(STORES["android-internal"], (self.root / "before.bin").read_bytes())
        result = self.write()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(self.prefix + self.next, self.partition.read_bytes())

    def test_rejects_stale_snapshot_bad_digest_and_wrong_size(self):
        original = self.partition.read_bytes()
        self.assertNotEqual(0, self.write("0" * 64).returncode)
        self.assertEqual(original, self.partition.read_bytes())
        (self.root / "next.bin").write_bytes(self.next[:-1])
        self.assertNotEqual(0, self.write().returncode)
        self.assertEqual(original, self.partition.read_bytes())
        (self.root / "next.bin").write_bytes(self.next)
        for changed in (original + b"extra", self.prefix + STORES["linux-usb"]):
            self.partition.write_bytes(changed)
            self.assertNotEqual(0, self.write().returncode)
            self.assertEqual(changed, self.partition.read_bytes())

    def test_corrupt_readback_restores_only_the_store(self):
        original = self.partition.read_bytes()
        wrapper = self.root / "dd"
        wrapper.write_text('''#!/bin/sh
/usr/bin/dd "$@" || exit $?
case " $* " in
  *" of=$MISC "*)
    if [ ! -e "$MISC.injected" ]; then
      touch "$MISC.injected"
      printf '\\377' | /usr/bin/dd of="$MISC" bs=1 seek="$(($(stat -c %s "$MISC") - 4096))" count=1 conv=notrunc 2>/dev/null
    fi ;;
esac
''')
        wrapper.chmod(0o755)
        self.env["PATH"] = str(self.root) + os.pathsep + os.environ["PATH"]
        result = self.write()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("original misc store restored", result.stderr)
        self.assertEqual(original, self.partition.read_bytes())


if __name__ == "__main__":
    unittest.main()
