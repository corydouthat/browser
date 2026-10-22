package app.hatch.ui

import app.hatch.persona.Persona
import app.hatch.persona.Presets
import app.hatch.ui.home.recentTabs
import app.hatch.ui.menu.MenuIcon
import app.hatch.ui.menu.mainMenuItems
import app.hatch.ui.menu.quickActions
import app.hatch.ui.surfaces.PersonaLabels
import app.hatch.ui.surfaces.shouldToggleDesktop
import mozilla.components.browser.state.state.createTab
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MenuAndHomeTest {
    private val none = {}

    private fun items(openInBrowser: Boolean = true, eruda: Boolean = false) = mainMenuItems(
        onNewTab = none, onFindInPage = none, onOpenInBrowser = none, openInBrowserEnabled = openInBrowser,
        onSiteRules = none, onSitePermissions = none, onSavedLogins = none, onAddons = none, onSettings = none,
        erudaInMenu = eruda, onEruda = none,
    )

    @Test
    fun `menu order and groups match the Main menu mockup`() {
        val items = items()
        assertEquals(
            listOf(
                "New tab", "Find in page", "Open in another browser",
                "Site rules", "Site permissions", "Saved logins",
                "Add-ons", "Settings",
            ),
            items.map { it.label },
        )
        // Dividers start the second and third groups only.
        assertEquals(listOf("Site rules", "Add-ons"), items.filter { it.dividerBefore }.map { it.label })
        assertEquals(
            listOf(
                MenuIcon.NewTab, MenuIcon.Find, MenuIcon.External, MenuIcon.SiteRules, MenuIcon.SitePermissions,
                MenuIcon.SavedLogins, MenuIcon.Addons, MenuIcon.Settings,
            ),
            items.map { it.icon },
        )
        // Persona…, Self-check and Eruda left the default menu (Settings › Developer has them).
        assertTrue(items.none { it.label in setOf("Persona…", "Self-check", "Eruda devtools") })
    }

    @Test
    fun `eruda is appended only when the developer setting is on`() {
        assertFalse(items(eruda = false).any { it.label == "Eruda devtools" })
        val withEruda = items(eruda = true)
        assertEquals("Eruda devtools", withEruda.last().label)
        assertEquals(MenuIcon.Eruda, withEruda.last().icon)
        assertEquals(9, withEruda.size)
    }

    @Test
    fun `open in another browser is disabled for non-web pages`() {
        val items = items(openInBrowser = false)
        assertFalse(items.single { it.label == "Open in another browser" }.enabled)
        assertTrue(items.filter { it.label != "Open in another browser" }.all { it.enabled })
    }

    @Test
    fun `quick actions are back, forward, share and reload or stop`() {
        assertEquals(listOf("Back", "Forward", "Share", "Reload"), quickActions(true, false, loading = false).map { it.label })
        assertEquals(listOf(true, false, true, true), quickActions(true, false, loading = false).map { it.enabled })
        assertEquals("Stop", quickActions(false, false, loading = true).last().label)
        assertFalse(quickActions(false, true, loading = true).first().enabled)
    }

    @Test
    fun `mode pickers only toggle when the other mode is chosen`() {
        assertTrue(shouldToggleDesktop(currentDesktop = true, wantDesktop = false))
        assertTrue(shouldToggleDesktop(currentDesktop = false, wantDesktop = true))
        assertFalse(shouldToggleDesktop(currentDesktop = true, wantDesktop = true))
        assertFalse(shouldToggleDesktop(currentDesktop = false, wantDesktop = false))
    }

    @Test
    fun `persona labels drop the parenthetical of preset names`() {
        assertEquals("Real device", PersonaLabels.short("Real device (no spoofing)"))
        assertEquals("United Kingdom", PersonaLabels.short("United Kingdom (London)"))
        assertEquals("Windows · Chrome 141", PersonaLabels.short("Windows · Chrome 141 (UA only; JS feature checks can tell it is Gecko)"))
        assertEquals("Windows · Firefox 158", PersonaLabels.short("Windows · Firefox 158"))
        assertEquals("(only)", PersonaLabels.short("(only)"), "nothing left: keep the full name")
        val p = Persona(Presets.identity("win-firefox")!!, Presets.region("device")!!)
        assertEquals("Windows · Firefox 158 · Device", PersonaLabels.full(p))
    }

    @Test
    fun `recent tabs exclude the current tab and blank tabs, newest first, limited`() {
        val tabs = listOf(
            createTab("https://a.org", id = "a", lastAccess = 10, createdAt = 10),
            createTab("about:blank", id = "blank", lastAccess = 100, createdAt = 100),
            createTab("https://b.org", id = "b", lastAccess = 50, createdAt = 50),
            createTab("https://c.org", id = "c", lastAccess = 70, createdAt = 70),
        )
        assertEquals(listOf("c", "b", "a"), recentTabs(tabs, currentId = null).map { it.id })
        assertEquals(listOf("b", "a"), recentTabs(tabs, currentId = "c").map { it.id })
        assertEquals(listOf("c"), recentTabs(tabs, currentId = null, limit = 1).map { it.id })
        val many = (1..10).map { createTab("https://t$it.org", id = "t$it", lastAccess = it.toLong(), createdAt = 0) }
        assertEquals(6, recentTabs(many, currentId = null).size, "the home grid shows at most three rows of two")
    }
}
