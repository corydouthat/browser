package app.hatch.logins

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Wire form of the "never save" list (SharedPreferences string) and the origin key it is indexed by. */
class LoginExceptionCodecTest {
    @Test
    fun `origins normalise to scheme and host`() {
        assertEquals("https://accounts.google.com", LoginExceptionCodec.normalizeOrigin("https://accounts.google.com"))
        assertEquals("https://accounts.google.com", LoginExceptionCodec.normalizeOrigin("  HTTPS://Accounts.Google.com/signin?x=1#f "))
        assertEquals("https://example.com", LoginExceptionCodec.normalizeOrigin("https://www.example.com/"))
        assertEquals("http://localhost:8080", LoginExceptionCodec.normalizeOrigin("http://localhost:8080/login"))
        assertEquals("https://example.com", LoginExceptionCodec.normalizeOrigin("example.com"), "scheme-less input assumes https")
        assertEquals("", LoginExceptionCodec.normalizeOrigin(""))
        assertEquals("", LoginExceptionCodec.normalizeOrigin("https://"))
    }

    @Test
    fun `encode is a sorted JSON array and decode normalises and drops junk`() {
        val encoded = LoginExceptionCodec.encode(setOf("https://b.example", "https://a.example"))
        assertEquals("""["https://a.example","https://b.example"]""", encoded)
        assertEquals(setOf("https://a.example", "https://b.example"), LoginExceptionCodec.decode(encoded))
        assertEquals(setOf("https://x.example"), LoginExceptionCodec.decode("""["https://WWW.x.example/path", ""]"""))
        assertEquals(emptySet(), LoginExceptionCodec.decode(null))
        assertEquals(emptySet(), LoginExceptionCodec.decode("   "))
        assertEquals(emptySet(), LoginExceptionCodec.decode("not json"), "a corrupt blob must not break the save prompt")
    }

    @Test
    fun `roundtrip is stable`() {
        val origins = setOf("https://a.example", "http://b.example:81", "https://c.example")
        assertEquals(origins, LoginExceptionCodec.decode(LoginExceptionCodec.encode(origins)))
    }
}
