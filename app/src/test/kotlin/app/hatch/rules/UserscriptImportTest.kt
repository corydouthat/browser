package app.hatch.rules

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserscriptImportTest {
    private fun script(vararg header: String, body: String = "(function(){ 'use strict'; })();"): String =
        "// ==UserScript==\n" + header.joinToString("") { "// $it\n" } + "// ==/UserScript==\n" + body

    @Test
    fun `a clean Tampermonkey script becomes a disabled ISOLATED rule with its header values`() {
        val src = script("@name  YouTube fixer", "@match https://www.youtube.com/*", "@include *://m.youtube.com/*", "@exclude https://www.youtube.com/embed/*", "@grant GM_addStyle", "@grant GM_getValue", "@run-at document-start")
        val r = UserscriptImport.fromText(src, "https://example.org/scripts/yt.user.js")
        assertTrue(r.ok, r.problems.toString())
        assertEquals(UserscriptImport.Kind.USER_JS, r.kind)
        val rule = assertNotNull(r.rule)
        assertEquals("YouTube fixer", rule.name)
        assertEquals(listOf("https://www.youtube.com/*", "*://m.youtube.com/*"), rule.matches)
        assertEquals(listOf("https://www.youtube.com/embed/*"), rule.excludeMatches)
        assertEquals("document_start", rule.runAt)
        assertEquals("ISOLATED", rule.world)
        assertFalse(rule.enabled)
        assertEquals(src, rule.js)
        assertNull(rule.css)
        assertEquals("https://example.org/scripts/yt.user.js", rule.source)
        assertTrue(rule.id.startsWith("rule-"))
        assertEquals("YouTube fixer", r.meta?.name)
    }

    @Test
    fun `require is refused with a clear message`() {
        val r = UserscriptImport.fromText(script("@name x", "@match *://*/*", "@require https://cdn.example.com/jquery.js"), null)
        assertNull(r.rule)
        assertTrue(r.problems.single().startsWith(UserscriptImport.REQUIRE_REFUSED))
        assertTrue(r.problems.single().contains("jquery.js"))
    }

    @Test
    fun `GM_xmlhttpRequest is refused whether granted or merely used`() {
        val granted = UserscriptImport.fromText(script("@name x", "@match *://*/*", "@grant GM_xmlhttpRequest"), null)
        assertEquals(listOf(UserscriptImport.XHR_REFUSED), granted.problems)
        val used = UserscriptImport.fromText(script("@name x", "@match *://*/*", "@grant none", body = "GM_xmlhttpRequest({url:'https://a'});"), null)
        assertEquals(listOf(UserscriptImport.XHR_REFUSED), used.problems)
        val promise = UserscriptImport.fromText(script("@name x", "@match *://*/*", body = "GM.xmlHttpRequest({});"), null)
        assertEquals(listOf(UserscriptImport.XHR_REFUSED), promise.problems)
        val lookalike = UserscriptImport.fromText(script("@name x", "@match *://*/*", body = "var myGM_xmlhttpRequestLogger = 1;"), null)
        assertTrue(lookalike.ok, "identifier boundaries respected")
    }

    @Test
    fun `grants outside the GM subset are refused, the subset passes`() {
        val bad = UserscriptImport.fromText(script("@name x", "@match *://*/*", "@grant GM_openInTab"), null)
        assertTrue(bad.problems.single().startsWith("Unsupported @grant GM_openInTab"))
        assertTrue(bad.problems.single().contains("GM_getValue"))
        val ok = UserscriptImport.fromText(script("@name x", "@match *://*/*", "@grant GM_setValue", "@grant GM.getValue", "@grant unsafeWindow", "@grant GM_registerMenuCommand"), null)
        assertTrue(ok.ok, ok.problems.toString())
    }

    @Test
    fun `missing header or matches is refused, bad includes become warnings`() {
        val noHeader = UserscriptImport.fromText("console.log(1)", null)
        assertEquals(listOf(UserscriptImport.NO_HEADER), noHeader.problems)
        val noMatch = UserscriptImport.fromText(script("@name x", "@include /^https?://.*\\.example\\.com/", "@include http://*example.com/*"), null)
        assertEquals(listOf(UserscriptImport.NO_MATCHES), noMatch.problems)
        assertTrue(noMatch.warnings.any { it.startsWith("Ignored regex @include") })
        assertTrue(noMatch.warnings.any { it.startsWith("Ignored glob @include") })
        val badRunAt = UserscriptImport.fromText(script("@name x", "@match *://*/*", "@run-at context-menu", "@exclude nope"), null)
        assertTrue(badRunAt.ok)
        assertEquals("document_idle", badRunAt.rule?.runAt)
        assertTrue(badRunAt.warnings.any { it.startsWith("Unknown @run-at") })
        assertTrue(badRunAt.warnings.any { it.startsWith("Ignored @exclude nope") })
        assertEquals("clipboard", badRunAt.rule?.source)
    }

    @Test
    fun `run-at mapping`() {
        assertEquals("document_start", UserscriptImport.runAtOf("document-start"))
        assertEquals("document_end", UserscriptImport.runAtOf(" document_end "))
        assertEquals("document_idle", UserscriptImport.runAtOf("document-idle"))
        assertEquals("document_idle", UserscriptImport.runAtOf(null))
        assertEquals("document_idle", UserscriptImport.runAtOf("document-body"))
    }

    @Test
    fun `user css with a UserStyle header and moz-document conditions`() {
        val css = """
            /* ==UserStyle==
            @name         Wide GitHub
            @version      1.0
            @namespace    example
            ==/UserStyle== */
            @-moz-document domain("github.com"), url-prefix("https://gist.github.com/") {
              .container-xl { max-width: 96vw !important; }
            }
        """.trimIndent()
        val r = UserscriptImport.fromText(css, "https://example.org/wide.user.css")
        assertTrue(r.ok, r.problems.toString())
        assertEquals(UserscriptImport.Kind.USER_CSS, r.kind)
        val rule = assertNotNull(r.rule)
        assertEquals("Wide GitHub", rule.name)
        assertEquals(listOf("*://github.com/*", "*://*.github.com/*", "https://gist.github.com/*"), rule.matches)
        assertEquals(css, rule.css)
        assertNull(rule.js)
        assertEquals("document_start", rule.runAt)
        assertTrue(r.warnings.isEmpty(), r.warnings.toString())
    }

    @Test
    fun `plain css without conditions applies everywhere with a warning, regexp conditions widen`() {
        val plain = UserscriptImport.fromText("body { color: red }", "https://example.org/x.user.css")
        assertTrue(plain.ok)
        assertEquals(listOf("*://*/*"), plain.rule?.matches)
        assertTrue(plain.warnings.any { it.contains("No ==UserStyle== header") })
        assertTrue(plain.warnings.any { it.contains("applies to every site") })
        val rx = UserscriptImport.fromText("/* ==UserStyle==\n@name R\n==/UserStyle== */\n@-moz-document regexp(\"https://.*\\\\.example\\\\.com/.*\"), domain(\"a.com\") { b{} }", null)
        assertEquals(listOf("*://*/*"), rx.rule?.matches)
        assertTrue(rx.warnings.any { it.startsWith("regexp()") })
        assertEquals("R", rx.rule?.name)
    }

    @Test
    fun `kind detection by url suffix then by header`() {
        assertEquals(UserscriptImport.Kind.USER_CSS, UserscriptImport.kindOf("x", "https://h/a.user.css?v=1"))
        assertEquals(UserscriptImport.Kind.USER_JS, UserscriptImport.kindOf("/* ==UserStyle== */", "https://h/a.user.js"))
        assertEquals(UserscriptImport.Kind.USER_CSS, UserscriptImport.kindOf("/* ==UserStyle==\n@name a\n==/UserStyle== */", null))
        assertEquals(UserscriptImport.Kind.USER_JS, UserscriptImport.kindOf("// ==UserScript==\n// @name a\n// ==/UserScript==", null))
    }

    @Test
    fun `import url gate`() {
        assertTrue(UserscriptImport.isAllowedUrl("https://greasyfork.org/scripts/1.user.js"))
        assertFalse(UserscriptImport.isAllowedUrl("http://greasyfork.org/scripts/1.user.js"))
        assertFalse(UserscriptImport.isAllowedUrl("https:///x.user.js"))
        assertFalse(UserscriptImport.isAllowedUrl("file:///sdcard/x.user.js"))
        assertFalse(UserscriptImport.isAllowedUrl(""))
    }
}
