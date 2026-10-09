package io.github.kingsleydon.bootswitch

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class BootTarget { ANDROID, LINUX, UNKNOWN }

sealed interface Status {
    data object Loading : Status
    data class Ready(val target: BootTarget) : Status
    data class Error(val message: String) : Status
}

/** Runs the bundled bootswitch.sh (shared with the KernelSU module) through su. */
object BootSwitch {
    private const val SCRIPT = "bootswitch.sh"

    suspend fun status(context: Context): Status = withContext(Dispatchers.IO) {
        val (code, out) = run(context, "status")
        when {
            code != 0 -> Status.Error(out.ifBlank { NO_ROOT })
            out.trim() == "android" -> Status.Ready(BootTarget.ANDROID)
            out.trim() == "linux" -> Status.Ready(BootTarget.LINUX)
            else -> Status.Ready(BootTarget.UNKNOWN)
        }
    }

    /** Sets the default boot target to Linux and reboots. Returns an error message on failure. */
    suspend fun rebootToLinux(context: Context): String? = withContext(Dispatchers.IO) {
        val (code, out) = run(context, "linux")
        if (code != 0) return@withContext out.ifBlank { NO_ROOT }
        su("reboot")
        null
    }

    private fun run(context: Context, arg: String): Pair<Int, String> {
        val script = File(context.filesDir, SCRIPT)
        context.assets.open(SCRIPT).use { input -> script.outputStream().use { input.copyTo(it) } }
        val tmp = context.cacheDir.absolutePath
        return su("BOOTSWITCH_TMP='$tmp' sh '${script.absolutePath}' $arg")
    }

    private fun su(command: String): Pair<Int, String> = try {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor() to output.trim().removePrefix("! ")
    } catch (e: Exception) {
        1 to NO_ROOT
    }

    const val NO_ROOT = "Root access denied. Allow Boot Switch in KernelSU → Superuser."
}
