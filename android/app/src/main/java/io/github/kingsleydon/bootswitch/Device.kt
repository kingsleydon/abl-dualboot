package io.github.kingsleydon.bootswitch

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class BootTarget { ANDROID, LINUX, UNKNOWN }
enum class AblKind { ROCKNIX, STOCK, UNKNOWN }

/** A Linux system the ROCKNIX ABL can boot: location is internal, sd or usb; name is e.g. Armada. */
data class Target(val location: String, val name: String) {
    val where get() = when (location) { "internal" -> "Internal storage"; "sd" -> "SD card"; else -> "USB drive" }
    val title get() = if (location == "usb") "Linux on USB" else name
}

data class AblStatus(val soc: String, val currentSlot: String, val slotA: AblKind, val slotB: AblKind) {
    val supported get() = soc != "unknown"
    val installed get() = slotA == AblKind.ROCKNIX && slotB == AblKind.ROCKNIX
    /** One slot still has ROCKNIX but the other does not: a system update replaced it. */
    val replacedByUpdate get() = !installed && (slotA == AblKind.ROCKNIX || slotB == AblKind.ROCKNIX)
}

sealed interface DeviceState {
    data object Loading : DeviceState
    data class NoRoot(val manager: Root.Manager?) : DeviceState
    data class Ready(val defaultBoot: BootTarget, val targets: List<Target>, val abl: AblStatus, val error: String?) : DeviceState
}

/** Talks to the bundled shell scripts (shared with the Magisk/KernelSU modules). */
object Device {
    const val BACKUP_DIR = "/sdcard/BootSwitch/backup"

    private fun bootswitch(context: Context, args: String) =
        Root.run("BOOTSWITCH_TMP='${context.cacheDir}' sh '${Root.asset(context, "bootswitch.sh")}' $args")

    suspend fun load(context: Context): DeviceState = withContext(Dispatchers.IO) {
        if (!Root.available()) return@withContext DeviceState.NoRoot(Root.detectManager(context))
        val status = bootswitch(context, "status")
        DeviceState.Ready(
            defaultBoot = when (status.output.substringBefore(" ")) {
                "android" -> BootTarget.ANDROID; "linux" -> BootTarget.LINUX; else -> BootTarget.UNKNOWN
            },
            targets = targets(context),
            abl = ablStatus(context),
            error = if (status.ok) null else status.output.removePrefix("! "),
        )
    }

    fun targets(context: Context): List<Target> =
        bootswitch(context, "targets").output.lines().mapNotNull { line ->
            line.trim().split(" ", limit = 2).takeIf { it.size == 2 }?.let { Target(it[0], it[1]) }
        }

    fun ablStatus(context: Context): AblStatus {
        val out = Root.run("sh '${Root.asset(context, "abl.sh")}' status").output
        val f = out.split(" ").mapNotNull { it.split("=").takeIf { kv -> kv.size == 2 }?.let { kv -> kv[0] to kv[1] } }.toMap()
        fun kind(v: String?) = when (v) { "rocknix" -> AblKind.ROCKNIX; "stock" -> AblKind.STOCK; else -> AblKind.UNKNOWN }
        return AblStatus(f["soc"] ?: "unknown", f["slot"] ?: "?", kind(f["a"]), kind(f["b"]))
    }

    /** Sets the target as default boot and restarts. Returns an error message, or null on success. */
    suspend fun reboot(context: Context, target: Target): String? = withContext(Dispatchers.IO) {
        val r = bootswitch(context, "linux ${target.location}")
        if (!r.ok) return@withContext r.output.removePrefix("! ").ifBlank { "Root access denied" }
        Settings.setLastTarget(context, target)
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
