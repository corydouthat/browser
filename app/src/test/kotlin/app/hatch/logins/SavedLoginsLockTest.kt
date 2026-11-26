package app.hatch.logins

import mozilla.components.concept.storage.Login
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun `dialogs open only while unlocked and ON_STOP closes them (RS-01)`() {
        val lock = SavedLoginsLock()
        assertFalse(lock.beginEdit("g1"), "edit is refused while locked")
        assertFalse(lock.beginAdd())
        assertFalse(lock.beginDelete("g1"))
        assertNull(lock.editingGuid)
        assertFalse(lock.adding)

        lock.unlock()
        lock.logins = listOf(login("g1"))
        assertTrue(lock.beginEdit("g1"))
        assertEquals("p", lock.editing()?.password)

        lock.onStop()
        assertNull(lock.editingGuid, "leaving the foreground closes the Edit dialog")
        assertNull(lock.editing())
        assertFalse(lock.beginEdit("g1"))

        lock.unlock()
        assertTrue(lock.beginAdd())
        assertTrue(lock.beginDelete("g1"))
        lock.onStop()
        assertFalse(lock.adding)
        assertNull(lock.deletingGuid)
    }

    @Test
    fun `the edit dialog re-reads the password from the list loaded after unlock`() {
        val lock = SavedLoginsLock()
        lock.unlock()
        assertTrue(lock.beginEdit("g1"))
        assertNull(lock.editing(), "nothing to show until the list is loaded")
        lock.logins = listOf(login("g1").copy(password = "fresh"))
        assertEquals("fresh", lock.editing()?.password)
    }

    @Test
    fun `the screen composes its dialogs only inside the unlocked branch`() {
        val rel = "src/main/kotlin/app/hatch/ui/logins/SavedLoginsScreen.kt"
        val src = listOf(java.io.File(rel), java.io.File("app/$rel")).first { it.isFile }.readText()
        val gate = src.indexOf("if (!unlocked) return")
        assertTrue(gate > 0, "SavedLoginsScreen must return before its dialogs while locked")
        for (call in listOf("lock.editing()?.let", "lock.deleting()?.let", "if (lock.adding)")) {
            val at = src.indexOf(call)
            assertTrue(at > gate, "$call must come after the unlocked gate")
        }
        assertFalse(Regex("""mutableStateOf<Login\?>""").containsMatchIn(src), "no Login (password) kept in screen-local state")
    }
}
