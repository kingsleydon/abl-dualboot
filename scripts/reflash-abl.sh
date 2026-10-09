#!/bin/bash
# Flash ROCKNIX ABL (SM8550) to both abl slots on an AYN Odin 2 Portal over adb (needs KernelSU/root).
# Re-run after every LineageOS update - LineageOS OTAs restore the stock ABL.
# Back up first: adb exec-out "su -c 'cat /dev/block/by-name/abl_a'" > abl_a.img (same for abl_b).
set -e
cd "$(dirname "$0")"
ELF=rocknix-abl-v1.2/abl_signed-SM8550.elf
if [ ! -f "$ELF" ]; then
  echo "Downloading ROCKNIX ABL v1.2..."
  curl -fsSL https://github.com/ROCKNIX/abl/releases/download/v1.2/rocknix-abl-v1.2.tar.gz | tar -xz
fi
SHA=8c27a70961476b327071ada2fd9b9c55dd2dcdc913eff3d33f2576133d7e398c
SIZE=258048

[ "$(adb shell getprop ro.product.device)" = odin2portal ] || { echo "Not an Odin 2 Portal - aborting"; exit 1; }
[ "$(adb shell getprop ro.board.platform)" = kalama ] || { echo "Not SM8550 (kalama) - aborting"; exit 1; }
[ "$(shasum -a 256 "$ELF" | awk '{print $1}')" = $SHA ] || { echo "Local ABL checksum mismatch - aborting"; exit 1; }
adb shell "su -c id" | grep -q 'uid=0' || { echo "No root (grant shell in KernelSU) - aborting"; exit 1; }

adb push "$ELF" /data/local/tmp/rocknix-abl.elf >/dev/null
[ "$(adb shell sha256sum /data/local/tmp/rocknix-abl.elf | awk '{print $1}')" = $SHA ] || { echo "Pushed file corrupt - aborting"; exit 1; }
echo "Checks OK. Flashing..."

for s in b a; do
  adb shell "su -c 'dd if=/data/local/tmp/rocknix-abl.elf of=/dev/block/by-name/abl_$s bs=4096 conv=fsync'"
  rb=$(adb shell "su -c 'head -c $SIZE /dev/block/by-name/abl_$s | sha256sum'" | awk '{print $1}')
  if [ "$rb" = $SHA ]; then echo "abl_$s VERIFIED"; else echo "abl_$s READBACK MISMATCH - stop, do not reboot, restore from partitions/abl_$s.img"; exit 1; fi
done
adb shell rm /data/local/tmp/rocknix-abl.elf
echo "Done. Reboot holding VOL- to get the boot menu."
