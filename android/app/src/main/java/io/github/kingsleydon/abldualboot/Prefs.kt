package io.github.kingsleydon.abldualboot

import android.content.Context

/** Small app preferences: the last Linux destination (offered first by the tile) and auto-update. */
object Prefs {
    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun lastTarget(context: Context): Target? {
        val p = prefs(context)
        val location = p.getString("last_location", null) ?: return null
        return Target(location, p.getString("last_name", null) ?: "Linux")
    }

    /** Written synchronously: the device restarts right after this. */
    fun setLastTarget(context: Context, target: Target) {
        prefs(context).edit().putString("last_location", target.location).putString("last_name", target.name).commit()
    }

    fun autoUpdate(context: Context) = prefs(context).getBoolean("auto_update", false)

    fun setAutoUpdate(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("auto_update", enabled).apply()
}
