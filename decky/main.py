import asyncio
import importlib.util
import os
import subprocess

import decky

# Decky runs plugins inside its own bundled Python, so import the switch code instead of spawning sys.executable.
_spec = importlib.util.spec_from_file_location("bootswitch", os.path.join(decky.DECKY_PLUGIN_DIR, "bootswitch.py"))
bootswitch = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(bootswitch)


class Plugin:
    async def get_status(self) -> str:
        try:
            return await asyncio.to_thread(bootswitch.status)
        except Exception as e:
            decky.logger.warning("status failed: %s", e)
            return "unknown"

    async def reboot_to_android(self) -> dict:
        try:
            result = await asyncio.to_thread(bootswitch.switch, "android")
        except Exception as e:
            decky.logger.error("switch failed: %s", e)
            return {"ok": False, "error": str(e)}
        decky.logger.info("default boot set to %s, rebooting", result)
        subprocess.Popen(["systemctl", "reboot"])
        return {"ok": True}

    async def _main(self):
        decky.logger.info("Boot Switch loaded")

    async def _unload(self):
        pass

    async def _uninstall(self):
        pass
