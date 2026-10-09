package io.github.kingsleydon.bootswitch

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/** Thin su wrapper that works with Magisk, KernelSU (and forks) and APatch - they all provide `su -c`. */
object Root {
    data class Result(val ok: Boolean, val output: String)

    fun run(command: String): Result = try {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        Result(process.waitFor() == 0, output)
    } catch (e: Exception) {
        Result(false, "")
    }

    fun available(): Boolean = run("id").output.contains("uid=0")

    /** Copies a bundled asset to app storage (readable by root) and returns its path. */
    fun asset(context: Context, name: String): String {
        val file = File(context.filesDir, name)
        file.parentFile?.mkdirs()
        context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return file.absolutePath
    }

    enum class Manager(val label: String, val packages: List<String>, val grantSteps: String) {
        MAGISK("Magisk", listOf("com.topjohnwu.magisk", "io.github.huskydg.magisk", "io.github.vvb2060.magisk"),
            "Tap Grant when Magisk asks. If you denied it before: Magisk → Superuser → enable Boot Switch."),
        KERNELSU("KernelSU", listOf("me.weishu.kernelsu"),
            "Open KernelSU → Superuser → enable Boot Switch."),
        KERNELSU_NEXT("KernelSU Next", listOf("com.rifsxd.ksunext"),
            "Open KernelSU Next → Superuser → enable Boot Switch."),
        SUKISU("SukiSU Ultra", listOf("com.sukisu.ultra"),
            "Open SukiSU → Superuser → enable Boot Switch."),
        APATCH("APatch", listOf("me.bmax.apatch"),
            "Open APatch → Superuser → enable Boot Switch."),
    }

    fun detectManager(context: Context): Manager? = Manager.entries.firstOrNull { m ->
        m.packages.any { pkg ->
            try { context.packageManager.getPackageInfo(pkg, 0); true } catch (e: PackageManager.NameNotFoundException) { false }
        }
    }
}
