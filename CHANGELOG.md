# Changelog

## Unreleased
- Detect OpenABL before ROCKNIX, including OpenABL releases and custom builds in the Android app and Linux helper.
- Add experimental switching for OpenABL builds after v1.2 that use the version 1 settings store in `misc` (main since 71c0040). Not device-verified.
- Refuse switching for released OpenABL v1.0–v1.2 or an absent/invalid store; never create or repair a store. Installation and OTA restoration remain blocked for all OpenABL builds.
- Require positive unreleased OpenABL identification in both slots for misc switching; refuse mixed slots and never authorize from leftover EFI variables or stores.
- Bind Android shell mutations to Kotlin's per-slot classification and inspected prefix length/SHA-256. Require two ROCKNIX slots for devinfo writes, and ROCKNIX or stock for flashing.
- Preflight block-device cache invalidation for misc writes, and invalidate after fsync before normal and rollback readback; refuse unavailable or denied flushes without cached fallback.
- Preserve unrelated settings, reseal the model copy and CRC-32, and verify bounded writes with rollback. Share golden settings fixtures across Android and Python, including invalid stores and I/O failures.
- Cover detection and refusal with shared Android/Python fixtures and shell tests.

## v2.2.1
- Internal cleanup, no behavior change: shared helpers, no deprecated APIs, no compiler warnings.

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
