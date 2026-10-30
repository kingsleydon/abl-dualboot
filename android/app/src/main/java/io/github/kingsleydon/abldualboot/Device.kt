package io.github.kingsleydon.abldualboot

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class BootTarget { ANDROID, LINUX, UNKNOWN }
enum class AblKind { OPENABL, ROCKNIX, STOCK, UNKNOWN }

/** A Linux system the ROCKNIX ABL can boot: location is internal, sd or usb; name is e.g. Armada. */
data class Target(val location: String, val name: String) {
    val where get() = when (location) { "internal" -> "Internal storage"; "sd" -> "SD card"; else -> "USB drive" }
    val title get() = if (location == "usb") "Linux on USB" else name
}

data class AblStatus(val soc: String, val slotA: AblKind, val slotB: AblKind, val error: String? = null) {
    val openabl get() = slotA == AblKind.OPENABL || slotB == AblKind.OPENABL
    val blockingError get() = if (openabl) OpenAbl.ERROR else error
    val supported get() = soc != "unknown"
    val installed get() = slotA == AblKind.ROCKNIX && slotB == AblKind.ROCKNIX
    /** One slot still has ROCKNIX but the other does not: a system update replaced it. */
    val replacedByUpdate get() = !openabl && !installed && (slotA == AblKind.ROCKNIX || slotB == AblKind.ROCKNIX)
}

sealed interface DeviceState {
    data object Loading : DeviceState
    data class NoRoot(val manager: Root.Manager?) : DeviceState
    data class Ready(val defaultBoot: BootTarget, val targets: List<Target>, val abl: AblStatus, val error: String?) : DeviceState
}

/** Talks to the bundled device scripts in shared/. */
object Device {
    const val BACKUP_DIR = "/sdcard/ABLDualBoot/backup"

    private fun dualboot(context: Context, args: String): Root.Result {
        val abl = ablStatus(context)
        abl.blockingError?.let { return Root.Result(false, it) }
        return Root.run("DUALBOOT_TMP='${context.cacheDir}' sh '${Root.asset(context, "dualboot.sh")}' $args")
    }

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

    fun targets(context: Context): List<Target> {
        val result = dualboot(context, "targets")
        if (!result.ok) return emptyList()
        return result.output.lines().mapNotNull { line ->
            line.trim().split(" ", limit = 2).takeIf { it.size == 2 }?.let { Target(it[0], it[1]) }
        }
    }

    fun ablStatus(context: Context): AblStatus {
        val directory = File(context.cacheDir, "abl-inspection-${UUID.randomUUID()}")
        val files = listOf("a", "b").map { File(directory, "abl_$it.img") }
        try {
            check(directory.mkdir()) { "Cannot create inspection directory" }
            files.forEach { it.writeBytes(byteArrayOf()) }
            val catalog = OpenAbl.catalog(File(Root.asset(context, "openabl-images.txt")).readText())
            val result = Root.run("sh '${Root.asset(context, "abl.sh")}' status '${directory.absolutePath}'")
            if (!result.ok) return AblStatus("unknown", AblKind.UNKNOWN, AblKind.UNKNOWN, result.error)
            val fields = result.output.split(" ").mapNotNull { it.split("=").takeIf { kv -> kv.size == 2 }?.let { kv -> kv[0] to kv[1] } }.toMap()
            return AblStatus(fields["soc"] ?: "unknown", OpenAbl.kind(files[0].readBytes(), catalog), OpenAbl.kind(files[1].readBytes(), catalog))
        } catch (e: Exception) {
            return AblStatus("unknown", AblKind.UNKNOWN, AblKind.UNKNOWN, "Cannot inspect bootloader - nothing written")
        } finally {
            files.forEach { it.delete() }
            directory.delete()
        }
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
        val abl = ablStatus(context)
        abl.blockingError?.let { return@withContext Root.Result(false, it) }
        val elf = try {
            AblRelease.extract(AblRelease.download(), soc)
        } catch (e: Exception) {
            return@withContext Root.Result(false, e.message ?: "Could not download the ROCKNIX ABL")
        }
        val file = File(context.filesDir, "abl_signed-$soc.elf").apply { writeBytes(elf) }
        try {
            ablStatus(context).blockingError?.let { return@withContext Root.Result(false, it) }
            Root.run("sh '${Root.asset(context, "abl.sh")}' flash '${file.absolutePath}' ${sha256(elf)} $BACKUP_DIR")
        } finally {
            file.delete()
        }
    }
}
