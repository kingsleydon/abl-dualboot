#!/system/bin/sh
# ROCKNIX ABL helper.
# Usage: abl.sh status
#        abl.sh flash <abl.elf> <sha256> <backup dir>
# A slot is "rocknix" when its ELF carries the qtestsign certificate every ROCKNIX ABL build is signed with,
# "stock" when it carries Qualcomm's. Stock slots are backed up before the first flash and never overwritten.
fail() { echo "! $1" >&2; exit 1; }

soc() {
  case "$(getprop ro.board.platform)" in
    kona) echo SM8250 ;; kalama) echo SM8550 ;; pineapple) echo SM8650 ;; sun) echo SM8750 ;; bengal) echo SM6115 ;;
    *) echo unknown ;;
  esac
}

kind() {
  if dd if=/dev/block/by-name/abl_$1 bs=1048576 count=1 2>/dev/null | grep -q qtestsign; then echo rocknix
  elif dd if=/dev/block/by-name/abl_$1 bs=1048576 count=1 2>/dev/null | grep -q QUALCOMM; then echo stock
  else echo unknown; fi
}

prefix_sha() { head -c "$2" "/dev/block/by-name/abl_$1" | sha256sum | cut -d' ' -f1; }

case "$1" in
  status)
    echo "soc=$(soc) a=$(kind a) b=$(kind b)" ;;
  flash)
    ELF=$2; SHA=$3; BAK=$4
    [ "$(soc)" != unknown ] || fail "unsupported SoC $(getprop ro.board.platform)"
    [ -b /dev/block/by-name/abl_a ] && [ -b /dev/block/by-name/abl_b ] || fail "abl_a/abl_b partitions not found"
    [ "$(sha256sum "$ELF" | cut -d' ' -f1)" = "$SHA" ] || fail "ABL file checksum mismatch"
    [ "$(head -c 4 "$ELF" | xxd -p)" = 7f454c46 ] || fail "ABL file is not an ELF"
    SIZE=$(stat -c %s "$ELF")
    [ "$SIZE" -le "$(blockdev --getsize64 /dev/block/by-name/abl_a)" ] || fail "ABL file larger than partition"
    mkdir -p "$BAK" || fail "cannot create backup folder"
    for s in a b; do
      if [ "$(kind $s)" = stock ] && [ ! -f "$BAK/stock-abl_$s.img" ]; then
        dd if=/dev/block/by-name/abl_$s of="$BAK/stock-abl_$s.img" 2>/dev/null || fail "backup of abl_$s failed - nothing written"
        echo "- Backed up stock abl_$s to $BAK/stock-abl_$s.img"
      fi
    done
    for s in b a; do
      if [ "$(prefix_sha $s $SIZE)" = "$SHA" ]; then echo "- abl_$s already up to date"; continue; fi
      dd if="$ELF" of=/dev/block/by-name/abl_$s bs=4096 conv=fsync 2>/dev/null
      if [ "$(prefix_sha $s $SIZE)" = "$SHA" ]; then echo "- abl_$s flashed and verified"; continue; fi
      [ -f "$BAK/stock-abl_$s.img" ] && dd if="$BAK/stock-abl_$s.img" of=/dev/block/by-name/abl_$s conv=fsync 2>/dev/null
      fail "abl_$s verification failed - slot restored from backup, do not reboot"
    done
    sync; echo "- Done" ;;
  *) fail "usage: $0 status | flash <elf> <sha256> <backup dir>" ;;
esac
