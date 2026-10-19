package app.hatch.crash

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Startup-crash capture: the decision and the report text are pure and locked here. */
class CrashReportTest {
    @Test
    fun `a leftover startup marker means safe mode, a clean start means normal`() {
        assertEquals(CrashReport.StartMode.SAFE, CrashReport.decide(startupMarkerExisted = true))
        assertEquals(CrashReport.StartMode.NORMAL, CrashReport.decide(startupMarkerExisted = false))
    }

    @Test
    fun `the report carries build, device, process, thread and the full cause chain`() {
        val cause = IllegalStateException("keystore unavailable")
        val top = RuntimeException("Unable to create application", cause)
        val text = CrashReport.format(
            appLine = "app.hatch.browser 0.1.0 (20) f454584 release",
            deviceLine = "Google Pixel, Android 16 (API 36)",
            processName = "app.hatch.browser",
            threadName = "main",
            throwable = top,
            time = "2026-10-19 08:00:00 +0000",
        )
        val lines = text.lines()
        assertEquals("Hatch crash report", lines[0])
        assertEquals("time: 2026-10-19 08:00:00 +0000", lines[1])
        assertEquals("app: app.hatch.browser 0.1.0 (20) f454584 release", lines[2])
        assertEquals("device: Google Pixel, Android 16 (API 36)", lines[3])
        assertEquals("process: app.hatch.browser", lines[4])
        assertEquals("thread: main", lines[5])
        assertTrue("java.lang.RuntimeException: Unable to create application" in text)
        assertTrue("Caused by: java.lang.IllegalStateException: keystore unavailable" in text)
        assertTrue("CrashReportTest" in text, "frames are present")
    }

    @Test
    fun `an unknown process is reported as a question mark`() {
        val text = CrashReport.format("a", "d", null, "t", RuntimeException("x"), "now")
        assertTrue("process: ?" in text)
    }
}
