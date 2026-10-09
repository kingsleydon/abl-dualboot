package io.github.kingsleydon.abldualboot

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class BootTarget { ANDROID, LINUX, UNKNOWN }
enum class AblKind { ROCKNIX, STOCK, UNKNOWN }

/** A Linux system the ROCKNIX ABL can boot: location is internal, sd or usb; name is e.g. Armada. */
data class Target(val location: String, val name: String) {
    val where get() = when (location) { "internal" -> "Internal storage"; "sd" -> "SD card"; else -> "USB drive" }
    val title get() = if (location == "usb") "Linux on USB" else name
}

data class AblStatus(val soc: String, val slotA: AblKind, val slotB: AblKind) {
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

/** Talks to the bundled device scripts in shared/. */
object Device {
    const val BACKUP_DIR = "/sdcard/ABLDualBoot/backup"

    private fun dualboot(context: Context, args: String) =
        Root.run("DUALBOOT_TMP='${context.cacheDir}' sh '${Root.asset(context, "dualboot.sh")}' $args")

    suspend fun load(context: Context): DeviceState = withContext(Dispatchers.IO) {
        if (!Root.available()) return@withContext DeviceState.NoRoot(Root.detectManager(context))
        val status = dualboot(context, "status")
        DeviceState.Ready(
            defaultBoot = when (status.output.substringBefore(" ")) {
                "android" -> BootTarget.ANDROID; "linux" -> BootTarget.LINUX; else -> BootTarget.UNKNOWN
            },
            targets = targets(context),
            abl = ablStatus(context),
            error = if (status.ok) null else status.error,
        )
    }

    fun targets(context: Context): List<Target> =
        dualboot(context, "targets").output.lines().mapNotNull { line ->
            line.trim().split(" ", limit = 2).takeIf { it.size == 2 }?.let { Target(it[0], it[1]) }
        }

    fun ablStatus(context: Context): AblStatus {
        val out = Root.run("sh '${Root.asset(context, "abl.sh")}' status").output
        val f = out.split(" ").mapNotNull { it.split("=").takeIf { kv -> kv.size == 2 }?.let { kv -> kv[0] to kv[1] } }.toMap()
        fun kind(v: String?) = when (v) { "rocknix" -> AblKind.ROCKNIX; "stock" -> AblKind.STOCK; else -> AblKind.UNKNOWN }
        return AblStatus(f["soc"] ?: "unknown", kind(f["a"]), kind(f["b"]))
    }

    /** Sets the target as default boot and restarts. Returns an error message, or null on success. */
    suspend fun reboot(context: Context, target: Target): String? = withContext(Dispatchers.IO) {
        val r = dualboot(context, "linux ${target.location}")
        if (!r.ok) return@withContext r.error
        Prefs.setLastTarget(context, target)
        Root.run("reboot")
        null
    }

    /**
     * Downloads the official ROCKNIX ABL for this SoC, verifies it and flashes it to both slots.
     * Returns the script log; ok=false on failure.
     */
    suspend fun installAbl(context: Context, soc: String): Root.Result = withContext(Dispatchers.IO) {
        val elf = try {
            AblRelease.extract(AblRelease.download(), soc)
        } catch (e: Exception) {
            return@withContext Root.Result(false, e.message ?: "Could not download the ROCKNIX ABL")
        }
        val file = File(context.filesDir, "abl_signed-$soc.elf").apply { writeBytes(elf) }
        try {
            Root.run("sh '${Root.asset(context, "abl.sh")}' flash '${file.absolutePath}' ${sha256(elf)} $BACKUP_DIR")
        } finally {
            file.delete()
        }
    }
}
