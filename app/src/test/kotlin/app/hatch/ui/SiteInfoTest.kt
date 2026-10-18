package app.hatch.ui

import mozilla.components.browser.state.state.SecurityInfo
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SiteInfoTest {
    @Test
    fun `rows describe the connection and fall back to the url host`() {
        val secure = siteInfoRows("https://www.example.org/a", SecurityInfo.Secure(host = "www.example.org", issuer = "Let's Encrypt"))
        assertEquals("Site" to "www.example.org", secure[0])
        assertEquals("Connection" to "Secure (HTTPS, certificate valid)", secure[1])
        assertEquals("Verified by" to "Let's Encrypt", secure[2])
        assertEquals(3, secure.size)

        val insecure = siteInfoRows("http://example.org/", SecurityInfo.Insecure())
        assertEquals("Site" to "example.org", insecure[0])
        assertEquals("Connection" to "Not secure", insecure[1])
        assertEquals(2, insecure.size)

        val unknown = siteInfoRows("about:config", SecurityInfo.Unknown)
        assertEquals("Site" to "about:config", unknown[0])
        assertEquals("Connection" to "Unknown", unknown[1])
    }

    @Test
    fun `host extraction`() {
        assertEquals("example.org:8443", OpenHost.of("https://example.org:8443/x?y"))
        assertNull(OpenHost.of("about:blank"))
        assertNull(OpenHost.of("https:///"))
    }
}
