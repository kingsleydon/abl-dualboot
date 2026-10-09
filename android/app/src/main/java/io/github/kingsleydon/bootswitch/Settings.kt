package io.github.kingsleydon.bootswitch

import android.content.Context

object Settings {
    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun linuxSource(context: Context): BootSource =
        if (prefs(context).getString("source", "sd") == "internal") BootSource.INTERNAL else BootSource.SD

    fun setLinuxSource(context: Context, source: BootSource) =
        prefs(context).edit().putString("source", if (source == BootSource.INTERNAL) "internal" else "sd").apply()
}
