package io.github.kingsleydon.abldualboot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Posts a notification that opens the app when tapped. */
object Notifications {
    enum class Channel(val id: String, val title: String, val importance: Int, val notificationId: Int) {
        BOOTLOADER("abl", "Bootloader", NotificationManager.IMPORTANCE_DEFAULT, 1),
        UPDATES("updates", "Updates", NotificationManager.IMPORTANCE_LOW, 2),
    }

    fun show(context: Context, channel: Channel, title: String, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(channel.id, channel.title, channel.importance))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            channel.notificationId,
            Notification.Builder(context, channel.id)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}
