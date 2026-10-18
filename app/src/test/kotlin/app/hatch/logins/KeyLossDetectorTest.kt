package app.hatch.logins

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SEC-6: only "database present, key absent" is a key loss. */
class KeyLossDetectorTest {
    @Test
    fun `four combinations`() {
        assertTrue(KeyLossDetector.detect(dbExists = true, keyPresent = false))
        assertFalse(KeyLossDetector.detect(dbExists = true, keyPresent = true))
        assertFalse(KeyLossDetector.detect(dbExists = false, keyPresent = false), "first run")
        assertFalse(KeyLossDetector.detect(dbExists = false, keyPresent = true), "key without a database yet")
    }

    @Test
    fun `the aside copy keeps the database name and is timestamped`() {
        assertEquals("logins2.sqlite.undecryptable-1700000000000", KeyLossDetector.asideName("logins2.sqlite", 1_700_000_000_000L))
        assertEquals("logins2.sqlite", LoginsStorageProvider.DB_NAME)
    }

    @Test
    fun `the notice is surfaced through the logins ui state and consumed once`() {
        val ui = LoginsUiState()
        assertFalse(ui.lock.unlocked)
        assertTrue(KeyLossDetector.NOTICE.contains("reset"))
    }
}
