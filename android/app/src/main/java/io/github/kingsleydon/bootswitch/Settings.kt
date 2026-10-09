package io.github.kingsleydon.bootswitch

import android.content.Context

/** Remembers the last Linux destination so the tile can offer it first. */
object Settings {
    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun lastTarget(context: Context): Target? {
        val p = prefs(context)
        val location = p.getString("last_location", null) ?: return null
        return Target(location, p.getString("last_name", "Linux")!!)
    }

    fun setLastTarget(context: Context, target: Target) =
        prefs(context).edit().putString("last_location", target.location).putString("last_name", target.name).apply()
}
