#!/usr/bin/env python3
# Read or set the ROCKNIX ABL default boot target stored in devinfo, then reboot.
# Usage: bootswitch.py status | android | linux [sd|internal]   (add --no-reboot to skip the reboot)
# Writes only BootMode (0xA92) and BootSourceMode (0xAF0), the same two bytes the ABL menu changes.
import os, subprocess, sys

DEV = os.environ.get("DEVINFO", "/dev/disk/by-partlabel/devinfo")
SRC_SD = 0x03
SRC_INTERNAL = None
BOOT_MODE, BOOT_SOURCE = 0xA92, 0xAF0


def fail(msg):
    sys.exit(f"! {msg}")


def check(d):
    ok = (d[:13] == b"ANDROID-BOOT!"
          and d[0xA54:0xA62] == b"\x01\x00\x08\x00\x01\x00BootMode"
          and d[0xAB2:0xAC6] == b"\x01\x00\x0e\x00\x01\x00BootSourceMode"
          and d[BOOT_MODE] in (0, 1))
    if not ok:
        fail("devinfo layout not recognised - nothing written")


def source_name(v):
    return "sd" if v == SRC_SD else "internal" if v == SRC_INTERNAL else "other"


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    cmd = args[0] if args else ""
    with open(DEV, "r+b" if cmd in ("android", "linux") else "rb", buffering=0) as f:
        before = f.read(4096)
        check(before)
        if cmd == "status":
            print(("android" if before[BOOT_MODE] == 1 else "linux"), source_name(before[BOOT_SOURCE]))
            return
        if cmd == "android":
            mode, source = 1, 0
        elif cmd == "linux":
            arg = args[1] if len(args) > 1 else ""
            if arg == "sd":
                source = SRC_SD
            elif arg == "internal":
                if SRC_INTERNAL is None:
                    fail("internal boot source not supported yet")
                source = SRC_INTERNAL
            elif arg == "":
                source = before[BOOT_SOURCE] or SRC_SD
            else:
                fail("boot source must be sd or internal")
            mode = 0
        else:
            fail("usage: bootswitch.py status | android | linux [sd|internal] [--no-reboot]")

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
    print(f"{cmd} ok")
    if "--no-reboot" not in sys.argv:
        subprocess.run(["systemctl", "reboot"])


if __name__ == "__main__":
    main()
