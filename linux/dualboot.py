#!/usr/bin/env python3
# Read or set the ROCKNIX ABL (devinfo) or OpenABL (misc) default boot target, then reboot.
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
# OpenABL uses a validated, CRC-32 sealed store in misc's last 4096 bytes (see edit_store).
import fcntl, hashlib, os, re, stat, struct, subprocess, sys, zlib
from pathlib import Path

DEV = os.environ.get("DEVINFO", "/dev/disk/by-partlabel/devinfo")
BOOT_MODE, BOOT_SOURCE = 0xA92, 0xAF0
SOURCES = {0: "internal", 1: "auto", 2: "usb", 3: "sd"}
BYNAME = os.environ.get("ABL_BY_NAME", "/dev/disk/by-partlabel")
MISC = os.environ.get("MISC", "/dev/disk/by-partlabel/misc")
OPENABL_SOURCES = {0: "auto", 1: "internal", 2: "sd", 3: "usb"}
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
    """Bounded LZMA firmware marker, as used by OpenABL-Android-Installer."""
    image = image[:1048576]
    for at in range(min(65536, len(image) - 12)):
        if image[at:at + 2] != b"\x5d\x00":
            continue
        dictionary = int.from_bytes(image[at + 1:at + 5], "little")
        if not dictionary or dictionary & (dictionary - 1):
            continue
        try:
            import lzma
        except ImportError as e:
            raise SwitchError("cannot inspect bootloader: Python lacks lzma - nothing written") from e
        try:
            decoder = lzma.LZMADecompressor(format=lzma.FORMAT_ALONE, memlimit=64 << 20)
            data = decoder.decompress(image[at:], max_length=4 << 20)
        except lzma.LZMAError:
            continue
        if re.search(rb"OpenABL\x00[0-9]+\.[0-9]+\x00", data):
            return True
    return False


def released_openabl(image, catalog):
    lengths = {size for size, _ in catalog if size <= len(image)}
    hashes = {size: hashlib.sha256(image[:size]).hexdigest() for size in lengths}
    return any(hashes.get(size) == digest for size, digest in catalog)


def uses_openabl():
    try:
        catalog = openabl_catalog()
        markers = []
        for slot in ("a", "b"):
            path = Path(BYNAME) / f"abl_{slot}"
            marker = False
            if path.exists():
                with path.open("rb", buffering=0) as f:
                    image = f.read(1048576)
                    if released_openabl(image, catalog):
                        raise SwitchError(OPENABL_ERROR)
                    marker = openabl_marker(image)
            markers.append(marker)
        if all(markers):
            return True
        if any(markers):
            raise SwitchError(OPENABL_ERROR)
        return False
    except OSError as e:
        raise SwitchError("cannot inspect bootloader - nothing written") from e


def store_records(store, sealed=True):
    """SettingsStoreValid at OpenABL 71c0040; never format or repair a store."""
    if (len(store) != 4096 or store[:8] != b"OABLVARS"
            or struct.unpack_from("<HH", store, 8) != (1, 4096)
            or (sealed and struct.unpack_from("<I", store, 12)[0] != zlib.crc32(store[16:]))
            or store[16] > 96):
        raise SwitchError(OPENABL_ERROR)
    records, names, at = [], set(), 128
    while at < 4096 and store[at]:
        length = store[at]
        data = at + 3 + length
        if length > 64 or data > 4096:
            raise SwitchError(OPENABL_ERROR)
        end = data + struct.unpack_from("<H", store, data - 2)[0]
        name = bytes(store[at + 1:at + 1 + length])
        if end > 4096 or name in names:
            raise SwitchError(OPENABL_ERROR)
        names.add(name)
        records.append((name, at, data, end))
        at = end
    return records


def store_values(store):
    values = {name: store[data:end] for name, _, data, end in store_records(store)}
    # SettingsFromVars ignores wrong-length and out-of-range values.
    def byte(name, maximum):
        value = values.get(name, b"")
        return value[0] if len(value) == 1 and value[0] <= maximum else 0
    return byte(b"BootMode", 1), byte(b"BootSource", 3)


