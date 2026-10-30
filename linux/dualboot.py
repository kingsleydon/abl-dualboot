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
import hashlib, lzma, os, re, subprocess, sys
from pathlib import Path

DEV = os.environ.get("DEVINFO", "/dev/disk/by-partlabel/devinfo")
BOOT_MODE, BOOT_SOURCE = 0xA92, 0xAF0
SOURCES = {0: "internal", 1: "auto", 2: "usb", 3: "sd"}
BYNAME = "/dev/disk/by-partlabel"
EFIVARS = "/sys/firmware/efi/efivars"
OPENABL_ERROR = "OpenABL is not supported yet - nothing written"


class SwitchError(Exception):
    pass


def openabl_catalog():
    path = Path(__file__).resolve().with_name("openabl-images.txt")
    if not path.is_file():
        path = path.parent.parent / "shared/openabl-images.txt"
    try:
        return [(int(size), digest) for line in path.read_text().splitlines()
                if line and not line.startswith("#")
                for _, _, digest, size in [line.split()]]
    except (OSError, ValueError) as e:
        raise SwitchError("cannot read OpenABL image catalog - nothing written") from e


def openabl_marker(image):
    """OpenABL-Android-Installer's marker approach: a bounded LZMA firmware volume.
    Inspect at most 1 MiB of input, 64 KiB of headers and 4 MiB of output."""
    image = image[:1048576]
    for at in range(min(65536, len(image) - 12)):
        if image[at:at + 2] != b"\x5d\x00":
            continue
        dictionary = int.from_bytes(image[at + 1:at + 5], "little")
        if not dictionary or dictionary & (dictionary - 1):
            continue
        try:
            decoder = lzma.LZMADecompressor(format=lzma.FORMAT_ALONE, memlimit=64 << 20)
            data = decoder.decompress(image[at:], max_length=4 << 20)
        except lzma.LZMAError:
            continue
        if re.search(rb"OpenABL\x00[0-9]+\.[0-9]+\x00", data):
            return True
    return False


def abl_kind(image, catalog):
    lengths = {size for size, _ in catalog if size <= len(image)}
    hashes = {size: hashlib.sha256(image[:size]).hexdigest() for size in lengths}
    if any(hashes.get(size) == digest for size, digest in catalog) or openabl_marker(image):
        return "openabl"
    if b"qtestsign" in image:
        return "rocknix"
    return "stock" if b"QUALCOMM" in image else "unknown"


def refuse_openabl():
    # Same runtime signals used by Armada's openabl-updater/abl-version.
    directory = Path(EFIVARS)
    try:
        if (directory / "SettingsSchema-8c5f2b7e-3a41-4d6c-9e2f-5b7a1c0d4e93").exists():
            raise SwitchError(OPENABL_ERROR)
        loader = directory / "LoaderInfo-4a67b082-0a4c-41cf-b6c7-440b29bb8c4f"
        if loader.exists() and loader.read_bytes()[4:].decode("utf-16-le").startswith("OpenABL "):
            raise SwitchError(OPENABL_ERROR)
        catalog = openabl_catalog()
        for slot in ("a", "b"):
            path = Path(BYNAME) / f"abl_{slot}"
            if path.exists():
                with path.open("rb", buffering=0) as f:
                    if abl_kind(f.read(1048576), catalog) == "openabl":
                        raise SwitchError(OPENABL_ERROR)
    except (OSError, UnicodeError) as e:
        raise SwitchError("cannot inspect bootloader - nothing written") from e


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
    refuse_openabl()
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
    refuse_openabl()
    with open(DEV, "rb", buffering=0) as f:
        d = f.read(4096)
    check(d)
    if d[BOOT_MODE] == 1:
        return f"android {SOURCES.get(_linux_source(d), 'unknown')}"
    return f"linux {SOURCES.get(d[BOOT_SOURCE], 'unknown')}"


def switch(target, location=""):
    """Sets the default boot target the way the ABL menu does. Returns e.g. "android internal"."""
    refuse_openabl()
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
