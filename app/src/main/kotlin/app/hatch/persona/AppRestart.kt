package app.hatch.persona

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import app.hatch.MainActivity

/**
 * Full process restart (needed when `env: TZ` changes: the environment is read once at `GeckoRuntime.create`).
 *
 * Implementation note: an AlarmManager "launcher intent in 150 ms + killProcess" relaunch is unreliable on
 * Android 12+ (exact alarms need `SCHEDULE_EXACT_ALARM`, and a PendingIntent started from an alarm after the
 * process died counts as a background activity start, which the platform blocks). Instead we start
 * [RestartActivity] in a separate `:restart` process while we are still in the foreground; it kills the old
 * process, launches [MainActivity] (a fresh main process) and exits. Same technique as ProcessPhoenix.
 */
object AppRestart {
    private const val EXTRA_PID = "app.hatch.extra.PID"

    fun restart(context: Context) {
        val intent = Intent(context, RestartActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            .putExtra(EXTRA_PID, Process.myPid())
        context.startActivity(intent)
        if (context is Activity) context.finish()
        Runtime.getRuntime().exit(0)
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
