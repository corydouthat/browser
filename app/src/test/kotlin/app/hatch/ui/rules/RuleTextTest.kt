package app.hatch.ui.rules

import app.hatch.rules.RuleValidation
import app.hatch.rules.SiteRule
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RuleTextTest {
    private fun rule(js: String? = null, css: String? = null, source: String? = null, matches: List<String> = listOf("*://*/*"), enabled: Boolean = false) =
        SiteRule("r", "n", matches, js = js, css = css, source = source, enabled = enabled)

    @Test
    fun `kind line names what the rule injects and tags the bundled examples`() {
        assertEquals("JS", RuleText.kindLine(rule(js = "x")))
        assertEquals("CSS", RuleText.kindLine(rule(css = "a{}")))
        assertEquals("JS + CSS", RuleText.kindLine(rule(js = "x", css = "a{}")))
        assertEquals("CSS · Example", RuleText.kindLine(rule(css = "a{}", source = RuleValidation.BUNDLED_SOURCE)))
        assertEquals("Empty", RuleText.kindLine(rule(js = " ")))
    }

    @Test
    fun `patterns, labels and the settings summary`() {
        assertEquals("*://docs.google.com/*, *://*.google.com/*", RuleText.patternLine(rule(matches = listOf("*://docs.google.com/*", "*://*.google.com/*"))))
        assertEquals("No match pattern", RuleText.patternLine(rule(matches = emptyList())))
        assertEquals("document-start", RuleText.runAtLabel("document_start"))
        assertEquals("Page", RuleText.worldLabel("MAIN"))
        assertEquals("Isolated", RuleText.worldLabel("ISOLATED"))
        assertEquals("2 of 4 on", RuleText.enabledSummary(listOf(rule(enabled = true), rule(enabled = true), rule(), rule())))
    }

    @Test
    fun `editing the pattern chips`() {
        val p = listOf("a", "b")
        assertEquals(listOf("a", "b", "c"), RuleText.editPattern(p, 2, " c "))
        assertEquals(listOf("a", "b"), RuleText.editPattern(p, 2, "  "), "a blank new chip adds nothing")
        assertEquals(listOf("a", "B"), RuleText.editPattern(p, 1, "B"))
        assertEquals(listOf("b"), RuleText.editPattern(p, 0, ""), "a blank edit removes the chip")
    }
}
