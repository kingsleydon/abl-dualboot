# Contributing

The most helpful contribution is a **[device report](../../issues/new?template=device.yml)**: which handheld,
chip and setup you tried, and what worked.

## Building

| Part | Command |
|---|---|
| Android app | `cd android && ./gradlew assembleDebug` (JDK 17+) |
| Decky plugin | `cd decky && pnpm i && pnpm build` (pnpm 9) |
| Modules | packaged by CI from `modules/` and `shared/` |

The ROCKNIX ABL binaries are not in this repo. CI downloads the official release and checks a pinned SHA-256;
for local builds put them in `android/app/abl-assets/abl/`.

## Changing the switch logic

`shared/dualboot.sh` (Android) and `linux/dualboot.py` (Linux) must behave identically and mirror the
ROCKNIX ABL menu. Test them against copies of a real `devinfo`, never the live partition first:

```sh
DEVINFO=./devinfo-copy.img DUALBOOT_TMP=/tmp sh shared/dualboot.sh linux
DEVINFO=./devinfo-copy.img python3 linux/dualboot.py linux --no-reboot
```

Releases are made by pushing a `v*` tag; CI builds, attests and publishes everything.
