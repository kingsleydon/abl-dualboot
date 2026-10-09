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
import kotlinx.coroutines.withContext

class BootTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        val last = Settings.lastTarget(this)
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = last?.title ?: getString(R.string.tile_label)
            subtitle = last?.where
            updateTile()
        }
    }

    override fun onClick() {
        if (isLocked) unlockAndRun(::choose) else choose()
    }

    private fun choose() {
        scope.launch {
            val targets = withContext(Dispatchers.IO) { if (Root.available()) Device.targets(this@BootTileService) else emptyList() }
            if (targets.isEmpty()) {
                Toast.makeText(this@BootTileService, Root.NO_ROOT, Toast.LENGTH_LONG).show()
                return@launch
            }
            val last = Settings.lastTarget(this@BootTileService)
            var selected = targets.indexOfFirst { it.location == last?.location }.takeIf { it >= 0 } ?: 0
            val dialog = AlertDialog.Builder(this@BootTileService, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.choose_title)
                .setSingleChoiceItems(targets.map { "${it.title} · ${it.where}" }.toTypedArray(), selected) { _, i -> selected = i }
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.restart) { _, _ -> restart(targets[selected]) }
                .create()
            showDialog(dialog)
        }
    }

    private fun restart(target: Target) {
        qsTile?.apply { state = Tile.STATE_ACTIVE; subtitle = getString(R.string.restarting); updateTile() }
        scope.launch {
            Device.reboot(this@BootTileService, target)?.let { error ->
                Toast.makeText(this@BootTileService, error, Toast.LENGTH_LONG).show()
                onStartListening()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
