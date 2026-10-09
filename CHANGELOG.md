# Changelog

## v2.2.0
- Removed the Magisk/KernelSU/APatch modules; the app does both jobs (restart into Linux, restore the bootloader). The project no longer ships any ROCKNIX binary.

## v2.1.0
- The app no longer bundles the ROCKNIX ABL. Install/Restore downloads the official release from github.com/ROCKNIX/abl and verifies it against a pinned checksum.

## v2.0.3
- The built-in updater turns off when an app store (F-Droid, IzzyOnDroid, Obtainium) installed the app; the store handles updates.
- Fastlane metadata for F-Droid-compatible repositories.

## v2.0.2
- Decky plugin reports the release version.

## v2.0.1
- Removed compatibility code for the old name.

## v2.0.0
- Renamed to **ABL Dual Boot**.
- App updates itself from GitHub releases through Android's PackageInstaller, verified against the release checksum; optional automatic updates.
- Release notes come from this changelog; a weekly workflow proposes new ROCKNIX ABL releases.

## v1.2.0
- Pick any Linux system to restart into: internal storage, SD card or USB, shown by name (Armada, ROCKNIX, Batocera, ...).
- App redesigned around "Restart into"; bootloader card only appears when something needs fixing.
- Quick Settings tile shows the last system used and opens a Material 3 chooser.
- Decky plugin: restart into Android or another Linux system.

## v1.1.1
- Decky plugin: fix "Default boot: Unknown" and Reboot to Android. Decky runs plugins in its own bundled Python, so the plugin now imports the switch code instead of launching `sys.executable`.

## v1.1.0
- All-in-one Android app: boot switch, Quick Settings tile, ROCKNIX ABL install/restore, notification when a system update removes the Linux boot menu.
- Works with Magisk, KernelSU, KernelSU Next, SukiSU and APatch (root via libsu).
- Linux on internal storage or SD card.
- Magisk/KernelSU/APatch modules with in-app updates (updateJson).
- Decky plugin rebuilt on the official template; one-line Linux installer.
- New icon.

## v1.0.0
- First release.
