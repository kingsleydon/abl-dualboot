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

data class AblStatus(
    val soc: String, val slotA: AblKind, val slotB: AblKind, val error: String? = null,
    val miscSupported: Boolean = false, val inspections: List<OpenAbl.Inspection> = emptyList(),
) {
    val openabl get() = slotA == AblKind.OPENABL || slotB == AblKind.OPENABL
    val blockingError get() = if (openabl && !miscSupported) OpenAbl.ERROR else error
    val supported get() = soc != "unknown"
    val installed get() = (openabl && miscSupported) || (slotA == AblKind.ROCKNIX && slotB == AblKind.ROCKNIX)
    /** One slot still has ROCKNIX but the other does not: a system update replaced it. */
    val replacedByUpdate get() = !openabl && !installed && (slotA == AblKind.ROCKNIX || slotB == AblKind.ROCKNIX)
    val inspectionArgs get() = inspections.joinToString(" ") { it.arguments }
    val flashable get() = inspections.size == 2 && inspections.all { it.kind == AblKind.ROCKNIX || it.kind == AblKind.STOCK }
}

sealed interface DeviceState {
    data object Loading : DeviceState
    data class NoRoot(val manager: Root.Manager?) : DeviceState
    data class Ready(val defaultBoot: BootTarget, val targets: List<Target>, val abl: AblStatus, val error: String?) : DeviceState
}

/** Talks to the bundled device scripts in shared/. */
object Device {
    const val BACKUP_DIR = "/sdcard/ABLDualBoot/backup"

    @Synchronized private fun dualboot(context: Context, args: String): Root.Result {
        val abl = ablStatus(context)
        abl.blockingError?.let { return Root.Result(false, it) }
        if (abl.openabl) return misc(context, args)
        val command = args.split(' ', limit = 2)
        val location = command.getOrElse(1) { "" }
        if (location.isNotEmpty() && location !in listOf("internal", "sd", "usb")) return Root.Result(false, "location must be sd, internal or usb")
        val scriptArgs = when (command[0]) {
            "status", "targets" -> command[0]
            "android", "linux" -> "${command[0]} '$location' ${abl.inspectionArgs}"
            else -> return Root.Result(false, "target must be android or linux")
        }
        return Root.run("DUALBOOT_TMP='${context.cacheDir}' sh '${Root.asset(context, "dualboot.sh")}' $scriptArgs")
    }

    /** Kotlin owns parsing/CRC; root only copies bounded bytes, syncs, compares and rolls back. */
    private fun misc(context: Context, args: String): Root.Result {
        val directory = File(context.cacheDir, "misc-inspection-${UUID.randomUUID()}")
        try {
            check(directory.mkdir()) { "Cannot create misc inspection directory" }
            val before = File(directory, "before.bin").apply { writeBytes(byteArrayOf()) }
            val script = Root.asset(context, "misc.sh")
            val read = Root.run("sh '$script' read '${directory.absolutePath}'")
            if (!read.ok) return Root.Result(false, OpenAbl.ERROR)
            val store = before.readBytes()
            if (!OpenAblStore.valid(store)) return Root.Result(false, OpenAbl.ERROR)
            if (args == "status") return Root.Result(true, OpenAblStore.status(store))
            val locations = Root.asset(context, "dualboot.sh")
            if (args == "targets") return Root.run("sh '$locations' locations")
            val fields = read.output.split(' ')
            check(fields.size == 2 && fields[0].toLongOrNull()?.let { it >= 0 } == true &&
                fields[1].matches(Regex("[a-f0-9]{64}"))) { "Invalid misc snapshot" }
            val internal = Root.run("sh '$locations' internal")
            if (!internal.ok || internal.output !in listOf("0", "1")) return Root.Result(false, "Cannot inspect internal storage - nothing written")
            val command = args.split(' ', limit = 2)
            val next = OpenAblStore.edit(store, command[0], command.getOrElse(1) { "" }, internal.output == "1")
            File(directory, "next.bin").writeBytes(next)
            val result = Root.run("sh '$script' write '${directory.absolutePath}' ${fields[0]} ${fields[1]} ${sha256(next)}")
            if (!result.ok) return result
            return Root.Result(true, OpenAblStore.status(next) + " ok")
        } catch (e: IllegalArgumentException) {
            return Root.Result(false, e.message ?: OpenAbl.ERROR)
        } catch (_: Exception) {
            return Root.Result(false, "Cannot access OpenABL settings")
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
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

    @Synchronized fun ablStatus(context: Context): AblStatus {
        val directory = File(context.cacheDir, "abl-inspection-${UUID.randomUUID()}")
        val files = listOf("a", "b").map { File(directory, "abl_$it.img") }
        try {
            check(directory.mkdir()) { "Cannot create inspection directory" }
            files.forEach { it.writeBytes(byteArrayOf()) }
            val catalog = OpenAbl.catalog(File(Root.asset(context, "openabl-images.txt")).readText())
            val result = Root.run("sh '${Root.asset(context, "abl.sh")}' status '${directory.absolutePath}'")
            if (!result.ok) return AblStatus("unknown", AblKind.UNKNOWN, AblKind.UNKNOWN, result.error)
            val fields = result.output.split(" ").mapNotNull { it.split("=").takeIf { kv -> kv.size == 2 }?.let { kv -> kv[0] to kv[1] } }.toMap()
            val images = files.map { it.readBytes() }
            val inspections = images.map { OpenAbl.inspect(it, catalog) }
            val supported = OpenAbl.canUseMisc(inspections) && misc(context, "status").ok
            return AblStatus(fields["soc"] ?: "unknown", inspections[0].kind, inspections[1].kind,
                miscSupported = supported, inspections = inspections)
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
        if (abl.openabl) return@withContext Root.Result(false, OpenAbl.ERROR)
        abl.blockingError?.let { return@withContext Root.Result(false, it) }
        if (!abl.flashable) return@withContext Root.Result(false, "Cannot identify both ABL slots - nothing written")
        val elf = try {
            AblRelease.extract(AblRelease.download(), soc)
        } catch (e: Exception) {
            return@withContext Root.Result(false, e.message ?: "Could not download the ROCKNIX ABL")
        }
        val file = File(context.filesDir, "abl_signed-$soc.elf").apply { writeBytes(elf) }
        try {
            val current = ablStatus(context)
            if (current.openabl) return@withContext Root.Result(false, OpenAbl.ERROR)
            current.blockingError?.let { return@withContext Root.Result(false, it) }
            if (!current.flashable) return@withContext Root.Result(false, "Cannot identify both ABL slots - nothing written")
            Root.run("DUALBOOT_TMP='${context.cacheDir}' sh '${Root.asset(context, "abl.sh")}' flash '${file.absolutePath}' ${sha256(elf)} $BACKUP_DIR ${current.inspectionArgs}")
        } finally {
            file.delete()
        }
    }
}
