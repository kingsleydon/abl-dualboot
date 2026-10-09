import asyncio
import subprocess

SCRIPT = "/etc/bootswitch/bootswitch.py"
DEVINFO = "/dev/disk/by-partlabel/devinfo"


def _read_target():
    with open(DEVINFO, "rb") as f:
        d = f.read(4096)
    if d[0xA5A:0xA62] != b"BootMode":
        return "unknown"
    return {(1, 0): "android", (0, 3): "linux"}.get((d[0xA92], d[0xAF0]), "unknown")


def _reboot_to(target):
    r = subprocess.run([SCRIPT, target, "--no-reboot"], capture_output=True, text=True)
    if r.returncode != 0:
        return {"ok": False, "error": (r.stderr or r.stdout).strip()}
    subprocess.Popen(["systemctl", "reboot"])
    return {"ok": True}


class Plugin:
    async def get_target(self):
        return await asyncio.to_thread(_read_target)

    async def reboot_to(self, target):
        if target not in ("android", "linux"):
            return {"ok": False, "error": "invalid target"}
        return await asyncio.to_thread(_reboot_to, target)

    async def _main(self):
        pass

    async def _unload(self):
        pass
