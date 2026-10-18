package app.hatch.rules

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleValidationTest {
    private val good = SiteRule(id = "r1", name = "Test", matches = listOf("*://*.example.com/*"), css = "body{}", world = "ISOLATED", runAt = "document_start")

    @Test
    fun `match pattern grammar`() {
        listOf(
            "<all_urls>", "*://*/*", "*://*.example.com/*", "https://example.com/", "http://example.com/path/*",
            "file:///home/*", "wss://*.chat.example/*", "ftp://ftp.example.org/*", "*://example.com/a?b=c",
        ).forEach { assertTrue(RuleValidation.isValidMatchPattern(it), it) }
        listOf(
            "", "example.com", "https://example.com", "https://*example.com/*", "https://example.com:8080/*",
            "moz-extension://abc/*", "https:///", "*://*.example.com", "https://user@example.com/*", "https://ex ample.com/*",
            "data:*", "all_urls", "https://-bad.com/*",
        ).forEach { assertFalse(RuleValidation.isValidMatchPattern(it), it) }
    }

    @Test
    fun `a complete rule has no problems`() {
        assertEquals(emptyList(), RuleValidation.problems(good))
        assertTrue(RuleValidation.isValid(good.copy(js = "void 0", css = null, world = "MAIN", runAt = "document_idle")))
    }

    @Test
    fun `every defect is named`() {
        val bad = SiteRule(id = "", name = " ", matches = listOf("nope"), excludeMatches = listOf("also nope"), js = null, css = " ", world = "PAGE", runAt = "later")
        val p = RuleValidation.problems(bad)
        assertTrue(p.any { it.contains("no id") })
        assertTrue(p.any { it.contains("Name is empty") })
        assertTrue(p.any { it == "Invalid match pattern: nope" })
        assertTrue(p.any { it == "Invalid exclude pattern: also nope" })
        assertTrue(p.any { it.contains("needs JavaScript or CSS") })
        assertTrue(p.any { it.contains("World must be") })
        assertTrue(p.any { it.contains("Run at must be") })
        assertTrue(RuleValidation.problems(good.copy(matches = emptyList())).any { it.contains("At least one match pattern") })
    }

    @Test
    fun `normalize trims and drops blanks, splitPatterns accepts lines and commas`() {
        val n = RuleValidation.normalize(good.copy(name = "  X ", matches = listOf(" *://a/* ", "", "  "), excludeMatches = listOf(" ", "*://b/*"), js = "  ", css = " .x{} "))
        assertEquals("X", n.name)
        assertEquals(listOf("*://a/*"), n.matches)
        assertEquals(listOf("*://b/*"), n.excludeMatches)
        assertEquals(null, n.js)
        assertEquals(" .x{} ", n.css)
        assertEquals(listOf("*://a/*", "*://b/*", "*://c/*"), RuleValidation.splitPatterns("*://a/*, *://b/*\n\n *://c/* \n"))
    }

    @Test
    fun `bundled example rules are recognised by their source tag`() {
        assertTrue(RuleValidation.isBundled(good.copy(source = "hatch-bundled")))
        assertFalse(RuleValidation.isBundled(good.copy(source = "https://example.com/x.user.js")))
        assertFalse(RuleValidation.isBundled(good))
    }
}
