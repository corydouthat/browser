package app.hatch.applinks

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLinksPolicyTest {
    @Test
    fun `stay in Hatch is the default and never launches apps for web links`() {
        assertEquals(AppLinksMode.STAY_IN_HATCH, AppLinksMode.DEFAULT)
        val p = AppLinksPolicy.of(AppLinksMode.STAY_IN_HATCH)
        assertFalse(p.launchInApp)
        assertTrue(p.shouldPrompt, "non-web schemes (intent://, market:) still go through the dialog")
    }

    @Test
    fun `ask launches with a prompt, always launches without`() {
        assertEquals(AppLinksPolicy(launchInApp = true, shouldPrompt = true), AppLinksPolicy.of(AppLinksMode.ASK))
        assertEquals(AppLinksPolicy(launchInApp = true, shouldPrompt = false), AppLinksPolicy.of(AppLinksMode.ALWAYS))
    }

    @Test
    fun `stored keys round-trip and unknown keys fall back to the default`() {
        AppLinksMode.entries.forEach { assertEquals(it, AppLinksMode.fromKey(it.key)) }
        assertEquals(AppLinksMode.DEFAULT, AppLinksMode.fromKey(null))
        assertEquals(AppLinksMode.DEFAULT, AppLinksMode.fromKey("bogus"))
        assertEquals(3, AppLinksMode.entries.map { it.key }.distinct().size)
    }
}
