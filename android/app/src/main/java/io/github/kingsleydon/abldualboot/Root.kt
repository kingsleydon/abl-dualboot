package io.github.kingsleydon.abldualboot

import android.content.Context
import android.content.pm.PackageManager
import com.topjohnwu.superuser.Shell
import java.io.File

/** Root access through libsu, which works with any su provider (Magisk, KernelSU and forks, APatch). */
object Root {
    data class Result(val ok: Boolean, val output: String) {
        /** Script output without the "! " prefix the device scripts put on errors. */
        val error: String get() = output.removePrefix("! ").ifBlank { "Root access denied" }
    }

    fun run(command: String): Result {
        if (!available()) return Result(false, "")
        val r = Shell.cmd(command).exec()
        return Result(r.isSuccess, r.out.joinToString("\n").trim())
    }

    fun available(): Boolean = Shell.getShell().isRoot

    /** Drops a cached non-root shell so the next call asks for root again. */
    fun retry() {
        Shell.getCachedShell()?.takeIf { !it.isRoot }?.close()
    }

    /** Copies a bundled asset to app storage (readable by root) and returns its path. */
    fun asset(context: Context, name: String): String {
        val file = File(context.filesDir, name)
        file.parentFile?.mkdirs()
        context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return file.absolutePath
    }

    const val NO_ROOT = "ABL Dual Boot needs root access. Open the app for setup steps."

    enum class Manager(val label: String, val packages: List<String>, val grantSteps: String) {
        MAGISK("Magisk", listOf("com.topjohnwu.magisk", "io.github.huskydg.magisk", "io.github.vvb2060.magisk"),
            "Tap Grant when Magisk asks. If you denied it before: Magisk → Superuser → enable ABL Dual Boot."),
        KERNELSU("KernelSU", listOf("me.weishu.kernelsu"),
            "Open KernelSU → Superuser → enable ABL Dual Boot."),
        KERNELSU_NEXT("KernelSU Next", listOf("com.rifsxd.ksunext"),
            "Open KernelSU Next → Superuser → enable ABL Dual Boot."),
        SUKISU("SukiSU Ultra", listOf("com.sukisu.ultra"),
            "Open SukiSU → Superuser → enable ABL Dual Boot."),
        APATCH("APatch", listOf("me.bmax.apatch"),
            "Open APatch → Superuser → enable ABL Dual Boot."),
    }

    fun detectManager(context: Context): Manager? = Manager.entries.firstOrNull { m ->
        m.packages.any { pkg ->
            try { context.packageManager.getPackageInfo(pkg, 0); true } catch (e: PackageManager.NameNotFoundException) { false }
        }
    }
}
