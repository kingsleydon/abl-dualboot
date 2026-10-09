# Contributing

The most helpful contribution is a **[device report](../../issues/new?template=device.yml)**: which handheld,
chip and setup you tried, and what worked.

## Building

| Part | Command |
|---|---|
| Android app | `cd android && ./gradlew assembleDebug` (JDK 17+) |
| Decky plugin | `cd decky && pnpm i && pnpm build` (pnpm 9) |

The ROCKNIX ABL binaries are not in this repo or the app. The pinned release is in `abl.properties`; the app
downloads it on demand and checks the pinned SHA-256.

## Changing the switch logic

`shared/dualboot.sh` (Android) and `linux/dualboot.py` (Linux) must behave identically and mirror the
ROCKNIX ABL menu. Test them against copies of a real `devinfo`, never the live partition first:

```sh
DEVINFO=./devinfo-copy.img DUALBOOT_TMP=/tmp sh shared/dualboot.sh linux
DEVINFO=./devinfo-copy.img python3 linux/dualboot.py linux --no-reboot
```

## Releasing

1. Add a `## vX.Y.Z` section to `CHANGELOG.md` (it becomes the release notes).
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, where versionCode is X*10000 + Y*100 + Z.
3. Push a `vX.Y.Z` tag; CI builds, attests and publishes everything.
