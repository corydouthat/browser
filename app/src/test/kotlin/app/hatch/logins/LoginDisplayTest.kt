package app.hatch.logins

import mozilla.components.concept.storage.Login
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoginDisplayTest {
    private fun login(guid: String, origin: String, user: String) = Login(guid = guid, username = user, password = "x", origin = origin)

    @Test
    fun `host label strips scheme and path`() {
        assertEquals("accounts.google.com", LoginDisplay.hostLabel("https://accounts.google.com"))
        assertEquals("example.com:8443", LoginDisplay.hostLabel("https://example.com:8443/login"))
        assertEquals("realm-only", LoginDisplay.hostLabel("realm-only"))
    }

    @Test
    fun `sorted by host then username, case-insensitive`() {
        val list = listOf(
            login("1", "https://zeta.example", "a"),
            login("2", "https://Alpha.example", "Zed"),
            login("3", "https://alpha.example", "bob"),
        )
        assertEquals(listOf("3", "2", "1"), LoginDisplay.sorted(list).map { it.guid })
    }

    @Test
    fun `filter matches origin or username and ignores blank queries`() {
        val list = listOf(login("1", "https://github.com", "cory"), login("2", "https://example.com", "work"))
        assertEquals(listOf("1"), LoginDisplay.filter(list, "GIT").map { it.guid })
        assertEquals(listOf("2"), LoginDisplay.filter(list, "work").map { it.guid })
        assertEquals(listOf("1", "2"), LoginDisplay.filter(list, "  ").map { it.guid })
        assertEquals(emptyList(), LoginDisplay.filter(list, "nope"))
    }

    @Test
    fun `origin for a new login needs a real host`() {
        assertEquals("https://example.com", LoginDisplay.originForNewLogin("example.com/login"))
        assertEquals("http://localhost:3000", LoginDisplay.originForNewLogin("http://localhost:3000"))
        assertNull(LoginDisplay.originForNewLogin("nodots"))
        assertNull(LoginDisplay.originForNewLogin(""))
    }
}
