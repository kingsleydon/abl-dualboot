package io.github.kingsleydon.abldualboot

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates from this repo's GitHub releases through Android's PackageInstaller (self-update). A download is
 * installed only if its SHA-256 matches the digest GitHub reports for the release asset (releases are
 * immutable), and Android rejects an APK not signed with the same key as the installed app.
 */
object Updater {
    private const val REPO = "kingsleydon/abl-dualboot"
    private const val APK = "ABL-Dual-Boot.apk"

    /**
     * The built-in updater only runs for APKs installed by hand (file manager, adb) or by itself.
     * When an app store installed us (F-Droid, IzzyOnDroid clients, Obtainium, ...), that store owns updates.
     */
    fun enabled(context: Context): Boolean {
        val installer = context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        return installer == null || installer == context.packageName || installer == "com.android.shell" ||
            installer.endsWith(".packageinstaller")
    }

    data class Release(val version: String, val code: Int, val notes: String, val apkUrl: String, val sha256: String)

    /** v1.2.3 -> 10203, matching the versionCode CI gives release builds. */
    private fun versionCode(tag: String): Int? {
        val parts = tag.removePrefix("v").split(".").map { it.toIntOrNull() ?: return null }
        return if (parts.size == 3) parts[0] * 10000 + parts[1] * 100 + parts[2] else null
    }

    private fun get(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        setRequestProperty("User-Agent", "abl-dualboot/${BuildConfig.VERSION_NAME}")
        connectTimeout = 15_000
        readTimeout = 30_000
    }

    /** The latest release if it is newer than this build, otherwise null. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val json = JSONObject(get("https://api.github.com/repos/$REPO/releases/latest").inputStream.bufferedReader().readText())
        val tag = json.getString("tag_name")
        val code = versionCode(tag) ?: return@withContext null
        if (code <= BuildConfig.VERSION_CODE) return@withContext null
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name") == APK }
            ?: return@withContext null
        val digest = apk.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 } ?: return@withContext null
        Release(tag.removePrefix("v"), code, json.optString("body").trim(), apk.getString("browser_download_url"), digest)
    }

    /**
     * Downloads and verifies the release, then hands it to PackageInstaller. Returns an error message, or null
     * once the session is committed; the outcome arrives in [InstallResultReceiver].
     */
    suspend fun install(context: Context, release: Release): String? = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "update.apk")
        try {
            get(release.apkUrl).inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            if (sha != release.sha256) return@withContext "Download did not match the release checksum - not installed"

            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                setSize(file.length())
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val callback = PendingIntent.getBroadcast(
                    context, id, Intent(context, InstallResultReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            }
            null
        } catch (e: Exception) {
            "Update failed: ${e.message}"
        } finally {
            file.delete()
        }
    }
}