def store_set(store, name, value):
    records = store_records(store, sealed=False)
    last = records[-1][3] if records else 128
    old = next((r for r in records if r[0] == name), None)
    if old and old[3] - old[2] == len(value):
        store[old[2]:old[3]] = value
        return
    end = last - (old[3] - old[1] if old else 0)
    record = bytes([len(name)]) + name + struct.pack("<H", len(value)) + value
    if end + len(record) > 4096:
        raise SwitchError("OpenABL settings store is full - nothing written")
    if old:
        store[old[1]:end] = store[old[3]:last]
    store[end:] = bytes(4096 - end)
    store[end:end + len(record)] = record


def edit_store(before, target, location="", internal=False):
    _, source = store_values(before)
    if target == "android":
        mode, source = 1, 1
    elif target == "linux":
        mode = 0
        source = {"internal": 1, "sd": 2, "usb": 3}.get(location, 2 if source == 1 and not internal else source)
    else:
        raise SwitchError("target must be android or linux")
    if location and location not in ("internal", "sd", "usb"):
        raise SwitchError("location must be sd, internal or usb")
    store = bytearray(before)
    # Seal once, after both edits. Intermediate CRCs are not valid.
    for name, value in ((b"BootMode", mode), (b"BootSource", source)):
        store_set(store, name, bytes([value]))
    values = {name: store[data:end] for name, _, data, end in store_records(store, sealed=False)}
    model = values.get(b"DeviceModel", b"")
    store[16:113] = bytes(97)
    if len(model) <= 96:
        store[16] = len(model)
        store[17:17 + len(model)] = model
    struct.pack_into("<I", store, 12, zlib.crc32(store[16:]))
    store_records(store)
    return bytes(store), source


def read_store(f):
    offset = f.seek(0, os.SEEK_END) - 4096
    if offset < 0:
        raise SwitchError(OPENABL_ERROR)
    f.seek(offset)
    store = f.read(4096)
    store_records(store)
    return offset, store


def openabl_store():
    if not uses_openabl():
        return None
    try:
        with open(MISC, "rb", buffering=0) as f:
            return read_store(f)[1]
    except OSError as e:
        raise SwitchError(OPENABL_ERROR) from e


def flush_misc_cache(f):
    # BLKFLSBUF invalidates block-device pages before readback.
    if stat.S_ISBLK(os.fstat(f.fileno()).st_mode):
        fcntl.ioctl(f.fileno(), 0x1261)


def switch_openabl(target, location):
    try:
        with open(MISC, "r+b", buffering=0) as f:
            fcntl.flock(f, fcntl.LOCK_EX)
            flush_misc_cache(f)
            offset, before = read_store(f)
            after, source = edit_store(before, target, location, internal_linux())
            if read_store(f) != (offset, before):
                raise SwitchError("misc changed since inspection - nothing written")
            try:
                f.seek(offset)
                if f.write(after) != 4096:
                    raise OSError("short write to misc")
                os.fsync(f.fileno())
                flush_misc_cache(f)
                if read_store(f) != (offset, after):
                    raise SwitchError("misc verification failed")
            except (OSError, SwitchError) as e:
                try:
                    f.seek(offset)
                    if f.write(before) != 4096:
                        raise OSError("short restore to misc")
                    os.fsync(f.fileno())
                    flush_misc_cache(f)
                    if read_store(f) != (offset, before):
                        raise OSError("misc restore mismatch")
                except (OSError, SwitchError) as restore:
                    raise SwitchError("misc restore failed - do not reboot") from restore
                raise SwitchError("verification failed - original misc store restored") from e
    except OSError as e:
        raise SwitchError("cannot access misc - nothing written") from e
    return f"{target} {OPENABL_SOURCES[source]}"


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
    openabl_store()
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
    store = openabl_store()
    if store is not None:
        mode, source = store_values(store)
        return f"{'android' if mode else 'linux'} {OPENABL_SOURCES[source]}"
    with open(DEV, "rb", buffering=0) as f:
        d = f.read(4096)
    check(d)
    if d[BOOT_MODE] == 1:
        return f"android {SOURCES.get(_linux_source(d), 'unknown')}"
    return f"linux {SOURCES.get(d[BOOT_SOURCE], 'unknown')}"


def switch(target, location=""):
    """Sets the default boot target the way the ABL menu does. Returns e.g. "android internal"."""
    store = openabl_store()
    overrides = {"sd": 3, "internal": 0, "usb": 2}
    if target not in ("android", "linux"):
        raise SwitchError("target must be android or linux")
    if location and location not in overrides:
        raise SwitchError("location must be sd, internal or usb")
    if store is not None:
        return switch_openabl(target, location)
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
