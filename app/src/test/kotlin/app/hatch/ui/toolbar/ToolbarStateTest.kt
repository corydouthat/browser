package app.hatch.ui.toolbar

import androidx.compose.ui.graphics.Color
import app.hatch.ui.theme.HatchColors
import app.hatch.ui.theme.HatchPalette
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
        assertNull(OpenTabsAutocompleteProvider.hostOf("https://accounts.google.com:x@evil.com/"), "userinfo URLs are never completions")
    }

    /** RS-03: a userinfo URL shows its real host while it loads (before Gecko commits the userinfo-free location). */
    @Test
    fun `display host is the real host for userinfo, backslash and IPv6 URLs`() {
        assertEquals("evil.com", ToolbarState.displayHost("https://accounts.google.com:x@evil.com/"))
        assertEquals("evil.com", ToolbarState.displayHost("https://accounts.google.com@evil.com/slow"))
        assertEquals("evil.com", ToolbarState.displayHost("https://evil.com\\@accounts.google.com/"))
        assertEquals("a", ToolbarState.displayHost("https://a/b@c.example/"))
        assertEquals("[::1]", ToolbarState.displayHost("http://[::1]:8080/x"))
        assertEquals("example.org", ToolbarState.displayHost("https://user:pw@WWW.Example.org:8443/"))
        assertEquals("evil.com", app.hatch.engine.UrlHost.of("https://a.example:x@b.example@evil.com/"))
        assertNull(app.hatch.engine.UrlHost.of("https://[::1/"))
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

    @Test
    fun `chip security glyph - lock, globe for insecure or unknown, warning for broken https`() {
        val secure = SecurityInfo.Secure("example.org", "Let's Encrypt")
        val insecure = SecurityInfo.Insecure("example.org")
        assertEquals(ToolbarState.ChipSecurity.Lock, ToolbarState.chipSecurity(secure, "https://example.org/"))
        assertEquals(ToolbarState.ChipSecurity.Globe, ToolbarState.chipSecurity(insecure, "http://example.org/"))
        assertEquals(ToolbarState.ChipSecurity.Warning, ToolbarState.chipSecurity(insecure, "https://expired.badssl.com/"))
        assertEquals(ToolbarState.ChipSecurity.Warning, ToolbarState.chipSecurity(insecure, "HTTPS://example.org/"))
        assertEquals(ToolbarState.ChipSecurity.Globe, ToolbarState.chipSecurity(SecurityInfo.Unknown, "https://example.org/"))
        assertEquals(ToolbarState.ChipSecurity.Globe, ToolbarState.chipSecurity(null, null))
        assertEquals(ToolbarState.ChipSecurity.Globe, ToolbarState.chipSecurity(insecure, "about:config"))
        assertEquals(ToolbarIcons.Lock, ToolbarIcons.security(ToolbarState.ChipSecurity.Lock))
        assertEquals(ToolbarIcons.Globe, ToolbarIcons.security(ToolbarState.ChipSecurity.Globe))
        assertEquals(ToolbarIcons.Warning, ToolbarIcons.security(ToolbarState.ChipSecurity.Warning))
    }

    @Test
    fun `display host - host only for web pages, the url otherwise, empty on home`() {
        assertEquals("", ToolbarState.displayHost(null))
        assertEquals("", ToolbarState.displayHost("about:blank"))
        assertEquals("secure.chase.com", ToolbarState.displayHost("https://secure.chase.com/web/auth/dashboard?x=1"))
        assertEquals("example.org", ToolbarState.displayHost("https://www.example.org/a"))
        assertEquals("localhost", ToolbarState.displayHost("http://localhost:8080/t"))
        assertEquals("about:config", ToolbarState.displayHost("about:config"))
        assertEquals("moz-extension://abc/x.html", ToolbarState.displayHost("moz-extension://abc/x.html"))
    }

    @Test
    fun `tab counter label caps at 99`() {
        assertEquals("0", ToolbarState.tabCountLabel(0))
        assertEquals("4", ToolbarState.tabCountLabel(4))
        assertEquals("99", ToolbarState.tabCountLabel(99))
        assertEquals("∞", ToolbarState.tabCountLabel(100))
    }

    @Test
    fun `toolbar colours - desktop tints the row, phone stays on surface`() {
        for (palette in HatchPalette.entries) for (dark in listOf(false, true)) {
            val c = HatchColors.of(palette, dark)
            val desk = ToolbarColors.of(c, desktop = true)
            assertEquals(c.tb, desk.background, "$palette $dark")
            assertEquals(c.ti, desk.content)
            assertEquals(c.pl, desk.pill)
            assertEquals(c.ac, desk.chipFill)
            assertEquals(c.oa, desk.chipContent)
            assertEquals(c.sep, desk.chipSeparator)
            assertEquals(c.ti, desk.text)
            assertEquals(Color.Transparent, desk.divider)
            val phone = ToolbarColors.of(c, desktop = false)
            assertEquals(c.bg, phone.background)
            assertEquals(c.ink, phone.content)
            assertEquals(c.s1, phone.pill)
            assertEquals(Color.Transparent, phone.chipFill)
            assertEquals(c.mu, phone.chipContent)
            assertEquals(c.ln2, phone.chipSeparator)
            assertEquals(c.ink, phone.text)
            assertEquals(c.ln, phone.divider)
            for (t in listOf(desk, phone)) {
                assertEquals(c.mu, t.hint)
                assertEquals(c.ac, t.outline)
                assertEquals(c.ac, t.progress)
                assertEquals(c.ac.copy(alpha = ToolbarColors.PROGRESS_TRACK_ALPHA), t.progressTrack)
            }
        }
    }
}
