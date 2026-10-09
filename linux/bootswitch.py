#!/usr/bin/env python3
# Read or set the ROCKNIX ABL default boot target stored in devinfo, then reboot.
# Usage: bootswitch.py status | android | linux [sd|internal|usb]   (add --no-reboot to skip the reboot)
#   linux without a location picks it like the ABL menu does (below); a location overrides that.
#
# Mirrors "Switch boot mode" in the ROCKNIX ABL v1.2 menu (LinuxLoader):
#   BootMode       (0xA92): 0 = Linux, 1 = Android
#   BootSourceMode (0xAF0): 0 = Internal, 1 = Auto, 2 = USB, 3 = SDcard
#   Linux -> Android: BootMode=1, BootSourceMode reset to 0
#   Android -> Linux: BootMode=0; if BootSourceMode is 0 and no Linux is installed after
#                     userdata on internal storage, BootSourceMode=3 (SDcard)
# Only these two bytes are written; anything else changing restores the original.
import os, subprocess, sys

DEV = os.environ.get("DEVINFO", "/dev/disk/by-partlabel/devinfo")
BOOT_MODE, BOOT_SOURCE = 0xA92, 0xAF0
SOURCES = {0: "internal", 1: "auto", 2: "usb", 3: "sd"}


def fail(msg):
    sys.exit(f"! {msg}")


def check(d):
    ok = (d[:13] == b"ANDROID-BOOT!"
          and d[0xA54:0xA62] == b"\x01\x00\x08\x00\x01\x00BootMode"
          and d[0xAB2:0xAC6] == b"\x01\x00\x0e\x00\x01\x00BootSourceMode"
          and d[BOOT_MODE] in (0, 1))
    if not ok:
        fail("devinfo layout not recognised - nothing written")


def internal_linux():
    """Linux installed internally = partitions after userdata on the same disk (how Armada/ROCKNIX install it)."""
    ud = os.path.basename(os.path.realpath("/dev/disk/by-partlabel/userdata"))
    try:
        n = int(open(f"/sys/class/block/{ud}/partition").read())
    except OSError:
        return False
    disk = os.path.basename(os.path.realpath(f"/sys/class/block/{ud}/.."))
    for p in os.listdir(f"/sys/class/block/{disk}"):
        try:
            if int(open(f"/sys/class/block/{disk}/{p}/partition").read()) > n:
                return True
        except (OSError, ValueError):
            pass
    return False


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    cmd = args[0] if args else ""
    with open(DEV, "r+b" if cmd in ("android", "linux") else "rb", buffering=0) as f:
        before = f.read(4096)
        check(before)
        cur_source = before[BOOT_SOURCE]
        linux_source = cur_source or (0 if internal_linux() else 3)
        if cmd == "status":
            if before[BOOT_MODE] == 1:
                print("android", SOURCES.get(linux_source, "unknown"))
            else:
                print("linux", SOURCES.get(cur_source, "unknown"))
            return
        if cmd == "android":
            mode, source = 1, 0
        elif cmd == "linux":
            location = args[1] if len(args) > 1 else ""
            overrides = {"sd": 3, "internal": 0, "usb": 2}
            if location and location not in overrides:
                fail("location must be sd, internal or usb")
            mode, source = 0, overrides.get(location, linux_source)
        else:
            fail("usage: bootswitch.py status | android | linux [sd|internal|usb] [--no-reboot]")

        if (before[BOOT_MODE], before[BOOT_SOURCE]) != (mode, source):
            f.seek(BOOT_MODE); f.write(bytes([mode]))
            f.seek(BOOT_SOURCE); f.write(bytes([source]))
            os.fsync(f.fileno())
        f.seek(0)
        after = f.read(4096)
        changed = {i for i in range(4096) if before[i] != after[i]}
        if not changed <= {BOOT_MODE, BOOT_SOURCE} or (after[BOOT_MODE], after[BOOT_SOURCE]) != (mode, source):
            f.seek(0); f.write(before); os.fsync(f.fileno())
            fail("verification failed - original devinfo restored, not rebooting")
    print(f"{cmd} {SOURCES.get(source, 'unknown')} ok")
    if "--no-reboot" not in sys.argv:
        subprocess.run(["systemctl", "reboot"])


if __name__ == "__main__":
    main()
