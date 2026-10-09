#!/bin/bash
# ABL Dual Boot installer for the Linux side (Armada, ROCKNIX, Batocera, ...).
#   curl -fsSL https://github.com/kingsleydon/abl-dualboot/releases/latest/download/install-linux.sh | sudo bash
# Installs dualboot.py, a "Reboot to Android" launcher, and the Decky plugin when Decky Loader is present.
set -euo pipefail
REPO=kingsleydon/abl-dualboot
BASE=https://github.com/$REPO/releases/latest/download

[ "$(id -u)" = 0 ] || { echo "Run with sudo (or as root)."; exit 1; }
command -v python3 >/dev/null || { echo "python3 is required."; exit 1; }
[ -e /dev/disk/by-partlabel/devinfo ] || { echo "No devinfo partition found - is this a ROCKNIX ABL device?"; exit 1; }

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
echo "- Downloading latest release"
curl -fsSL "$BASE/abl-dualboot-linux.zip" -o "$TMP/linux.zip"
python3 -m zipfile -e "$TMP/linux.zip" "$TMP/linux"

echo "- Checking devinfo (read-only)"
python3 "$TMP/linux/dualboot.py" status

# Remove the previous "Boot Switch" install (renamed to ABL Dual Boot in v2.0.0)
rm -rf /etc/bootswitch /etc/sudoers.d/bootswitch
if [ -n "${SUDO_USER:-}" ]; then
  OLD_HOME=$(getent passwd "$SUDO_USER" | cut -d: -f6)
  rm -rf "$OLD_HOME/homebrew/plugins/boot-switch"
fi

DIR=/etc/abl-dualboot
touch /etc/.abl-dualboot-test 2>/dev/null && rm -f /etc/.abl-dualboot-test || DIR=/storage/.config/abl-dualboot
install -d -m 0755 "$DIR"
install -m 0755 "$TMP/linux/dualboot.py" "$DIR/dualboot.py"
echo "- Installed $DIR/dualboot.py"

USER_NAME=${SUDO_USER:-}
if [ -n "$USER_NAME" ] && [ "$USER_NAME" != root ]; then
  USER_HOME=$(getent passwd "$USER_NAME" | cut -d: -f6)
  if [ -d /etc/sudoers.d ]; then
    RULE="$USER_NAME ALL=(root) NOPASSWD: $DIR/dualboot.py android, $DIR/dualboot.py linux"
    echo "$RULE" > "$TMP/sudoers"
    visudo -cf "$TMP/sudoers" >/dev/null && install -m 0440 "$TMP/sudoers" /etc/sudoers.d/abl-dualboot
    echo "- Allowed $USER_NAME to switch without a password"
  fi
  APPS="$USER_HOME/.local/share/applications"
  install -d -o "$USER_NAME" "$APPS"
  sed "s#/etc/abl-dualboot/dualboot.py#$DIR/dualboot.py#" "$TMP/linux/reboot-to-android.desktop" > "$APPS/reboot-to-android.desktop"
  chown "$USER_NAME" "$APPS/reboot-to-android.desktop"; chmod 0755 "$APPS/reboot-to-android.desktop"
  [ -f "$USER_HOME/Desktop/reboot-to-android.desktop" ] && install -o "$USER_NAME" -m 0755 "$APPS/reboot-to-android.desktop" "$USER_HOME/Desktop/"
  echo "- Added 'Reboot to Android' to the app menu"
  PLUGINS="$USER_HOME/homebrew/plugins"
else
  PLUGINS="${HOME}/homebrew/plugins"
fi

if [ -d "$PLUGINS" ]; then
  curl -fsSL "$BASE/abl-dualboot-decky.zip" -o "$TMP/decky.zip"
  rm -rf "$PLUGINS/abl-dualboot"
  python3 -m zipfile -e "$TMP/decky.zip" "$PLUGINS"
  chown -R root:root "$PLUGINS/abl-dualboot"
  systemctl restart plugin_loader 2>/dev/null || true
  echo "- Installed Decky plugin: Quick Access (...) -> ABL Dual Boot"
fi
echo "Done."
