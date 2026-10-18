package app.hatch.ui

import app.hatch.hostrewrites.HostRewriteFilter
import app.hatch.persona.UrlRewriteRules
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** ENG-04: the typed-URL path honours the per-host switches (the same filtered table the extension gets). */
class NormalizeInputTest {
    private val all = UrlRewriteRules.extensionHostRewrites

    @Test
    fun `typed mobile hosts rewrite with the full table and not with a disabled row`() {
        assertEquals("https://www.youtube.com/watch?v=x", normalizeInput("m.youtube.com/watch?v=x", desktop = true))
        assertEquals("https://www.youtube.com/watch?v=x", normalizeInput("m.youtube.com/watch?v=x", desktop = true, hostRewrites = all))
        // Settings → Host rewrites: m.youtube.com AND the generic strip off.
        val disabled = setOf("m.youtube.com", UrlRewriteRules.GENERIC_MOBILE_HOST_RULE)
        val effective = HostRewriteFilter.effective(all, disabled)
        assertEquals("https://m.youtube.com/watch?v=x", normalizeInput("m.youtube.com/watch?v=x", desktop = true, hostRewrites = effective))
        // Other rows still apply, and the query rule is table-independent.
        assertEquals("https://x.com/jack", normalizeInput("mobile.twitter.com/jack", desktop = true, hostRewrites = effective))
        assertEquals("https://m.youtube.com/watch?v=x", normalizeInput("https://m.youtube.com/watch?v=x&app=m", desktop = true, hostRewrites = effective))
    }

    @Test
    fun `no rewrite without a desktop persona and search for free text`() {
        assertEquals("https://m.youtube.com/", normalizeInput("m.youtube.com/", desktop = false))
        assertEquals("https://www.google.com/search?q=hello+world", normalizeInput("hello world", desktop = true))
        assertEquals("about:blank", normalizeInput("   ", desktop = true))
    }
}
