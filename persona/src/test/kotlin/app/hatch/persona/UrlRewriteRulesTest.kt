package app.hatch.persona

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UrlRewriteRulesTest {
    @Test
    fun `rewrite table`() {
        val table = listOf(
            // exact host map
            "https://m.youtube.com/watch?v=abc" to "https://www.youtube.com/watch?v=abc",
            "https://m.youtube.com/watch?v=abc&app=m" to "https://www.youtube.com/watch?v=abc",
            "https://mobile.twitter.com/jack/status/20" to "https://x.com/jack/status/20",
            "https://m.facebook.com/groups/1?ref=bookmarks#top" to "https://www.facebook.com/groups/1?ref=bookmarks#top",
            "http://touch.facebook.com/" to "http://www.facebook.com/",
            // wikimedia
            "https://en.m.wikipedia.org/wiki/Main_Page" to "https://en.wikipedia.org/wiki/Main_Page",
            "https://de.m.wikipedia.org/wiki/Berlin?mf_useformat=true" to "https://de.wikipedia.org/wiki/Berlin",
            "https://en.m.wiktionary.org/wiki/hatch" to "https://en.wiktionary.org/wiki/hatch",
            "https://EN.M.WIKIPEDIA.ORG/wiki/X" to "https://en.wikipedia.org/wiki/X",
            // generic prefix strip (one label, >= 2 labels remain)
            "https://m.example.com/path" to "https://example.com/path",
            "https://mobile.example.co.uk/a/b?c=d" to "https://example.co.uk/a/b?c=d",
            "https://touch.example.org" to "https://example.org",
            "https://user:pw@m.example.com:8443/x" to "https://user:pw@example.com:8443/x",
            // amazon mobile paths
            "https://www.amazon.com/gp/aw/d/B01N5IB20Q/ref=x" to "https://www.amazon.com/dp/B01N5IB20Q",
            "https://www.amazon.co.uk/gp/aw/s/?k=hatch" to "https://www.amazon.co.uk/?k=hatch",
            "https://amazon.de/gp/aw" to "https://amazon.de/",
            // params only
            "https://en.wikipedia.org/wiki/X?mf_useformat=true" to "https://en.wikipedia.org/wiki/X",
            "https://en.wikipedia.org/wiki/X?a=1&mf_useformat=true&b=2" to "https://en.wikipedia.org/wiki/X?a=1&b=2",
            "https://www.youtube.com/?app=m" to "https://www.youtube.com/",
        )
        for ((input, expected) in table) {
            assertEquals(expected, UrlRewriteRules.desktopUrl(input), "input=$input")
        }
    }

    @Test
    fun `no-change cases return null`() {
        val unchanged = listOf(
            "https://www.youtube.com/watch?v=abc",
            "https://x.com/home",
            "https://en.wikipedia.org/wiki/Main_Page",
            "https://example.com/",
            "https://m.me",                         // only one label would remain
            "https://www.amazon.com/dp/B01N5IB20Q",
            "https://www.youtube.com/?app=desktop",  // app=desktop is not a mobile marker
            "https://example.com/?mf_useformat",     // key present but it is the only param -> stripped? no: see below
            "ftp://m.example.com/file",
            "about:blank",
            "not a url",
            "",
        ).filter { it != "https://example.com/?mf_useformat" }
        for (u in unchanged) {
            assertNull(UrlRewriteRules.desktopUrl(u), "input=$u")
        }
        // a bare mf_useformat key (no value) is still the mobile-format marker and gets dropped
        assertEquals("https://example.com/", UrlRewriteRules.desktopUrl("https://example.com/?mf_useformat"))
    }

    @Test
    fun `host-only helper and default host table`() {
        assertEquals("www.youtube.com", UrlRewriteRules.desktopHost("m.youtube.com"))
        assertEquals("x.com", UrlRewriteRules.desktopHost("mobile.twitter.com"))
        assertEquals("en.wikipedia.org", UrlRewriteRules.desktopHost("en.m.wikipedia.org"))
        assertEquals("example.com", UrlRewriteRules.desktopHost("m.example.com"))
        assertNull(UrlRewriteRules.desktopHost("www.example.com"))
        assertNull(UrlRewriteRules.desktopHost("m.me"))
        val rewrites = UrlRewriteRules.defaultHostRewrites
        assertTrue(rewrites.any { it.from == "m.youtube.com" && it.to == "www.youtube.com" })
        assertTrue(rewrites.any { it.from == "mobile.twitter.com" && it.to == "x.com" })
        assertTrue(rewrites.any { it.from == "m.facebook.com" && it.to == "www.facebook.com" })
        assertEquals(rewrites.size, rewrites.map { it.from }.toSet().size, "from hosts must be unique")
        for (r in rewrites) {
            assertTrue(r.from != r.to)
            assertTrue(r.from == r.from.lowercase() && r.to == r.to.lowercase())
            assertNull(UrlRewriteRules.desktopHost(r.to), "target ${r.to} must be stable (no loops)")
        }
    }

    /**
     * HATCH-WC-01: the table sent to the extension (exact hosts + the two JS regex rules) must agree with
     * [UrlRewriteRules.desktopHost] on every host, so followed links and typed URLs rewrite identically.
     * The JS side runs the same strings (`background.js normalizeHostRewrites`; mirrored in
     * `app/src/main/assets/extension/test/hostrewrite-gaps.test.mjs`).
     */
    @Test
    fun `extension table agrees with desktopHost on a host corpus`() {
        val ext = UrlRewriteRules.extensionHostRewrites
        assertEquals(UrlRewriteRules.defaultHostRewrites.size + 2, ext.size)
        assertEquals(UrlRewriteRules.WIKIMEDIA_HOST_RULE, ext[ext.size - 2].from)
        assertEquals("$1.$2.org", ext[ext.size - 2].to)
        assertEquals(UrlRewriteRules.GENERIC_MOBILE_HOST_RULE, ext[ext.size - 1].from)
        assertEquals("$1", ext[ext.size - 1].to)
        assertEquals(
            "/^([a-z-]+)\\.m\\.(wikipedia|wiktionary|wikimedia|wikibooks|wikiquote|wikinews|wikisource|wikiversity|wikivoyage|mediawiki|wikidata)\\.org$/",
            UrlRewriteRules.WIKIMEDIA_HOST_RULE,
        )
        assertEquals("/^(?:m|mobile|touch)\\.([^.]+\\.[^.]+.*)$/", UrlRewriteRules.GENERIC_MOBILE_HOST_RULE)
        val corpus = listOf(
            "en.m.wikipedia.org", "de.m.wikipedia.org", "en.m.wiktionary.org", "zh-yue.m.wikipedia.org", "m.wikidata.org",
            "commons.m.wikimedia.org", "en.wikipedia.org", "m.wikihow.com", "mobile.example.org", "touch.dailymotion.com",
            "m.youtube.com", "mobile.twitter.com", "mbasic.facebook.com", "www.youtube.com", "x.com", "m.me",
            "www.mobile.de", "mobile.de", "example.com", "m.example.co.uk", "M.EXAMPLE.COM", "m.m.example.com",
            "touch.facebook.com", "amazon.com", "mail.google.com",
        )
        for (host in corpus) {
            assertEquals(UrlRewriteRules.desktopHost(host), UrlRewriteRules.extensionRewrite(host), "host=$host")
        }
        // and the spec examples from the device checklist
        assertEquals("en.wikipedia.org", UrlRewriteRules.extensionRewrite("en.m.wikipedia.org"))
        assertEquals("wikihow.com", UrlRewriteRules.extensionRewrite("m.wikihow.com"))
        assertNull(UrlRewriteRules.extensionRewrite("m.me"))
        assertNull(UrlRewriteRules.extensionRewrite("www.mobile.de"))
    }

    /**
     * ENG-04: a host switched off in Settings (`HostRewriteFilter.effective` drops it from the table) must not be
     * rewritten on the typed/pasted path either; the rest of the table, the Amazon path rule and the query rules
     * keep applying.
     */
    @Test
    fun `desktopUrl honours a filtered host table`() {
        val all = UrlRewriteRules.extensionHostRewrites
        val withoutYoutube = all.filterNot { it.from == "m.youtube.com" }
        // Only the exact row is gone: the generic `m.` strip (last in the table) still catches the host, on this path
        // exactly as in the extension's onBeforeRequest. Keeping the mobile host needs the generic rule off as well
        // (Settings says so).
        assertEquals("youtube.com", UrlRewriteRules.desktopHost("m.youtube.com", withoutYoutube))
        val withoutYoutubeAndGeneric = withoutYoutube.filterNot { it.from == UrlRewriteRules.GENERIC_MOBILE_HOST_RULE }
        assertNull(UrlRewriteRules.desktopHost("m.youtube.com", withoutYoutubeAndGeneric))
        assertNull(UrlRewriteRules.desktopUrl("https://m.youtube.com/watch?v=x", withoutYoutubeAndGeneric))
        // The query rule still applies on its own.
        assertEquals("https://m.youtube.com/watch?v=x", UrlRewriteRules.desktopUrl("https://m.youtube.com/watch?v=x&app=m", withoutYoutubeAndGeneric))
        // Other rows and the Wikimedia regex rule still work through the generic interpreter.
        assertEquals("x.com", UrlRewriteRules.desktopHost("mobile.twitter.com", withoutYoutubeAndGeneric))
        assertEquals("en.wikipedia.org", UrlRewriteRules.desktopHost("en.m.wikipedia.org", withoutYoutubeAndGeneric))
        assertNull(UrlRewriteRules.desktopHost("m.example.com", withoutYoutubeAndGeneric), "generic strip is off")
        assertEquals("example.com", UrlRewriteRules.desktopHost("m.example.com", withoutYoutube), "generic strip still on")
        // An empty table rewrites no host at all, but the Amazon path rule is table-independent.
        assertNull(UrlRewriteRules.desktopUrl("https://m.facebook.com/", emptyList()))
        assertEquals("https://www.amazon.com/dp/B01N5IB20Q", UrlRewriteRules.desktopUrl("https://www.amazon.com/gp/aw/d/B01N5IB20Q", emptyList()))
        // The full table through both paths agrees.
        for (host in listOf("m.youtube.com", "en.m.wikipedia.org", "m.example.com", "www.example.com")) {
            assertEquals(UrlRewriteRules.desktopHost(host), UrlRewriteRules.rewriteWith(host, all), host)
        }
    }
}
