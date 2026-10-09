import asyncio
import importlib.util
import os
import subprocess

import decky

# Decky runs plugins inside its own bundled Python, so import the switch code instead of spawning sys.executable.
_spec = importlib.util.spec_from_file_location("bootswitch", os.path.join(decky.DECKY_PLUGIN_DIR, "bootswitch.py"))
bootswitch = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(bootswitch)


def _info():
    mode, source = bootswitch.status().split(" ")
    return {
        "mode": mode,
        "source": source,
        "targets": [{"location": loc, "name": name} for loc, name in bootswitch.targets()],
    }


class Plugin:
    async def get_info(self) -> dict:
        try:
            return await asyncio.to_thread(_info)
        except Exception as e:
            decky.logger.warning("get_info failed: %s", e)
            return {"error": str(e)}

    async def restart_into(self, target: str, location: str = "") -> dict:
        try:
            result = await asyncio.to_thread(bootswitch.switch, target, location)
        except Exception as e:
            decky.logger.error("switch failed: %s", e)
            return {"ok": False, "error": str(e)}
        decky.logger.info("default boot set to %s, restarting", result)
        subprocess.Popen(["systemctl", "reboot"])
        return {"ok": True}

    async def _main(self):
        decky.logger.info("Boot Switch loaded")

    async def _unload(self):
        pass

    async def _uninstall(self):
        pass
