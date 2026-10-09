package io.github.kingsleydon.bootswitch

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class BootTarget { ANDROID, LINUX, UNKNOWN }
enum class BootSource { SD, INTERNAL, OTHER }
enum class AblKind { ROCKNIX, STOCK, UNKNOWN }

data class AblStatus(val soc: String, val currentSlot: String, val slotA: AblKind, val slotB: AblKind) {
    val supported get() = soc != "unknown"
    val installed get() = slotA == AblKind.ROCKNIX && slotB == AblKind.ROCKNIX
    /** Current slot runs the stock ABL while the other one still has ROCKNIX: a system update replaced it. */
    val replacedByUpdate get() = !installed && (slotA == AblKind.ROCKNIX || slotB == AblKind.ROCKNIX)
}

sealed interface DeviceState {
    data object Loading : DeviceState
    data class NoRoot(val manager: Root.Manager?) : DeviceState
    data class Ready(val target: BootTarget, val source: BootSource, val abl: AblStatus, val switchError: String?) : DeviceState
}

/** Talks to the bundled shell scripts (shared with the Magisk/KernelSU modules). */
object Device {
    const val BACKUP_DIR = "/sdcard/BootSwitch/backup"

    suspend fun load(context: Context): DeviceState = withContext(Dispatchers.IO) {
        if (!Root.available()) return@withContext DeviceState.NoRoot(Root.detectManager(context))
        val abl = ablStatus(context)
        val switch = Root.run("BOOTSWITCH_TMP='${context.cacheDir}' sh '${Root.asset(context, "bootswitch.sh")}' status")
        val words = switch.output.split(" ")
        DeviceState.Ready(
            target = when (words.firstOrNull()) { "android" -> BootTarget.ANDROID; "linux" -> BootTarget.LINUX; else -> BootTarget.UNKNOWN },
            source = when (words.getOrNull(1)) { "sd" -> BootSource.SD; "internal" -> BootSource.INTERNAL; else -> BootSource.OTHER },
            abl = abl,
            switchError = if (switch.ok) null else switch.output.removePrefix("! "),
        )
    }

    fun ablStatus(context: Context): AblStatus {
        val out = Root.run("sh '${Root.asset(context, "abl.sh")}' status").output
        val f = out.split(" ").mapNotNull { it.split("=").takeIf { kv -> kv.size == 2 }?.let { kv -> kv[0] to kv[1] } }.toMap()
        fun kind(v: String?) = when (v) { "rocknix" -> AblKind.ROCKNIX; "stock" -> AblKind.STOCK; else -> AblKind.UNKNOWN }
        return AblStatus(f["soc"] ?: "unknown", f["slot"] ?: "?", kind(f["a"]), kind(f["b"]))
    }

    /** Sets Linux as default boot target and reboots. Returns an error message, or null on success. */
    suspend fun rebootToLinux(context: Context, source: BootSource? = null): String? = withContext(Dispatchers.IO) {
        val arg = when (source) { BootSource.SD -> "sd"; BootSource.INTERNAL -> "internal"; else -> "" }
        val r = Root.run("BOOTSWITCH_TMP='${context.cacheDir}' sh '${Root.asset(context, "bootswitch.sh")}' linux $arg")
        if (!r.ok) return@withContext r.output.removePrefix("! ").ifBlank { "Root access denied" }
        Root.run("reboot")
        null
    }

    /** Flashes the bundled ROCKNIX ABL for this SoC to both slots. Returns the script log; ok=false on failure. */
    suspend fun installAbl(context: Context, soc: String): Root.Result = withContext(Dispatchers.IO) {
        val name = "abl/abl_signed-$soc.elf"
        val elf = try { Root.asset(context, name) } catch (e: Exception) {
            return@withContext Root.Result(false, "No ROCKNIX ABL bundled for $soc")
        }
        val sha = context.assets.open("$name.sha256").bufferedReader().readText().substringBefore(" ").trim()
        Root.run("sh '${Root.asset(context, "abl.sh")}' flash '$elf' $sha $BACKUP_DIR")
    }
}
