package io.github.kingsleydon.abldualboot

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class BootTileService : TileService() {
    override fun onStartListening() {
        val last = Prefs.lastTarget(this)
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = last?.title ?: getString(R.string.tile_label)
            subtitle = last?.where
            updateTile()
        }
    }

    override fun onClick() {
        val intent = Intent(this, ChooserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        unlockAndRun { startActivityAndCollapse(pending) }
    }
}
