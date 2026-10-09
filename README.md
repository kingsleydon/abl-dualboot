<p align="center"><img src="docs/icon.png" width="96" alt=""></p>

# Boot Switch

One-tap switching between **Android** and **Linux** on handhelds that use the
[ROCKNIX ABL](https://github.com/ROCKNIX/abl) bootloader. No more holding VOL- at power-on.

Works with any Linux that boots through the ROCKNIX ABL (Armada, ROCKNIX, Batocera, Knulli, …),
from an **SD card or internal storage**, and with every common root solution: **Magisk, KernelSU,
KernelSU Next, SukiSU, APatch**.

## Quick start

**1. Android:** install [`BootSwitch.apk`](../../releases/latest/download/BootSwitch.apk) and open it.
- Allow root when asked (KernelSU / APatch: enable *Boot Switch* in the manager's *Superuser* tab).
- No ROCKNIX ABL yet? Tap **Install ROCKNIX ABL**. Your stock bootloader is backed up first.
- Tap **Add Quick Settings tile**.

**2. Linux:** run once in a terminal (or over SSH):
```sh
curl -fsSL https://github.com/kingsleydon/rocknix-bootswitch/releases/latest/download/install-linux.sh | sudo bash
```

**3. Switch:**

| From | Tap | Boots |
|---|---|---|
| Android | Quick Settings → **Boot to Linux**, or the app | Linux |
| Linux (Steam / Game Mode) | **…** → **Boot Switch** → **Reboot to Android** | Android |
| Linux (desktop) | App menu → **Reboot to Android** | Android |

**After an Android system update** the update puts back the stock bootloader and the Linux boot menu
disappears. Boot Switch shows a notification. Tap it, then **Restore**.

## Other downloads

| File | For |
|---|---|
| `boot-to-linux-module.zip` | The switch as a Magisk / KernelSU / APatch module (Modules → Action) |
| `abl-reflash-module.zip` | ROCKNIX ABL restore as a module (Modules → Action, after updates) |
| `boot-switch-decky.zip` | Decky plugin only (the installer already adds it) |
| `bootswitch-linux.zip` | `bootswitch.py` + desktop launcher, for manual installs |

Modules update themselves from the manager app (`updateJson`).

> [!WARNING]
> Boot Switch writes to the `devinfo` partition (which also stores bootloader state), and installing the
> ROCKNIX ABL replaces your bootloader. It is tested only on an **AYN Odin 2 Portal (SM8550)** with ROCKNIX ABL v1.2,
> LineageOS 23.2 and Armada. Every tool refuses to write unless the layout matches exactly, verifies every write and
> restores the original on mismatch. The VOL- boot menu always remains as a fallback. Use at your own risk.

## How it works

The ROCKNIX ABL keeps its settings as named entries in `devinfo`. The values below come from the ABL v1.2
binary (LinuxLoader), whose menu code was disassembled to confirm them:

| Setting | Offset | Values |
|---|---|---|
| `BootMode` | `0xA92` | `0` Linux · `1` Android |
| `BootSourceMode` | `0xAF0` | `0` Internal · `1` Auto · `2` USB · `3` SDcard |

Boot Switch does exactly what the menu's **Switch boot mode** does:

- **Linux → Android:** `BootMode=1`, `BootSourceMode` reset to `0`.
- **Android → Linux:** `BootMode=0`. If `BootSourceMode` is `0` and no Linux is installed internally, `BootSourceMode=3` (SD card).
  "Installed internally" means partitions follow `userdata` on the same disk, which is how Armada/ROCKNIX install to internal storage.

So Linux on an **SD card or internal storage** both work without any setting. ROCKNIX ABL builds are recognised by
their `qtestsign` signing certificate (the stock ABL is Qualcomm-signed), so a newer ROCKNIX ABL is never mistaken for stock.

## Build

- `android/`: Kotlin + Jetpack Compose (Material 3), root via [libsu](https://github.com/topjohnwu/libsu). `./gradlew assembleRelease`
- `decky/`: built from [decky-plugin-template](https://github.com/SteamDeckHomebrew/decky-plugin-template). `pnpm i && pnpm build`
- `modules/`: [Magisk module format](https://topjohnwu.github.io/Magisk/guides.html), also understood by KernelSU and APatch
- `shared/`: the device scripts used by both the app and the modules

GitHub Actions builds everything; pushing a `v*` tag publishes a release. The ROCKNIX ABL binaries are not stored
here. CI downloads the official release and checks it against a pinned SHA-256.

## License

MIT. ROCKNIX ABL is © the ROCKNIX team under its own terms. The Decky plugin is based on decky-plugin-template (BSD-3-Clause).
