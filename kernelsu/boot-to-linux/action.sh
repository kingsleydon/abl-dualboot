#!/system/bin/sh
sh "${0%/*}/bootswitch.sh" linux || exit 1
echo "- Default boot target: Linux. Rebooting in 3 seconds..."
sleep 3
reboot
