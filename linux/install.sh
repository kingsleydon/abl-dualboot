#!/bin/bash
# Boot Switch installer for the Linux side (Armada, ROCKNIX, Batocera, ...).
#   curl -fsSL https://github.com/kingsleydon/rocknix-bootswitch/releases/latest/download/install-linux.sh | sudo bash
# Installs bootswitch.py, a "Reboot to Android" launcher, and the Decky plugin when Decky Loader is present.
set -euo pipefail
REPO=kingsleydon/rocknix-bootswitch
BASE=https://github.com/$REPO/releases/latest/download

[ "$(id -u)" = 0 ] || { echo "Run with sudo (or as root)."; exit 1; }
command -v python3 >/dev/null || { echo "python3 is required."; exit 1; }
[ -e /dev/disk/by-partlabel/devinfo ] || { echo "No devinfo partition found - is this a ROCKNIX ABL device?"; exit 1; }

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
echo "- Downloading latest release"
curl -fsSL "$BASE/bootswitch-linux.zip" -o "$TMP/linux.zip"
python3 -m zipfile -e "$TMP/linux.zip" "$TMP/linux"

echo "- Checking devinfo (read-only)"
python3 "$TMP/linux/bootswitch.py" status

DIR=/etc/bootswitch
touch /etc/.bootswitch-test 2>/dev/null && rm -f /etc/.bootswitch-test || DIR=/storage/.config/bootswitch
install -d -m 0755 "$DIR"
install -m 0755 "$TMP/linux/bootswitch.py" "$DIR/bootswitch.py"
echo "- Installed $DIR/bootswitch.py"

USER_NAME=${SUDO_USER:-}
if [ -n "$USER_NAME" ] && [ "$USER_NAME" != root ]; then
  USER_HOME=$(getent passwd "$USER_NAME" | cut -d: -f6)
  if [ -d /etc/sudoers.d ]; then
    RULE="$USER_NAME ALL=(root) NOPASSWD: $DIR/bootswitch.py android, $DIR/bootswitch.py linux"
    echo "$RULE" > "$TMP/sudoers"
    visudo -cf "$TMP/sudoers" >/dev/null && install -m 0440 "$TMP/sudoers" /etc/sudoers.d/bootswitch
    echo "- Allowed $USER_NAME to switch without a password"
  fi
  APPS="$USER_HOME/.local/share/applications"
  install -d -o "$USER_NAME" "$APPS"
  sed "s#/etc/bootswitch/bootswitch.py#$DIR/bootswitch.py#" "$TMP/linux/reboot-to-android.desktop" > "$APPS/reboot-to-android.desktop"
  chown "$USER_NAME" "$APPS/reboot-to-android.desktop"; chmod 0755 "$APPS/reboot-to-android.desktop"
  echo "- Added 'Reboot to Android' to the app menu"
  PLUGINS="$USER_HOME/homebrew/plugins"
else
  PLUGINS="${HOME}/homebrew/plugins"
fi

if [ -d "$PLUGINS" ]; then
  curl -fsSL "$BASE/boot-switch-decky.zip" -o "$TMP/decky.zip"
  rm -rf "$PLUGINS/boot-switch"
  python3 -m zipfile -e "$TMP/decky.zip" "$PLUGINS"
  chown -R root:root "$PLUGINS/boot-switch"
  systemctl restart plugin_loader 2>/dev/null || true
  echo "- Installed Decky plugin: Quick Access (...) -> Boot Switch"
fi
echo "Done."
