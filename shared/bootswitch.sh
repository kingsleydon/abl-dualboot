#!/system/bin/sh
# Read or set the ROCKNIX ABL default boot target stored in devinfo.
# Usage: bootswitch.sh status | targets | android | linux [sd|internal|usb]
#   targets prints one Linux location per line, "<location> <name>": internal and sd when present (name from the
#   first partition's label, e.g. Armada, ROCKNIX), and usb, which cannot be detected before booting.
#   linux without a location picks it like the ABL menu does (below); a location overrides that.
# DEVINFO overrides the partition path (for testing on a copy).
#
# Mirrors "Switch boot mode" in the ROCKNIX ABL v1.2 menu (LinuxLoader):
#   BootMode       (0xA92): 0 = Linux, 1 = Android
#   BootSourceMode (0xAF0): 0 = Internal, 1 = Auto, 2 = USB, 3 = SDcard
#   Linux -> Android: BootMode=1, BootSourceMode reset to 0
#   Android -> Linux: BootMode=0; if BootSourceMode is 0 and no Linux is installed after
#                     userdata on internal storage, BootSourceMode=3 (SDcard)
# Only these two bytes are written; anything else changing restores the original.
P=${DEVINFO:-/dev/block/by-name/devinfo}
TMP=${BOOTSWITCH_TMP:-/data/local/tmp}

hex() { dd if="$P" bs=1 skip=$(($1)) count=$2 2>/dev/null | xxd -p | tr -d '\n'; }
putb() { printf "\\$(printf %03o $((0x$1)))" | dd of="$2" bs=1 seek=$(($3)) count=1 conv=notrunc,fsync 2>/dev/null; }
fail() { echo "! $1" >&2; exit 1; }

# Linux installed internally = partitions after userdata on the same disk (how Armada/ROCKNIX install it).
# Prints the first such partition's sysfs name.
internal_part() {
  ud=$(basename "$(readlink -f /dev/block/by-name/userdata)")
  n=$(cat "/sys/class/block/$ud/partition" 2>/dev/null) || return 1
  disk=${ud%%[0-9]*}; best=""; bestn=999
  for p in /sys/class/block/$disk[0-9]*; do
    pn=$(cat "$p/partition" 2>/dev/null || echo 0)
    [ "$pn" -gt "$n" ] && [ "$pn" -lt "$bestn" ] && { best=$(basename "$p"); bestn=$pn; }
  done
  [ -n "$best" ] && echo "$best"
}
internal_linux() { [ -n "$(internal_part)" ]; }
# Prints the SD card's sysfs name.
sd_card() {
  for d in /sys/class/block/mmcblk[0-9]*; do
    [ -e "$d/partition" ] && continue
    [ "$(cat "$d/device/type" 2>/dev/null)" = SD ] && { basename "$d"; return 0; }
  done
  return 1
}
# Friendly OS name from a partition's GPT name or filesystem label.
os_name() {
  label=$(sed -n 's/^PARTNAME=//p' "/sys/class/block/$1/uevent" 2>/dev/null)
  [ -n "$label" ] || label=$(blkid "/dev/block/$1" 2>/dev/null | sed -n 's/.* LABEL="\([^"]*\)".*/\1/p')
  case "$label" in
    ARMADA*) echo Armada ;; ROCKNIX*) echo ROCKNIX ;; BATOCERA*) echo Batocera ;; KNULLI*) echo Knulli ;;
    ""|"NO NAME") echo Linux ;; *) echo "$label" ;;
  esac
}
source_name() { case "$1" in 00) echo internal ;; 01) echo auto ;; 02) echo usb ;; 03) echo sd ;; *) echo "unknown" ;; esac; }

[ "$(hex 0x0 13)" = 414e44524f49442d424f4f5421 ] || fail "devinfo layout not recognised - nothing written"
[ "$(hex 0xA54 14)" = 010008000100426f6f744d6f6465 ] || fail "BootMode key not found - nothing written"
[ "$(hex 0xAB2 20)" = 01000e000100426f6f74536f757263654d6f6465 ] || fail "BootSourceMode key not found - nothing written"
MODE_CUR=$(hex 0xA92 1); SRC_CUR=$(hex 0xAF0 1); CUR=$MODE_CUR$SRC_CUR
case "$MODE_CUR" in 00|01) ;; *) fail "unexpected BootMode $MODE_CUR - nothing written" ;; esac

# Boot source Linux would use after switching (same rule as the ABL menu)
if [ "$SRC_CUR" != 00 ]; then LINUX_SRC=$SRC_CUR; elif internal_linux; then LINUX_SRC=00; else LINUX_SRC=03; fi

case "$1" in
  status)
    [ "$MODE_CUR" = 01 ] && echo "android $(source_name $LINUX_SRC)" || echo "linux $(source_name $SRC_CUR)"
    exit 0 ;;
  targets)
    part=$(internal_part) && echo "internal $(os_name "$part")"
    sd=$(sd_card) && echo "sd $(os_name "${sd}p1")"
    echo "usb Linux"
    exit 0 ;;
  android) MODE=01; SRC=00 ;;
  linux)
    MODE=00
    case "$2" in
      "") SRC=$LINUX_SRC ;;
      sd) SRC=03 ;; internal) SRC=00 ;; usb) SRC=02 ;;
      *) fail "location must be sd, internal or usb" ;;
    esac ;;
  *) fail "usage: $0 status | targets | android | linux [sd|internal|usb]" ;;
esac

BEFORE=$(sha256sum "$P" | cut -d' ' -f1)
cp "$P" "$TMP/devinfo-before-switch.img" || fail "cannot back up devinfo - nothing written"
if [ "$CUR" != "$MODE$SRC" ]; then
  putb $MODE "$P" 0xA92
  putb $SRC "$P" 0xAF0
fi
# verify: target bytes set, and nothing else changed
cp "$P" "$TMP/devinfo-check.img"
putb $MODE_CUR "$TMP/devinfo-check.img" 0xA92
putb $SRC_CUR "$TMP/devinfo-check.img" 0xAF0
if [ "$(hex 0xA92 1)$(hex 0xAF0 1)" != "$MODE$SRC" ] || [ "$(sha256sum "$TMP/devinfo-check.img" | cut -d' ' -f1)" != "$BEFORE" ]; then
  dd if="$TMP/devinfo-before-switch.img" of="$P" conv=fsync 2>/dev/null
  rm -f "$TMP/devinfo-check.img"
  fail "verification failed - original devinfo restored"
fi
rm -f "$TMP/devinfo-check.img"
echo "$1 $(source_name $SRC) ok"
