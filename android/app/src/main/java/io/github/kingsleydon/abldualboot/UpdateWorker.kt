package io.github.kingsleydon.abldualboot

import android.content.Context
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
        if (!Updater.enabled(applicationContext)) return Result.success()
        val release = try { Updater.check() } catch (e: Exception) { return Result.retry() } ?: return Result.success()
        if (Prefs.autoUpdate(applicationContext) && applicationContext.packageManager.canRequestPackageInstalls()) {
            Updater.install(applicationContext, release)
        } else {
            Notifications.show(
                applicationContext, Notifications.Channel.UPDATES,
                "ABL Dual Boot ${release.version} is available", "Tap to update",
            )
        }
        return Result.success()
    }

    companion object {
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
