# ROCKNIX Boot Switch

One-tap switching between **Android** and **Linux** on handhelds that use the
[ROCKNIX ABL](https://github.com/ROCKNIX/abl) bootloader — no more holding VOL- at power-on.

Works with any Linux that boots through the ROCKNIX ABL (Armada, ROCKNIX, Batocera, Knulli, …).

> [!WARNING]
> This writes to the `devinfo` partition, which also stores bootloader state.
> It is **only tested on an AYN Odin 2 Portal (SM8550) with ROCKNIX ABL v1.2**, LineageOS 23.2 and Armada.
> Every tool refuses to write unless the `devinfo` layout matches exactly, changes only two bytes,
> verifies the result and restores the original on any mismatch. The VOL- boot menu always remains as a fallback.
> Use at your own risk.

## How it works

The ROCKNIX ABL stores its settings as named entries in `devinfo`. Choosing a boot mode in its menu changes
exactly two bytes:

| Default boot | `BootMode` @ `0xA92` | `BootSourceMode` @ `0xAF0` |
|---|---|---|
| Android | `01` | `00` |
| Linux (SD card) | `00` | `03` |

These tools write the same two bytes the menu does, then reboot.

## Tools

| Where | Tool | What it does |
|---|---|---|
| Android | **Boot Switch app** (`BootSwitch.apk`) | Material 3 app + **Quick Settings tile** → reboot to Linux. Needs root (KernelSU/Magisk). |
| Android | **Boot to Linux** KernelSU module | Same switch from KernelSU → Modules → Action. |
| Android | **ROCKNIX ABL Reflash (SM8550)** KernelSU module | Android OTAs (e.g. LineageOS) restore the stock ABL. Tap Action after updating, **before rebooting**. Odin 2 Portal only. |
| Linux (Decky) | **Boot Switch** Decky plugin | Quick Access menu → Reboot to Android. |
| Linux | `bootswitch.py` + desktop launcher | Same, for any Linux distro (`sudo bootswitch.py android`). |
| PC | `scripts/reflash-abl.sh` | Flash the ROCKNIX ABL over adb (root). Downloads the official release and verifies its checksum. |

Download everything from **[Releases](../../releases)**.

## Install

**Android app** — install `BootSwitch.apk`, open it, allow it in *KernelSU → Superuser*, then tap
*Add Quick Settings tile*.

**KernelSU modules** — *KernelSU → Modules → Install from storage* → pick the zip → reboot.

**Decky plugin** (Linux side, as root):
```sh
sudo install -d -m 0755 /etc/bootswitch
sudo install -m 0755 bootswitch.py /etc/bootswitch/bootswitch.py
sudo unzip boot-switch-decky.zip -d ~/homebrew/plugins/
sudo systemctl restart plugin_loader
```

**Linux desktop launcher** — also install the sudoers rule so the launcher can run without a password:
```sh
sudo visudo -cf bootswitch.sudoers && sudo install -m 0440 bootswitch.sudoers /etc/sudoers.d/bootswitch
install -m 0755 reboot-to-android.desktop ~/.local/share/applications/
```
(The sudoers rule assumes the user is `armada`; edit it for other distros.)

## Before you start

- Back up `devinfo` and both `abl` slots to a PC:
  `adb exec-out "su -c 'cat /dev/block/by-name/devinfo'" > devinfo.img`
- Read the current state without writing anything: `su -c sh bootswitch.sh status`

## Build

GitHub Actions builds every artifact on push; tags `v*` publish a release.
The ROCKNIX ABL binary is **not** stored in this repo — CI downloads the official release and verifies its SHA-256.

## License

MIT. ROCKNIX ABL is © the ROCKNIX team and distributed under its own terms.
