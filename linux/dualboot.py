#!/usr/bin/env python3
# Read or set the ROCKNIX ABL default boot target stored in devinfo, then reboot.
# Usage: dualboot.py status | targets | android | linux [sd|internal|usb]   (add --no-reboot to skip the reboot)
#   targets prints one Linux location per line, "<location> <name>": internal and sd when present (name from the
#   first partition's label, e.g. Armada, ROCKNIX), and usb, which cannot be detected before booting.
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


class SwitchError(Exception):
    pass


def _read(path):
    with open(path) as f:
        return f.read()


def check(d):
    ok = (d[:13] == b"ANDROID-BOOT!"
          and d[0xA54:0xA62] == b"\x01\x00\x08\x00\x01\x00BootMode"
          and d[0xAB2:0xAC6] == b"\x01\x00\x0e\x00\x01\x00BootSourceMode"
          and d[BOOT_MODE] in (0, 1))
    if not ok:
        raise SwitchError("devinfo layout not recognised - nothing written")


def internal_part():
    """First partition after userdata on the same disk, or None. Linux installed internally
    (by Armada/ROCKNIX) lives there."""
    ud = os.path.basename(os.path.realpath("/dev/disk/by-partlabel/userdata"))
    try:
        n = int(_read(f"/sys/class/block/{ud}/partition"))
    except OSError:
        return None
    disk = os.path.basename(os.path.realpath(f"/sys/class/block/{ud}/.."))
    after = []
    for p in os.listdir(f"/sys/class/block/{disk}"):
        try:
            pn = int(_read(f"/sys/class/block/{disk}/{p}/partition"))
        except (OSError, ValueError):
            continue
        if pn > n:
            after.append((pn, p))
    return min(after)[1] if after else None


def internal_linux():
    return internal_part() is not None


def _fat_label(dev):
    try:
        with open(dev, "rb") as f:
            b = f.read(512)
    except OSError:
        return ""
    if b[0x52:0x57] == b"FAT32":
        label = b[0x47:0x52]
    elif b[0x36:0x39] == b"FAT":
        label = b[0x2B:0x36]
    else:
        return ""
    label = label.decode("ascii", "ignore").strip()
    return "" if label == "NO NAME" else label


def os_name(part):
    """Friendly OS name from a partition's GPT name or FAT label."""
    label = ""
    try:
        for line in _read(f"/sys/class/block/{part}/uevent").splitlines():
            if line.startswith("PARTNAME="):
                label = line.split("=", 1)[1].strip()
    except OSError:
        pass
    label = label or _fat_label(f"/dev/{part}")
    for prefix, name in (("ARMADA", "Armada"), ("ROCKNIX", "ROCKNIX"), ("BATOCERA", "Batocera"), ("KNULLI", "Knulli")):
        if label.upper().startswith(prefix):
            return name
    return label or "Linux"


def sd_card():
    """The SD card's block device name (e.g. mmcblk0), or None."""
    for name in os.listdir("/sys/class/block"):
        d = f"/sys/class/block/{name}"
        if name.startswith("mmcblk") and not os.path.exists(f"{d}/partition"):
            try:
                if _read(f"{d}/device/type").strip() == "SD":
                    return name
            except OSError:
                pass
    return None


def targets():
    """Linux locations to offer as (location, OS name): internal and sd when present, usb always."""
    result = []
    part = internal_part()
    if part:
        result.append(("internal", os_name(part)))
    sd = sd_card()
    if sd:
        result.append(("sd", os_name(f"{sd}p1")))
    result.append(("usb", "Linux"))
    return result


def _linux_source(d):
    return d[BOOT_SOURCE] or (0 if internal_linux() else 3)


def status():
    """Returns e.g. "android sd" (default boot, then where Linux boots from)."""
    with open(DEV, "rb", buffering=0) as f:
        d = f.read(4096)
    check(d)
    if d[BOOT_MODE] == 1:
        return f"android {SOURCES.get(_linux_source(d), 'unknown')}"
    return f"linux {SOURCES.get(d[BOOT_SOURCE], 'unknown')}"


def switch(target, location=""):
    """Sets the default boot target the way the ABL menu does. Returns e.g. "android internal"."""
    overrides = {"sd": 3, "internal": 0, "usb": 2}
    if target not in ("android", "linux"):
        raise SwitchError("target must be android or linux")
    if location and location not in overrides:
        raise SwitchError("location must be sd, internal or usb")
    with open(DEV, "r+b", buffering=0) as f:
        before = f.read(4096)
        check(before)
        if target == "android":
            mode, source = 1, 0
        else:
            mode, source = 0, overrides.get(location, _linux_source(before))
        if (before[BOOT_MODE], before[BOOT_SOURCE]) != (mode, source):
            f.seek(BOOT_MODE); f.write(bytes([mode]))
            f.seek(BOOT_SOURCE); f.write(bytes([source]))
            os.fsync(f.fileno())
        f.seek(0)
        after = f.read(4096)
        changed = {i for i in range(4096) if before[i] != after[i]}
        if not changed <= {BOOT_MODE, BOOT_SOURCE} or (after[BOOT_MODE], after[BOOT_SOURCE]) != (mode, source):
            f.seek(0); f.write(before); os.fsync(f.fileno())
            raise SwitchError("verification failed - original devinfo restored")
    return f"{target} {SOURCES.get(source, 'unknown')}"


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    cmd = args[0] if args else ""
    try:
        if cmd == "status":
            print(status())
            return
        if cmd == "targets":
            print("\n".join(f"{loc} {name}" for loc, name in targets()))
            return
        if cmd not in ("android", "linux"):
            raise SwitchError("usage: dualboot.py status | targets | android | linux [sd|internal|usb] [--no-reboot]")
        print(switch(cmd, args[1] if len(args) > 1 else "") + " ok")
    except SwitchError as e:
        sys.exit(f"! {e}")
    if "--no-reboot" not in sys.argv:
        subprocess.run(["systemctl", "reboot"])


if __name__ == "__main__":
    main()
