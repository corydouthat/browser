package app.hatch

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.StrictMode
import androidx.work.Configuration
import app.hatch.crash.CrashCapture
import app.hatch.memory.MemoryTrim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.SystemAction
import mozilla.components.support.AppServicesInitializer
import java.io.File

/**
 * `Configuration.Provider` (PERF-06): the manifest removes androidx.work's automatic `WorkManagerInitializer`
 * (what Fenix does, `AndroidManifest.xml:847-857`), so nothing opens the WorkManager database at every cold start.
 * Hatch enqueues no work; should an AC module ever call `WorkManager.getInstance`, on-demand initialisation uses
 * this default configuration instead of throwing.
 */
class HatchApplication : Application(), Configuration.Provider {
    /** Lazy singletons; nothing touches Gecko until the browser UI asks for it. */
    val components: Components by lazy { Components(this) }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        // The ":restart" process (AppRestart) must never initialise Gecko or the store.
        if (isMainProcess()) {
            // Startup-crash capture first: a leftover marker from the previous start puts this one in safe mode,
            // in which nothing below runs and MainActivity shows CrashRecoveryActivity instead of the browser.
            CrashCapture.install(this)
            if (CrashCapture.safeMode) return
            // Before any Rust call (logins/db_crypto, sync15, search): NSS and the megazord, exactly as Fenix 158's
            // setupMegazordInitial. Without it the logins store throws "NSS has not initialized" on its first key.
            AppServicesInitializer.init(AppServicesInitializer.Config(crashReporting = null))
            installDebugStrictMode()
            components.rulesRepository // creates the bundled example rules on first run
            // PERF-03: ThumbnailStorage's constructor clears the private disk cache (DiskLruCache open + delete);
            // creating it here, off the main thread, keeps that IO out of MainActivity.onCreate's first-frame path.
            // (BrowserIcons is not warmed here: it needs the GeckoRuntime, which must be created on the UI thread.)
            components.applicationScope.launch(Dispatchers.IO) { components.thumbnailStorage }
        }
    }

    /**
     * ENG-01: forwards memory pressure like Fenix (`FenixApplication.onTrimMemory`): the favicon memory cache is
     * trimmed and `SystemAction.LowMemoryAction` lets AC's `TrimMemoryMiddleware` suspend every engine session except
     * the selected tab and the three most recently used (their `EngineSessionState` is kept; reselecting recreates
     * the session with the persona through `CreateEngineSessionMiddleware`). Main process only; never creates the
     * store or the icon cache just to trim them.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (CrashCapture.safeMode) return
        val actions = MemoryTrim.actionsFor(
            level = level,
            isMainProcess = isMainProcess(),
            storeInitialized = components.isStoreInitialized,
            iconsInitialized = components.isIconsInitialized,
        )
        for (action in actions) {
            when (action) {
                MemoryTrim.Action.TrimIconCache -> components.icons.onTrimMemory(level)
                MemoryTrim.Action.DispatchLowMemory -> components.store.dispatch(SystemAction.LowMemoryAction(level))
            }
        }
    }

    /** PERF-03 diagnostic: debug builds log main-thread disk IO (never crash) so cold-start regressions show in logcat. */
    private fun installDebugStrictMode() {
        if (!BuildConfig.DEBUG) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .penaltyLog()
                .build(),
        )
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
