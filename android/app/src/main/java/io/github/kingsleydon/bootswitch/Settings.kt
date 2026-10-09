package io.github.kingsleydon.bootswitch

import android.content.Context

/** Where Linux boots from. AUTOMATIC picks it the way the ROCKNIX ABL menu does. */
enum class LinuxLocation(val arg: String, val label: String, val description: String) {
    AUTOMATIC("", "Automatic", "Internal storage if Linux is installed there, otherwise SD card"),
    SD("sd", "SD card", "Always boot Linux from the SD card"),
    INTERNAL("internal", "Internal storage", "Always boot Linux installed on internal storage"),
    USB("usb", "USB", "Always boot Linux from a USB drive"),
}

object Settings {
    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun linuxLocation(context: Context): LinuxLocation =
        LinuxLocation.entries.firstOrNull { it.name == prefs(context).getString("linux_location", null) } ?: LinuxLocation.AUTOMATIC

    fun setLinuxLocation(context: Context, location: LinuxLocation) =
        prefs(context).edit().putString("linux_location", location.name).apply()
}
