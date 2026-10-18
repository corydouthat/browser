package app.hatch.ui

import mozilla.components.browser.state.state.createTab
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ChromeLayeringTest {
    /** UXP-01: no tap catcher above the home's controls; one above the web content while editing. */
    @Test
    fun `the edit-mode tap catcher never covers the home`() {
        assertTrue(ChromeLayering.editTapCatcherOverContent(editing = true, isHome = false))
        assertFalse(ChromeLayering.editTapCatcherOverContent(editing = true, isHome = true))
        assertFalse(ChromeLayering.editTapCatcherOverContent(editing = false, isHome = false))
        assertFalse(ChromeLayering.editTapCatcherOverContent(editing = false, isHome = true))
    }

    /** PERF-04: progress ticks must not change the snapshot the chrome observes. */
    @Test
    fun `selected tab facts ignore progress and keep what the chrome shows`() {
        val base = createTab("https://x.org/", id = "t", title = "X")
        val p10 = base.copy(content = base.content.copy(progress = 10, loading = true))
        val p90 = base.copy(content = base.content.copy(progress = 90, loading = true))
        assertEquals(SelectedTabFacts.of(p10), SelectedTabFacts.of(p90))
        assertNotEquals(SelectedTabFacts.of(base), SelectedTabFacts.of(p10), "loading flips are visible")
        val navigated = base.copy(content = base.content.copy(url = "https://x.org/b", canGoBack = true))
        assertNotEquals(SelectedTabFacts.of(base), SelectedTabFacts.of(navigated))
        val facts = SelectedTabFacts.of(navigated)
        assertEquals("t", facts.id)
        assertEquals("https://x.org/b", facts.url)
        assertTrue(facts.canGoBack)
        assertFalse(facts.crashed)
        assertFalse(facts.fullScreen)
    }
}
