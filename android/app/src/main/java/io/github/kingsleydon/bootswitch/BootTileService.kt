package io.github.kingsleydon.bootswitch

import android.app.AlertDialog
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BootTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.tile_label)
            subtitle = null
            updateTile()
        }
    }

    override fun onClick() {
        if (isLocked) unlockAndRun(::confirm) else confirm()
    }

    private fun confirm() {
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Reboot to Linux?")
            .setMessage("Sets Linux (SD card) as the default boot target and restarts now.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Reboot") { _, _ ->
                qsTile?.apply { state = Tile.STATE_ACTIVE; subtitle = "Rebooting…"; updateTile() }
                scope.launch {
                    BootSwitch.rebootToLinux(this@BootTileService)?.let { error ->
                        Toast.makeText(this@BootTileService, error, Toast.LENGTH_LONG).show()
                        onStartListening()
                    }
                }
            }
            .create()
        showDialog(dialog)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
