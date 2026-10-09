#!/system/bin/sh
# Flash bundled ROCKNIX ABL (SM8550) to abl_b and abl_a, verifying each by readback.
MODDIR=${0%/*}
ELF=$MODDIR/abl.elf
SHA=8c27a70961476b327071ada2fd9b9c55dd2dcdc913eff3d33f2576133d7e398c
SIZE=258048

fail() { echo "! $1"; echo "! Nothing more was written. Do NOT reboot if a slot failed - ask for help."; exit 1; }

[ "$(getprop ro.product.device)" = odin2portal ] || fail "Not an Odin 2 Portal"
[ "$(getprop ro.board.platform)" = kalama ] || fail "Not SM8550 (kalama)"
[ "$(sha256sum "$ELF" | cut -d' ' -f1)" = $SHA ] || fail "Bundled ABL file is corrupt"

for s in b a; do
  P=/dev/block/by-name/abl_$s
  if [ "$(head -c $SIZE $P | sha256sum | cut -d' ' -f1)" = $SHA ]; then
    echo "- abl_$s already ROCKNIX, skipping"; continue
  fi
  echo "- Flashing abl_$s ..."
  dd if="$ELF" of=$P bs=4096 conv=fsync 2>/dev/null || fail "dd to abl_$s failed"
  [ "$(head -c $SIZE $P | sha256sum | cut -d' ' -f1)" = $SHA ] || fail "abl_$s readback mismatch"
  echo "- abl_$s VERIFIED"
done
sync
echo "- Done. Safe to reboot; hold VOL- for the boot menu."
