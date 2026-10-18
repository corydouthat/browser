package app.hatch

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File

class HatchApplication : Application() {
    /** Lazy singletons; nothing touches Gecko until the browser UI asks for it. */
    val components: Components by lazy { Components(this) }

    override fun onCreate() {
        super.onCreate()
        // The ":restart" process (AppRestart) must never initialise Gecko or the store.
        if (isMainProcess()) {
            components.rulesRepository // creates the bundled example rules on first run
        }
    }

    private fun isMainProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { Application.getProcessName() }.getOrNull()
        } else {
            runCatching { File("/proc/self/cmdline").readText().trim { it <= ' ' || it == '\u0000' } }.getOrNull()
        } ?: return true
        return name == packageName
    }
}

val Context.components: Components
    get() = (applicationContext as HatchApplication).components
