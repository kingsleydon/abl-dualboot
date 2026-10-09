package io.github.kingsleydon.abldualboot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** After boot, notices when a system update replaced the ROCKNIX ABL and offers a one-tap restore. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (Root.available() && Device.ablStatus(context).replacedByUpdate) notify(context)
            } finally {
                pending.finish()
            }
        }
    }

    private fun notify(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bootloader", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(1, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Linux boot menu was removed")
            .setContentText("A system update restored the stock bootloader. Tap to restore it.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }

    companion object { const val CHANNEL = "abl" }
}
