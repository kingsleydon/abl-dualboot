#!/system/bin/sh
# Re-flash the ROCKNIX ABL for this device's SoC (e.g. after an Android OTA restored the stock one).
MODDIR=${0%/*}
SOC=$(sh "$MODDIR/abl.sh" status | sed -n 's/.*soc=\([^ ]*\).*/\1/p')
ELF=$MODDIR/abl/abl_signed-$SOC.elf
[ -f "$ELF" ] || { echo "! No ROCKNIX ABL for this SoC ($SOC)"; exit 1; }
sh "$MODDIR/abl.sh" status
sh "$MODDIR/abl.sh" flash "$ELF" "$(cut -d' ' -f1 "$ELF.sha256")" /sdcard/BootSwitch/backup || exit 1
echo "- Safe to reboot. Hold VOL- at power-on for the boot menu."
