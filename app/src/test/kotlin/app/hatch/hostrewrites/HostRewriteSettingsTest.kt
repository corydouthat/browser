package app.hatch.hostrewrites

import app.hatch.persona.HostRewrite
import app.hatch.persona.UrlRewriteRules
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostRewriteSettingsTest {
    private val all = UrlRewriteRules.extensionHostRewrites

    @Test
    fun `nothing disabled sends the full table unchanged`() {
        assertEquals(all, HostRewriteFilter.effective(all, emptySet()))
    }

    @Test
    fun `disabled hosts are absent, order of the rest is preserved`() {
        val disabled = setOf("m.youtube.com", UrlRewriteRules.GENERIC_MOBILE_HOST_RULE)
        val eff = HostRewriteFilter.effective(all, disabled)
        assertEquals(all.size - 2, eff.size)
        assertFalse(eff.any { it.from == "m.youtube.com" })
        assertFalse(eff.any { it.from == UrlRewriteRules.GENERIC_MOBILE_HOST_RULE })
        assertEquals(all.filterNot { it.from in disabled }, eff)
    }

    @Test
    fun `toggle adds and removes, and drops keys for rules that no longer exist`() {
        var d = HostRewriteFilter.toggle(all, emptySet(), "m.facebook.com", enabled = false)
        assertEquals(setOf("m.facebook.com"), d)
        d = HostRewriteFilter.toggle(all, d + "gone.example", "m.reddit.com", enabled = false)
        assertEquals(setOf("m.facebook.com", "m.reddit.com"), d, "stale key dropped")
        d = HostRewriteFilter.toggle(all, d, "m.facebook.com", enabled = true)
        assertEquals(setOf("m.reddit.com"), d)
        assertEquals(setOf("m.reddit.com"), HostRewriteFilter.toggle(all, d, "unknown.host", enabled = false), "unknown hosts are never added")
    }

    @Test
    fun `labels name the regex rules and show exact rules as from to`() {
        assertTrue(HostRewriteFilter.label(HostRewrite(UrlRewriteRules.WIKIMEDIA_HOST_RULE, UrlRewriteRules.WIKIMEDIA_HOST_TARGET)).startsWith("Wikimedia"))
        assertTrue(HostRewriteFilter.label(HostRewrite(UrlRewriteRules.GENERIC_MOBILE_HOST_RULE, UrlRewriteRules.GENERIC_MOBILE_HOST_TARGET)).startsWith("Generic"))
        assertEquals("m.youtube.com → www.youtube.com", HostRewriteFilter.label(HostRewrite("m.youtube.com", "www.youtube.com")))
        assertTrue(HostRewriteFilter.isRegex(HostRewrite(UrlRewriteRules.WIKIMEDIA_HOST_RULE, "x")))
        assertFalse(HostRewriteFilter.isRegex(HostRewrite("m.youtube.com", "x")))
    }
}
