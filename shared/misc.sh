#!/system/bin/sh
# Bounded I/O only; the app validates/edits OpenABL stores and CRC-32 in Kotlin.
# Usage: misc.sh read <private dir> | write <private dir> <offset> <prefix sha256> <next sha256>
# MISC overrides the partition path for fixture tests.
P=${MISC:-/dev/block/by-name/misc}
D=$2
fail() { echo "! $1" >&2; exit 1; }
[ -d "$D" ] || fail "missing misc inspection directory - nothing written"
if [ -b "$P" ]; then SIZE=$(blockdev --getsize64 "$P")
else SIZE=$(stat -c %s "$P"); fi
case "$SIZE" in ""|*[!0-9]*) fail "cannot size misc - nothing written" ;; esac
[ "$SIZE" -ge 4096 ] || fail "OpenABL is not supported yet - nothing written"
OFFSET=$((SIZE - 4096))

read_tail() {
  dd if="$P" of="$D/check.bin" bs=1 skip="$OFFSET" count=4096 2>/dev/null &&
    [ "$(wc -c < "$D/check.bin")" -eq 4096 ]
}
prefix_hash() {
  head -c "$OFFSET" "$P" > "$D/prefix.bin" &&
    [ "$(wc -c < "$D/prefix.bin")" -eq "$OFFSET" ] || return 1
  sha256sum "$D/prefix.bin" | cut -d' ' -f1
}
write_tail() {
  dd if="$1" of="$P" bs=1 seek="$OFFSET" count=4096 conv=notrunc,fsync 2>/dev/null
}
flush_cache() {
  if [ -b "$P" ]; then blockdev --flushbufs "$P"; fi
}
restore() {
  trap - HUP INT TERM
  if write_tail "$D/before.bin" && flush_cache && read_tail && cmp -s "$D/before.bin" "$D/check.bin"; then
    fail "verification failed - original misc store restored"
  fi
  fail "misc restore failed - do not reboot"
}

case "$1" in
  read)
    read_tail || fail "cannot read misc - nothing written"
    cp "$D/check.bin" "$D/before.bin" || fail "cannot back up misc - nothing written"
    PREFIX=$(prefix_hash) || fail "cannot read misc prefix - nothing written"
    echo "$OFFSET $PREFIX" ;;
  write)
    flush_cache || fail "cannot invalidate misc cache - nothing written"
    [ "$3" = "$OFFSET" ] || fail "misc size changed - nothing written"
    [ "$(wc -c < "$D/before.bin")" -eq 4096 ] &&
      [ "$(wc -c < "$D/next.bin")" -eq 4096 ] || fail "invalid misc edit size - nothing written"
    [ "$(sha256sum "$D/next.bin" | cut -d' ' -f1)" = "$5" ] || fail "misc edit checksum mismatch - nothing written"
    PREFIX=$(prefix_hash) || fail "cannot read misc prefix - nothing written"
    [ "$PREFIX" = "$4" ] && read_tail && cmp -s "$D/before.bin" "$D/check.bin" ||
      fail "misc changed since inspection - nothing written"
    trap restore HUP INT TERM
    write_tail "$D/next.bin" || restore
    flush_cache || restore
    read_tail && cmp -s "$D/next.bin" "$D/check.bin" || restore
    PREFIX=$(prefix_hash) && [ "$PREFIX" = "$4" ] || restore
    trap - HUP INT TERM
    echo ok ;;
  *) fail "usage: misc.sh read <dir> | write <dir> <offset> <prefix sha256> <next sha256>" ;;
esac
