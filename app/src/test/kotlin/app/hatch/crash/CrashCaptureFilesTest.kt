package app.hatch.crash

import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashCaptureFilesTest {
    private fun dir(): File = Files.createTempDirectory("crashes").toFile()

    @Test
    fun `a crash after startup is offered once on the next normal start`() {
        val d = dir()
        File(d, "last.txt").writeText("trace")
        assertEquals("trace", CrashCapture.pendingReport(d, safeMode = false))
        CrashCapture.acknowledge(d)
        assertNull(CrashCapture.pendingReport(d, safeMode = false))
        assertEquals("trace", File(d, "previous.txt").readText())
        assertFalse(File(d, "last.txt").exists())
    }

    @Test
    fun `safe mode shows the report on the recovery screen instead`() {
        val d = dir()
        File(d, "last.txt").writeText("trace")
        assertNull(CrashCapture.pendingReport(d, safeMode = true))
        assertTrue(File(d, "last.txt").exists())
    }

    @Test
    fun `acknowledge replaces an older previous report and tolerates no report`() {
        val d = dir()
        CrashCapture.acknowledge(d)
        File(d, "previous.txt").writeText("old")
        File(d, "last.txt").writeText("new")
        CrashCapture.acknowledge(d)
        assertEquals("new", File(d, "previous.txt").readText())
    }
}
