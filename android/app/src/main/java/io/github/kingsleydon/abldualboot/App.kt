package io.github.kingsleydon.abldualboot

import android.app.Application
import com.topjohnwu.superuser.Shell

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        UpdateWorker.schedule(this)
    }

    companion object {
        init {
            // libsu: configure the main shell before it is first created.
            Shell.enableVerboseLogging = BuildConfig.DEBUG
            Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(10))
        }
    }
}
