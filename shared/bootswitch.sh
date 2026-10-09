#!/system/bin/sh
# Read or set the ROCKNIX ABL default boot target stored in devinfo.
# Usage: bootswitch.sh status|android|linux [devinfo path]
# Writes only BootMode (0xA92) and BootSourceMode (0xAF0), the same two bytes the ABL menu changes,
# then verifies nothing else changed and restores the original if it did.
P=${2:-/dev/block/by-name/devinfo}
TMP=${BOOTSWITCH_TMP:-/data/local/tmp}

hex() { dd if="$P" bs=1 skip=$(($1)) count=$2 2>/dev/null | xxd -p | tr -d '\n'; }
putb() { printf "\\$(printf %03o $((0x$1)))" | dd of="$2" bs=1 seek=$(($3)) count=1 conv=notrunc,fsync 2>/dev/null; }
fail() { echo "! $1" >&2; exit 1; }

[ "$(hex 0x0 13)" = 414e44524f49442d424f4f5421 ] || fail "devinfo layout not recognised - nothing written"
[ "$(hex 0xA54 14)" = 010008000100426f6f744d6f6465 ] || fail "BootMode key not found - nothing written"
[ "$(hex 0xAB2 20)" = 01000e000100426f6f74536f757263654d6f6465 ] || fail "BootSourceMode key not found - nothing written"
CUR="$(hex 0xA92 1)$(hex 0xAF0 1)"

case "$1" in
  status)
    case "$CUR" in 0100) echo android ;; 0003) echo linux ;; *) echo "unknown:$CUR" ;; esac
    exit 0 ;;
  android) MODE=01; SRC=00 ;;
  linux)   MODE=00; SRC=03 ;;
  *) fail "usage: $0 status|android|linux" ;;
esac
[ "$CUR" = 0100 ] || [ "$CUR" = 0003 ] || fail "unexpected current value $CUR - nothing written"

BEFORE=$(sha256sum "$P" | cut -d' ' -f1)
cp "$P" "$TMP/devinfo-before-switch.img" || fail "cannot back up devinfo - nothing written"
if [ "$CUR" != "$MODE$SRC" ]; then
  putb $MODE "$P" 0xA92
  putb $SRC "$P" 0xAF0
fi
# verify: target bytes set, and nothing else changed
cp "$P" "$TMP/devinfo-check.img"
putb ${CUR%??} "$TMP/devinfo-check.img" 0xA92
putb ${CUR#??} "$TMP/devinfo-check.img" 0xAF0
if [ "$(hex 0xA92 1)$(hex 0xAF0 1)" != "$MODE$SRC" ] || [ "$(sha256sum "$TMP/devinfo-check.img" | cut -d' ' -f1)" != "$BEFORE" ]; then
  dd if="$TMP/devinfo-before-switch.img" of="$P" conv=fsync 2>/dev/null
  rm -f "$TMP/devinfo-check.img"
  fail "verification failed - original devinfo restored"
fi
rm -f "$TMP/devinfo-check.img"
echo "$1"
