package app.hatch.persona

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** ENG-05: the session flush runs before the process exit, and a failing flush still exits. */
class AppRestartTest {
    @Test
    fun `beforeExit runs first, then exit`() {
        val order = mutableListOf<String>()
        AppRestart.exitAfter(beforeExit = { order += "flush" }, exit = { order += "exit" })
        assertEquals(listOf("flush", "exit"), order)
    }

    @Test
    fun `an exception in beforeExit still exits`() {
        val order = mutableListOf<String>()
        AppRestart.exitAfter(beforeExit = { order += "flush"; error("disk full") }, exit = { order += "exit" })
        assertEquals(listOf("flush", "exit"), order)
        assertEquals(2_000L, AppRestart.FLUSH_TIMEOUT_MS)
    }
}
