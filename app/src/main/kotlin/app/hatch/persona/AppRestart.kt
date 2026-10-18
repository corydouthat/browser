package app.hatch.persona

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import app.hatch.MainActivity
import app.hatch.components
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Full process restart (needed when `env: TZ` changes: the environment is read once at `GeckoRuntime.create`).
 *
 * Implementation note: an AlarmManager "launcher intent in 150 ms + killProcess" relaunch is unreliable on
 * Android 12+ (exact alarms need `SCHEDULE_EXACT_ALARM`, and a PendingIntent started from an alarm after the
 * process died counts as a background activity start, which the platform blocks). Instead we start
 * [RestartActivity] in a separate `:restart` process while we are still in the foreground; it kills the old
 * process, launches [MainActivity] (a fresh main process) and exits. Same technique as ProcessPhoenix.
 *
 * ENG-05: `Runtime.exit(0)` pre-empts every autosave path (`AutoSave`: 30 s periodic, ON_STOP after androidx's
 * 700 ms background grace, and the change-triggered save that waits `minimumIntervalMs` = 2 s), so a tab opened
 * or a navigation made in the last seconds before "Restart" was lost. [flushSession] writes the session file
 * synchronously first (`SessionStorage.save` is `synchronized(sessionFileLock)` over an `AtomicFile`,
 * `SessionStorage.kt:75-105`), bounded by [FLUSH_TIMEOUT_MS] so a stuck write can never block the restart.
 */
object AppRestart {
    private const val EXTRA_PID = "app.hatch.extra.PID"
    const val FLUSH_TIMEOUT_MS = 2_000L

    fun restart(context: Context) {
        val intent = Intent(context, RestartActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            .putExtra(EXTRA_PID, Process.myPid())
        context.startActivity(intent)
        if (context is Activity) context.finish()
        exitAfter(beforeExit = { flushSession(context) }, exit = { Runtime.getRuntime().exit(0) })
    }

    /**
     * Runs [beforeExit] and then [exit], always: a failure in the flush is logged by the caller's `runCatching`
     * and never keeps the process alive (JVM-tested).
     */
    fun exitAfter(beforeExit: () -> Unit, exit: () -> Unit) {
        try {
            beforeExit()
        } catch (_: Throwable) {
            // The restart must happen even when the session could not be written.
        }
        exit()
    }

    /** Writes the current `BrowserState` to the session file now, if the store was ever created. */
    private fun flushSession(context: Context) {
        val c = context.components
        if (!c.isStoreInitialized) return
        runBlocking {
            withTimeout(FLUSH_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { c.sessionStorage.save(c.store.state) }
            }
        }
    }

    /** Runs in the `:restart` process (see AndroidManifest); never loads Gecko. */
    class RestartActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val pid = intent.getIntExtra(EXTRA_PID, -1)
            if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid)
            val launch = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(launch)
            finish()
            Runtime.getRuntime().exit(0)
        }
    }
}
