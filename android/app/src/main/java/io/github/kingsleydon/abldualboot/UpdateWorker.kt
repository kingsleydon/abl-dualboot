package io.github.kingsleydon.abldualboot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.app.Notification
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Daily update check: installs automatically if enabled, otherwise posts a notification. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val release = try { Updater.check() } catch (e: Exception) { return Result.retry() } ?: return Result.success()
        if (Settings.autoUpdate(applicationContext) && applicationContext.packageManager.canRequestPackageInstalls()) {
            Updater.install(applicationContext, release)
        } else {
            notify(applicationContext, release)
        }
        return Result.success()
    }

    private fun notify(context: Context, release: Updater.Release) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(2, Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("ABL Dual Boot ${release.version} is available")
            .setContentText("Tap to update")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }

    companion object {
        private const val CHANNEL = "updates"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "update-check",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}
