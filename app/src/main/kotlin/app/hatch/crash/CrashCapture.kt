package app.hatch.crash

import android.app.Application
import android.content.Context
import android.os.Build
import app.hatch.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Startup-crash capture. [install] runs first thing in `HatchApplication.onCreate` (main process only):
 *
 * 1. If the previous start left `files/crashes/startup.marker` behind, that start never reached
 *    [startupCompleted]: this start enters **safe mode** ([safeMode] = true). The Application skips its warm-ups
 *    and `MainActivity` hands over to [CrashRecoveryActivity], which shows the saved trace (or
 *    [CrashReport.NO_JAVA_TRACE]) with Share / Copy / Try again / Clear app data. Nothing of Gecko, the store or
 *    the logins storage is touched in safe mode, so a crash in any of them cannot loop.
 * 2. The marker is (re)written and a default uncaught-exception handler is installed that writes
 *    `files/crashes/last.txt` ([CrashReport.format]) before delegating to the system handler, so Android still
 *    shows its own crash dialog and kills the process as usual.
 *
 * [startupCompleted] is called by `MainActivity` a few seconds after its first resume; a crash before that
 * trips safe mode on the next start, a crash after it only writes `last.txt` (visible in Settings → footer in a
 * later milestone; shareable from the recovery screen if the next start trips too).
 */
object CrashCapture {
    @Volatile var safeMode: Boolean = false
        private set

    /** The report of the crash that tripped safe mode, or null when none was written (native crash / kill). */
    @Volatile var lastReport: String? = null
        private set

    fun install(app: Application) {
        val dir = dir(app)
        val marker = marker(app)
        val markerExisted = marker.exists()
        safeMode = CrashReport.decide(markerExisted) == CrashReport.StartMode.SAFE
        lastReport = if (safeMode) runCatching { lastFile(app).takeIf { it.exists() }?.readText() }.getOrNull() else null
        runCatching {
            dir.mkdirs()
            marker.writeText(BuildConfig.VERSION_CODE.toString())
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                lastFile(app).writeText(
                    CrashReport.format(
                        appLine = appLine(),
                        deviceLine = deviceLine(),
                        processName = processName(),
                        threadName = thread.name,
                        throwable = throwable,
                        time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()),
                    ),
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** The browser UI is up: the next start boots normally. */
    fun startupCompleted(context: Context) {
        runCatching { marker(context).delete() }
    }

    /** "Try again" on the recovery screen: forget the marker and the report, then boot normally. */
    fun clear(context: Context) {
        runCatching { marker(context).delete() }
        runCatching { lastFile(context).delete() }
        safeMode = false
        lastReport = null
    }

    fun appLine(): String =
        "${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.GIT_SHA}" +
            (if (BuildConfig.DEBUG) " debug" else " release")

    fun deviceLine(): String = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.FINGERPRINT}"

    private fun processName(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching { Application.getProcessName() }.getOrNull() else null

    private fun dir(context: Context) = File(context.filesDir, "crashes")
    private fun marker(context: Context) = File(dir(context), "startup.marker")
    private fun lastFile(context: Context) = File(dir(context), "last.txt")
}
