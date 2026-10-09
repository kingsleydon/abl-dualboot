import asyncio
import os
import subprocess
import sys

import decky

SCRIPT = os.path.join(decky.DECKY_PLUGIN_DIR, "bootswitch.py")


def _run(*args):
    return subprocess.run([sys.executable, SCRIPT, *args], capture_output=True, text=True)


class Plugin:
    async def get_status(self) -> str:
        r = await asyncio.to_thread(_run, "status")
        if r.returncode != 0:
            decky.logger.warning("status failed: %s", r.stderr.strip())
            return "unknown"
        return r.stdout.strip()

    async def reboot_to_android(self) -> dict:
        r = await asyncio.to_thread(_run, "android", "--no-reboot")
        if r.returncode != 0:
            error = (r.stderr or r.stdout).strip().removeprefix("! ")
            decky.logger.error("switch failed: %s", error)
            return {"ok": False, "error": error}
        decky.logger.info("default boot set to android, rebooting")
        subprocess.Popen(["systemctl", "reboot"])
        return {"ok": True}

    async def _main(self):
        decky.logger.info("Boot Switch loaded")

    async def _unload(self):
        pass

    async def _uninstall(self):
        pass
