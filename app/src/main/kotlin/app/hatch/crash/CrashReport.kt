package app.hatch.crash

import java.io.PrintWriter
import java.io.StringWriter

/**
 * Pure formatting and decision logic behind [CrashCapture] (JVM-tested). Hatch has no adb on the road, so the
 * only crash evidence that reaches a developer is what the app itself writes to disk and lets the user share.
 */
object CrashReport {
    /** What the next start should do, given what the previous one left behind. */
    enum class StartMode {
        /** The previous start completed (or there was none): boot the browser. */
        NORMAL,

        /** The previous start never reached [CrashCapture.startupCompleted]: show the recovery screen instead. */
        SAFE,
    }

    fun decide(startupMarkerExisted: Boolean): StartMode = if (startupMarkerExisted) StartMode.SAFE else StartMode.NORMAL

    /** The text written to `files/crashes/last.txt` and shared from the recovery screen. */
    fun format(
        appLine: String,
        deviceLine: String,
        processName: String?,
        threadName: String,
        throwable: Throwable,
        time: String,
    ): String = buildString {
        appendLine("Hatch crash report")
        appendLine("time: $time")
        appendLine("app: $appLine")
        appendLine("device: $deviceLine")
        appendLine("process: ${processName ?: "?"}")
        appendLine("thread: $threadName")
        appendLine()
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        append(sw.toString())
    }

    /** What the recovery screen says when the marker was left behind but no Java trace was written. */
    const val NO_JAVA_TRACE: String =
        "The last start did not finish, but no Java stack trace was captured. That usually means a native crash " +
            "(Gecko) or the process was killed during startup; a logcat/tombstone is needed for those."
}
