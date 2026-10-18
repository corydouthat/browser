package app.hatch.ui.find

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FindBarPolicyTest {
    private fun a(tab: String?, url: String?) = FindBarPolicy.Anchor(tab, url)

    @Test
    fun `same tab and a fragment-only change keep the bar`() {
        assertFalse(FindBarPolicy.shouldClose(a("t", "https://x.org/p"), a("t", "https://x.org/p")))
        assertFalse(FindBarPolicy.shouldClose(a("t", "https://x.org/p"), a("t", "https://x.org/p#section")))
        assertFalse(FindBarPolicy.shouldClose(a("t", "https://x.org/p#a"), a("t", "https://x.org/p#b")))
        assertFalse(FindBarPolicy.shouldClose(a("t", null), a("t", null)))
    }

    @Test
    fun `a navigation to another document closes the bar`() {
        assertTrue(FindBarPolicy.shouldClose(a("t", "https://x.org/p"), a("t", "https://x.org/q")))
        assertTrue(FindBarPolicy.shouldClose(a("t", "https://x.org/p?a=1"), a("t", "https://x.org/p?a=2")))
        assertTrue(FindBarPolicy.shouldClose(a("t", "https://x.org/p"), a("t", null)))
    }

    @Test
    fun `a tab switch closes the bar even to the same url`() {
        assertTrue(FindBarPolicy.shouldClose(a("t1", "https://x.org/p"), a("t2", "https://x.org/p")))
        assertTrue(FindBarPolicy.shouldClose(a("t1", "https://x.org/p"), a(null, null)))
    }
}
