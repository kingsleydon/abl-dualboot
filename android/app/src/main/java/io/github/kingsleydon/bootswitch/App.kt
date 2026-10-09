package io.github.kingsleydon.bootswitch

import android.app.Application
import com.topjohnwu.superuser.Shell

class App : Application() {
    companion object {
        init {
            // libsu: configure the main shell before it is first created.
            Shell.enableVerboseLogging = BuildConfig.DEBUG
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_REDIRECT_STDERR)
                    .setTimeout(10),
            )
        }
    }
}
