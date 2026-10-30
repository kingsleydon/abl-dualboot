#!/system/bin/sh
# ROCKNIX ABL helper.
# Usage: abl.sh status [<inspection dir>]
#        abl.sh flash <abl.elf> <sha256> <backup dir> <a kind> <b kind>
# OpenABL release hashes are checked first: OpenABL also carries qtestsign.
# A slot is "rocknix" when its ELF carries the qtestsign certificate every ROCKNIX ABL build is signed with,
# "stock" when it carries Qualcomm's. Stock slots are backed up before the first flash and never overwritten.
fail() { echo "! $1" >&2; exit 1; }
DIR=$(cd "$(dirname "$0")" && pwd)
BYNAME=${ABL_BY_NAME:-/dev/block/by-name}
CATALOG=$DIR/openabl-images.txt
[ -r "$CATALOG" ] || fail "OpenABL image catalog not found - nothing written"

openabl() {
  for length in $(sed '/^#/d' "$CATALOG" | cut -d' ' -f4 | sort -u); do
    hash=$(head -c "$length" "$1" 2>/dev/null | sha256sum | cut -d' ' -f1)
    grep -q " $hash $length$" "$CATALOG" && return 0
  done
  return 1
}

soc() {
  case "$(getprop ro.board.platform)" in
    kona) echo SM8250 ;; kalama) echo SM8550 ;; pineapple) echo SM8650 ;; sun) echo SM8750 ;; bengal) echo SM6115 ;;
    *) echo unknown ;;
  esac
}

kind() {
  if openabl "$1"; then echo openabl
  elif dd if="$1" bs=1048576 count=1 2>/dev/null | grep -q qtestsign; then echo rocknix
  elif dd if="$1" bs=1048576 count=1 2>/dev/null | grep -q QUALCOMM; then echo stock
  else echo unknown; fi
}

prefix_sha() { head -c "$2" "$BYNAME/abl_$1" | sha256sum | cut -d' ' -f1; }

case "$1" in
  status)
    # The app owns these files and inspects the compressed marker in Kotlin.
    if [ -n "$2" ]; then
      for s in a b; do
        head -c 1048576 "$BYNAME/abl_$s" > "$2/abl_$s.img" || fail "cannot read abl_$s"
      done
    fi
    echo "soc=$(soc) a=$(kind "$BYNAME/abl_a") b=$(kind "$BYNAME/abl_b")" ;;
  flash)
    ELF=$2; SHA=$3; BAK=$4
    for s in a b; do
      case "$(kind "$BYNAME/abl_$s")" in
        openabl) fail "OpenABL is not supported yet - nothing written" ;;
        rocknix|stock) ;;
        *) fail "unknown ABL - nothing written" ;;
      esac
    done
    [ "$#" -eq 6 ] || fail "missing ABL kinds - nothing written"
    case "$5$6" in *[!a-z]*) fail "invalid ABL kinds - nothing written" ;; esac
    case "$5 $6" in *openabl*) fail "OpenABL is not supported yet - nothing written" ;; esac
    printf '%s %s\n' "$5" "$6" | LC_ALL=C grep -Eq '^(rocknix|stock) (rocknix|stock)$' || fail "invalid ABL kinds - nothing written"
    [ "$(soc)" != unknown ] || fail "unsupported SoC $(getprop ro.board.platform)"
    [ -b "$BYNAME/abl_a" ] && [ -b "$BYNAME/abl_b" ] || fail "abl_a/abl_b partitions not found"
    [ "$(sha256sum "$ELF" | cut -d' ' -f1)" = "$SHA" ] || fail "ABL file checksum mismatch"
    [ "$(head -c 4 "$ELF" | xxd -p)" = 7f454c46 ] || fail "ABL file is not an ELF"
    SIZE=$(stat -c %s "$ELF")
    [ "$SIZE" -le "$(blockdev --getsize64 "$BYNAME/abl_a")" ] || fail "ABL file larger than partition"
    mkdir -p "$BAK" || fail "cannot create backup folder"
    for s in a b; do
      if [ "$(kind "$BYNAME/abl_$s")" = stock ] && [ ! -f "$BAK/stock-abl_$s.img" ]; then
        dd if="$BYNAME/abl_$s" of="$BAK/stock-abl_$s.img" 2>/dev/null || fail "backup of abl_$s failed - nothing written"
        echo "- Backed up stock abl_$s to $BAK/stock-abl_$s.img"
      fi
    done
    for s in b a; do
      if [ "$(prefix_sha $s $SIZE)" = "$SHA" ]; then echo "- abl_$s already up to date"; continue; fi
      dd if="$ELF" of="$BYNAME/abl_$s" bs=4096 conv=fsync 2>/dev/null
      if [ "$(prefix_sha $s $SIZE)" = "$SHA" ]; then echo "- abl_$s flashed and verified"; continue; fi
      [ -f "$BAK/stock-abl_$s.img" ] && dd if="$BAK/stock-abl_$s.img" of="$BYNAME/abl_$s" conv=fsync 2>/dev/null
      fail "abl_$s verification failed - slot restored from backup, do not reboot"
    done
    sync; echo "- Done" ;;
  *) fail "usage: $0 status | flash <elf> <sha256> <backup dir>" ;;
esac
