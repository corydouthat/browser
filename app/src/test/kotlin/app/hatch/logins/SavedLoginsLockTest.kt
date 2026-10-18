package app.hatch.logins

import mozilla.components.concept.storage.Login
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SEC-1: leaving the foreground re-locks, hides and forgets. */
class SavedLoginsLockTest {
    private fun login(guid: String) = Login(guid = guid, origin = "https://a.example", username = "u", password = "p")

    @Test
    fun `unlock, reveal, then ON_STOP locks and clears everything`() {
        val lock = SavedLoginsLock()
        assertFalse(lock.unlocked)
        lock.toggleReveal("g1")
        assertTrue(lock.revealed.isEmpty(), "nothing can be revealed while locked")

        lock.unlock()
        lock.logins = listOf(login("g1"), login("g2"))
        lock.toggleReveal("g1")
        assertEquals(setOf("g1"), lock.revealed)
        lock.toggleReveal("g1")
        assertTrue(lock.revealed.isEmpty())
        lock.toggleReveal("g2")

        lock.onStop()
        assertFalse(lock.unlocked)
        assertTrue(lock.revealed.isEmpty())
        assertTrue(lock.logins.isEmpty())
    }
}
