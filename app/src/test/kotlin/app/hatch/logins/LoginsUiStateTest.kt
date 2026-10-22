package app.hatch.logins

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Back / Esc leave the Saved login detail for the list before they close the Saved logins screen. */
class LoginsUiStateTest {
    @Test
    fun `back pops the detail page first`() {
        val ui = LoginsUiState()
        assertFalse(ui.onBack(), "closed screen does not consume back")
        ui.savedLoginsOpen = true
        ui.detailGuid = "g1"
        assertTrue(ui.onBack())
        assertNull(ui.detailGuid)
        assertTrue(ui.savedLoginsOpen, "the list is still open")
        assertTrue(ui.onBack())
        assertFalse(ui.savedLoginsOpen)
        ui.detailGuid = "g2"
        assertFalse(ui.onBack(), "a stale detail guid does not keep a closed screen alive")
        assertEquals("g2", ui.detailGuid)
    }
}
