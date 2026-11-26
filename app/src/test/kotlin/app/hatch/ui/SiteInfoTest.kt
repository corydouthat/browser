package app.hatch.ui

import app.hatch.ui.toolbar.ToolbarState
import mozilla.components.browser.state.state.SecurityInfo
import org.junit.jupiter.api.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SiteInfoTest {
    /** Self-signed test certificate: subject O="Example, Inc." (escaped comma), CN=secure.example.org, notAfter 2027-03-14 12:00 UTC. */
    private val pem = """
-----BEGIN CERTIFICATE-----
MIIBvjCCAWWgAwIBAgIUcE5QYJoiIhKjWn9qWoFBEBF+j7owCgYIKoZIzj0EAwIw
NTEbMBkGA1UEAwwSc2VjdXJlLmV4YW1wbGUub3JnMRYwFAYDVQQKDA1FeGFtcGxl
LCBJbmMuMB4XDTI2MDEwMTAwMDAwMFoXDTI3MDMxNDEyMDAwMFowNTEbMBkGA1UE
AwwSc2VjdXJlLmV4YW1wbGUub3JnMRYwFAYDVQQKDA1FeGFtcGxlLCBJbmMuMFkw
EwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEGd2XJVi5uv9yCkBj/NXEA9RubB+aTrJo
G0cnUS4clE3W5F0hhgm/SfindfFWekonE6Hb8R/JaofZd3JrKpfn+qNTMFEwHQYD
VR0OBBYEFA4erO6snCF96P3NokzX1XToCX9qMB8GA1UdIwQYMBaAFA4erO6snCF9
6P3NokzX1XToCX9qMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDRwAwRAIg
WVDcFf9l1xyxDrW1yv65YAlLXqyoM3wzcYiq0Q2QjwoCIHMjTvBRohFBMbNIZYTd
rhyXOyj0yjZWC1JxBzBJinTA
-----END CERTIFICATE-----
    """.trimIndent()

    private fun cert(): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream()) as X509Certificate

    @Test
    fun `summary names the host and the connection like the Site info mockup`() {
        val secure = siteInfoSummary("https://www.example.org/a", SecurityInfo.Secure(host = "www.example.org", issuer = "Let's Encrypt"))
        assertEquals("www.example.org", secure.host)
        assertEquals(ToolbarState.SecurityBadge.Secure, secure.badge)
        assertEquals("Connection is secure", secure.connection)

        val insecure = siteInfoSummary("http://example.org/", SecurityInfo.Insecure())
        assertEquals("example.org", insecure.host, "falls back to the URL's host")
        assertEquals("Connection is not secure", insecure.connection)

        val unknown = siteInfoSummary("about:config", SecurityInfo.Unknown)
        assertEquals("about:config", unknown.host)
        assertEquals("No connection details", unknown.connection)
    }

    @Test
    fun `certificate rows are Issued by, Issued to and Expires`() {
        assertEquals(listOf("Issued by" to "Let's Encrypt"), siteInfoRows(SecurityInfo.Secure(host = "a.org", issuer = "Let's Encrypt")))
        assertTrue(siteInfoRows(SecurityInfo.Insecure()).isEmpty())
        assertTrue(siteInfoRows(SecurityInfo.Unknown).isEmpty())

        val rows = siteInfoRows(SecurityInfo.Secure(host = "secure.example.org", issuer = "Example CA", certificate = cert()))
        assertEquals(
            listOf("Issued by" to "Example CA", "Issued to" to "Example, Inc.", "Expires" to "2027-03-14"),
            rows,
        )
        // No CA name from the engine: the certificate's own issuer (self-signed here, so the subject's O).
        assertEquals("Example, Inc.", siteInfoRows(SecurityInfo.Secure(certificate = cert())).first().second)
    }

    @Test
    fun `expiry is a UTC date`() {
        assertEquals("1970-01-01", expiryDate(Date(0)))
        assertEquals("2027-03-14", expiryDate(Date(1_805_068_799_000L)))
    }

    @Test
    fun `distinguished names are parsed with RFC 2253 escapes and quotes`() {
        assertEquals("JPMorgan Chase and Co.", X500Names.attribute("CN=secure.chase.com,O=JPMorgan Chase and Co.,L=New York,C=US", "O"))
        assertEquals("secure.chase.com", X500Names.attribute("CN=secure.chase.com,O=JPMorgan Chase and Co.", "cn"))
        assertEquals("Example, Inc.", X500Names.attribute("O=Example\\, Inc.,CN=x", "O"))
        assertEquals("A, B", X500Names.attribute("O=\"A, B\",CN=x", "O"))
        assertEquals("é", X500Names.attribute("O=\\C3\\A9", "O"))
        assertEquals("b", X500Names.attribute("CN=a+O=b", "O"), "multi-valued RDN")
        assertNull(X500Names.attribute("CN=x", "O"))
        assertNull(X500Names.attribute("O=,CN=x", "O"), "blank values are absent")
    }

    @Test
    fun `host extraction`() {
        assertEquals("example.org:8443", OpenHost.of("https://example.org:8443/x?y"))
        assertNull(OpenHost.of("about:blank"))
        assertNull(OpenHost.of("https:///"))
    }
}
