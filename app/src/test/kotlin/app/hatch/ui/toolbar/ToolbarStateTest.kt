package app.hatch.ui.toolbar

import kotlinx.coroutines.runBlocking
import mozilla.components.browser.state.state.SecurityInfo
import mozilla.components.compose.browser.toolbar.concept.Action.ActionButtonRes
import mozilla.components.ui.icons.R as iconsR
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolbarStateTest {
    @Test
    fun `security badge follows Fenix 158 mapping`() {
        assertEquals(ToolbarState.SecurityBadge.Unknown, ToolbarState.securityBadge(null))
        assertEquals(ToolbarState.SecurityBadge.Unknown, ToolbarState.securityBadge(SecurityInfo.Unknown))
        assertEquals(ToolbarState.SecurityBadge.Secure, ToolbarState.securityBadge(SecurityInfo.Secure("example.org", "Let's Encrypt")))
        assertEquals(ToolbarState.SecurityBadge.Insecure, ToolbarState.securityBadge(SecurityInfo.Insecure("example.org")))
        assertEquals(ToolbarState.SecurityBadge.Secure, ToolbarState.securityBadge(SecurityInfo.from(isSecure = true, host = "a")))
    }

    @Test
    fun `display url strips scheme and www, keeps paths`() {
        assertEquals("", ToolbarState.displayUrl(null))
        assertEquals("", ToolbarState.displayUrl(""))
        assertEquals("", ToolbarState.displayUrl("about:blank"))
        assertEquals("example.org", ToolbarState.displayUrl("https://www.example.org/"))
        assertEquals("example.org", ToolbarState.displayUrl("http://example.org"))
        assertEquals("example.org/a/b?c=1", ToolbarState.displayUrl("https://example.org/a/b?c=1"))
        assertEquals("example.org/x", ToolbarState.displayUrl("https://www.example.org/x"))
        assertEquals("about:config", ToolbarState.displayUrl("about:config"))
        assertEquals("localhost:8080/t", ToolbarState.displayUrl("http://localhost:8080/t"))
        assertEquals("wwwx.example.org", ToolbarState.displayUrl("https://wwwx.example.org/"))
    }

    @Test
    fun `home is about blank with no history`() {
        assertTrue(ToolbarState.isHome("about:blank", canGoBack = false))
        assertFalse(ToolbarState.isHome("about:blank", canGoBack = true))
        assertFalse(ToolbarState.isHome("https://example.org/", canGoBack = false))
        assertFalse(ToolbarState.isHome(null, canGoBack = false))
    }

    @Test
    fun `progress bar only while loading, clamped`() {
        assertNull(ToolbarState.progress(loading = false, progress = 50))
        assertEquals(50, ToolbarState.progress(loading = true, progress = 50))
        assertEquals(100, ToolbarState.progress(loading = true, progress = 180))
        assertEquals(0, ToolbarState.progress(loading = true, progress = -3))
    }

    @Test
    fun `persona label`() {
        assertEquals("Windows · Firefox · US East", ToolbarState.personaLabel("Windows · Firefox", "US East"))
    }

    @Test
    fun `open tabs autocomplete matches hosts with and without www`() {
        val urls = listOf("https://www.github.com/corydouthat/browser", "http://example.org/x", "about:blank", "https://mail.google.com/")
        val r = OpenTabsAutocompleteProvider.suggest("git", urls)!!
        assertEquals("github.com", r.text)
        assertEquals("https://www.github.com/corydouthat/browser", r.url)
        assertEquals(OpenTabsAutocompleteProvider.SOURCE, r.source)
        assertEquals(4, r.totalItems)
        assertEquals("www.github.com", OpenTabsAutocompleteProvider.suggest("www.gi", urls)!!.text)
        assertEquals("Example.org", OpenTabsAutocompleteProvider.suggest("Exa", urls)!!.text)
        assertEquals("mail.google.com", OpenTabsAutocompleteProvider.suggest("mail", urls)!!.text)
        assertNull(OpenTabsAutocompleteProvider.suggest("", urls))
        assertNull(OpenTabsAutocompleteProvider.suggest("zzz", urls))
        assertNull(OpenTabsAutocompleteProvider.suggest("about", urls))
    }

    @Test
    fun `provider delegates to the current tab list`() = runBlocking {
        var urls = listOf("https://news.ycombinator.com/")
        val p = OpenTabsAutocompleteProvider({ urls })
        assertEquals("news.ycombinator.com", p.getAutocompleteSuggestion("news")!!.text)
        urls = emptyList()
        assertNull(p.getAutocompleteSuggestion("news"))
        assertEquals(0, p.autocompletePriority)
    }

    @Test
    fun `host extraction`() {
        assertEquals("example.org", OpenTabsAutocompleteProvider.hostOf("https://example.org:8443/a"))
        assertEquals("example.org", OpenTabsAutocompleteProvider.hostOf("http://EXAMPLE.org?x"))
        assertNull(OpenTabsAutocompleteProvider.hostOf("about:blank"))
        assertNull(OpenTabsAutocompleteProvider.hostOf("moz-extension://abc/x"))
        assertNull(OpenTabsAutocompleteProvider.hostOf("https://user@host/"))
    }

    /** UXP-11: a clear button while there is text in the address bar, nothing otherwise. */
    @Test
    fun `edit mode shows a clear action only for a non-empty query`() {
        assertTrue(ToolbarState.editEndActions("").isEmpty())
        val actions = ToolbarState.editEndActions("git")
        assertEquals(1, actions.size)
        val clear = actions.single() as ActionButtonRes
        assertEquals(iconsR.drawable.mozac_ic_cross_circle_fill_24, clear.drawableResId)
        assertEquals(ToolbarEvents.ClearClicked, clear.onClick)
    }
}
