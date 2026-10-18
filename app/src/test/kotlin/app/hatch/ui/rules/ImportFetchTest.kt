package app.hatch.ui.rules

import mozilla.components.concept.fetch.Client
import mozilla.components.concept.fetch.MutableHeaders
import mozilla.components.concept.fetch.Request
import mozilla.components.concept.fetch.Response
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SEC-3: the import fetch never follows redirects, never sends cookies and refuses a non-https answer. */
class ImportFetchTest {
    private class FakeClient(private val answer: (Request) -> Response) : Client() {
        val requests = mutableListOf<Request>()
        override fun fetch(request: Request): Response {
            requests += request
            return answer(request)
        }
    }

    private fun response(url: String, status: Int, body: String = "// ==UserScript==\n// ==/UserScript==\n", headers: MutableHeaders = MutableHeaders()) =
        Response(url, status, headers, Response.Body(body.byteInputStream()))

    @Test
    fun `the request is anonymous and manual-redirect`() {
        val client = FakeClient { response("https://good.example/x.user.js", 200) }
        val text = fetchText(client, "https://good.example/x.user.js")
        assertTrue(text.contains("UserScript"))
        val req = client.requests.single()
        assertEquals(Request.CookiePolicy.OMIT, req.cookiePolicy)
        assertEquals(Request.Redirect.MANUAL, req.redirect)
        assertEquals(false, req.useCaches)
    }

    @Test
    fun `a 3xx is refused with the paste-the-final-URL hint`() {
        val client = FakeClient { response("https://good.example/x.user.js", 302, headers = MutableHeaders("Location" to "http://evil.example/x.user.js")) }
        val e = assertThrows<IllegalStateException> { fetchText(client, "https://good.example/x.user.js") }
        assertEquals(REDIRECT_REFUSED, e.message)
        for (status in listOf(301, 303, 307, 308)) {
            val c = FakeClient { response("https://good.example/x.user.js", status) }
            assertThrows<IllegalStateException> { fetchText(c, "https://good.example/x.user.js") }
        }
    }

    @Test
    fun `a body served from a non-https URL is refused even with status 200`() {
        val client = FakeClient { response("http://evil.example/x.user.js", 200) }
        val e = assertThrows<IllegalStateException> { fetchText(client, "https://good.example/x.user.js") }
        assertEquals(NOT_HTTPS_REFUSED, e.message)
    }

    @Test
    fun `other failures keep their status and the size cap holds`() {
        val notFound = FakeClient { response("https://good.example/x.user.js", 404) }
        assertEquals("HTTP 404", assertThrows<IllegalStateException> { fetchText(notFound, "https://good.example/x.user.js") }.message)
        val big = FakeClient { response("https://good.example/x.user.js", 200, headers = MutableHeaders("Content-Length" to "${(1L shl 20) + 1}")) }
        assertThrows<IllegalStateException> { fetchText(big, "https://good.example/x.user.js") }
    }
}
