import asyncio
import os
import subprocess
import sys

SCRIPT = os.path.join(os.path.dirname(os.path.realpath(__file__)), "bootswitch.py")


def _run(*args):
    return subprocess.run([sys.executable, SCRIPT, *args], capture_output=True, text=True)


def _status():
    r = _run("status")
    return r.stdout.strip() if r.returncode == 0 else "unknown"


def _reboot_to(target):
    r = _run(target, "--no-reboot")
    if r.returncode != 0:
        return {"ok": False, "error": (r.stderr or r.stdout).strip().removeprefix("! ")}
    subprocess.Popen(["systemctl", "reboot"])
    return {"ok": True}


class Plugin:
    async def get_target(self):
        return await asyncio.to_thread(_status)

    async def reboot_to(self, target):
        if target not in ("android", "linux"):
            return {"ok": False, "error": "invalid target"}
        return await asyncio.to_thread(_reboot_to, target)

    async def _main(self):
        pass

    async def _unload(self):
        pass
