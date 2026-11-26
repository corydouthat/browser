package app.hatch.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** UXP-06: back/Esc order and what the keyboard shortcuts clear before acting on the toolbar. */
class AppUiStateTest {
    @Test
    fun `the find row replaces the toolbar row, so URL edit and find are never open together`() {
        val s = AppUiState()
        s.findBar = true
        s.siteInfo = true
        s.prepareForUrlEdit()
        assertFalse(s.findBar, "Ctrl+L closes the find row the edit field replaces")
        assertFalse(s.siteInfo, "and the site-info card anchored under the toolbar")
        s.findBar = true; s.siteInfo = true
        s.onUrlEditStarted()
        assertFalse(s.findBar, "origin tap / Paste: same")
        assertFalse(s.siteInfo)
    }

    @Test
    fun `Esc cancels the URL edit once nothing above it is open`() {
        val s = AppUiState()
        var editing = true
        var exits = 0
        s.isUrlEditing = { editing }
        s.exitUrlEdit = { if (editing) { exits++; editing = false; true } else false }
        s.selfCheck = true
        assertTrue(s.hasOverlay())
        assertTrue(s.onBack())
        assertEquals(1, exits, "the edit was cancelled before the self-check bar under it")
        assertTrue(s.selfCheck)
        assertTrue(s.onBack())
        assertFalse(s.selfCheck)
        assertFalse(s.onBack(), "nothing left to close")
    }

    @Test
    fun `back order is site info, find, tray, menu, sheet, rules, logins, settings page, settings, URL edit, self-check`() {
        val s = AppUiState()
        val closed = mutableListOf<String>()
        var rules = 1
        var logins = 2 // the detail page, then the list
        var editing = true
        s.isUrlEditing = { editing }
        s.exitUrlEdit = { if (editing) { editing = false; closed += "edit"; true } else false }
        val rulesBack = { if (rules > 0) { rules--; closed += "rules"; true } else false }
        val loginsBack = { if (logins > 0) { logins--; closed += "logins"; true } else false }
        s.siteInfo = true; s.findBar = true; s.overlay = Overlay.Tabs; s.menu = true; s.personaSheet = true; s.selfCheck = true
        fun back() = s.onBack(rulesBack, loginsBack)
        assertTrue(back()); assertFalse(s.siteInfo)
        assertTrue(back()); assertFalse(s.findBar)
        assertTrue(back()); assertEquals(Overlay.None, s.overlay)
        s.openSettings(SettingsPage.Developer)
        s.menu = true
        s.personaSheet = true
        assertTrue(back()); assertFalse(s.menu)
        assertTrue(back()); assertFalse(s.personaSheet)
        assertTrue(back()); assertEquals(listOf("rules"), closed)
        assertTrue(back()); assertTrue(back()); assertEquals(listOf("rules", "logins", "logins"), closed)
        assertEquals(SettingsPage.Developer, s.settingsPage)
        assertTrue(back()); assertNull(s.settingsPage); assertEquals(Overlay.Settings, s.overlay)
        assertTrue(back()); assertEquals(Overlay.None, s.overlay)
        assertEquals(listOf("rules", "logins", "logins"), closed, "the URL edit waits under Settings")
        assertTrue(back()); assertEquals("edit", closed.last())
        assertTrue(back()); assertFalse(s.selfCheck)
        assertFalse(back())
        assertFalse(s.hasOverlay())
    }

    @Test
    fun `Ctrl+L leaves Settings, the tray, the menu and the find bar before the bar takes focus`() {
        val s = AppUiState()
        s.overlay = Overlay.Settings; s.menu = true; s.findBar = true
        s.prepareForUrlEdit()
        assertEquals(Overlay.None, s.overlay)
        assertFalse(s.menu)
        assertFalse(s.findBar)
        s.overlay = Overlay.Tabs
        s.prepareForUrlEdit()
        assertEquals(Overlay.None, s.overlay)
    }

    @Test
    fun `Ctrl+F leaves the tray and Settings before the find bar opens`() {
        val s = AppUiState()
        s.overlay = Overlay.Tabs; s.menu = true
        s.prepareForFind()
        assertEquals(Overlay.None, s.overlay)
        assertFalse(s.menu)
    }

    @Test
    fun `back pops Settings pages before it closes Settings`() {
        val s = AppUiState()
        s.openSettings()
        assertEquals(Overlay.Settings, s.overlay)
        assertNull(s.settingsPage, "root page")
        s.pushSettingsPage(SettingsPage.Developer)
        s.pushSettingsPage(SettingsPage.Developer)
        assertEquals(listOf(SettingsPage.Developer), s.settingsStack.toList(), "the same page twice is one entry")
        s.pushSettingsPage(SettingsPage.Appearance)
        assertEquals(SettingsPage.Appearance, s.settingsPage)
        assertTrue(s.hasOverlay())
        assertTrue(s.onBack()); assertEquals(SettingsPage.Developer, s.settingsPage)
        assertTrue(s.onBack()); assertNull(s.settingsPage); assertEquals(Overlay.Settings, s.overlay)
        assertTrue(s.onBack()); assertEquals(Overlay.None, s.overlay)
        assertFalse(s.onBack())
    }

    @Test
    fun `OpenAddons deep-links to the Add-ons page and reopening Settings starts at the root`() {
        val s = AppUiState()
        s.menu = true
        s.openSettings(SettingsPage.Addons)
        assertFalse(s.menu, "the menu that emitted the event closes")
        assertEquals(SettingsPage.Addons, s.settingsPage)
        s.settingsBack()
        assertNull(s.settingsPage)
        assertEquals(Overlay.Settings, s.overlay)
        s.pushSettingsPage(SettingsPage.Toolbar)
        s.closeSettings()
        assertEquals(Overlay.None, s.overlay)
        assertTrue(s.settingsStack.isEmpty())
        s.openSettings()
        assertNull(s.settingsPage)
    }

    @Test
    fun `pages are only pushed while Settings is open and the stack does not leak into another overlay`() {
        val s = AppUiState()
        s.pushSettingsPage(SettingsPage.Developer)
        assertTrue(s.settingsStack.isEmpty())
        s.openSettings(SettingsPage.Developer)
        s.prepareForUrlEdit()
        assertEquals(Overlay.None, s.overlay)
        assertTrue(s.settingsStack.isEmpty())
        s.openSettings(SettingsPage.Developer)
        s.prepareForFind()
        assertTrue(s.settingsStack.isEmpty())
        s.overlay = Overlay.Tabs
        assertNull(s.settingsPage, "no page outside Settings")
    }

    @Test
    fun `Self-check from Settings closes Settings, then starts the check`() {
        val s = AppUiState()
        s.openSettings(SettingsPage.Developer)
        s.startSelfCheckFromSettings()
        assertEquals(Overlay.None, s.overlay)
        assertTrue(s.settingsStack.isEmpty())
        assertTrue(s.selfCheck)
        assertTrue(s.onBack()); assertFalse(s.selfCheck)
    }

    @Test
    fun `back and Esc close the Settings search before Settings (RS-05)`() {
        val ui = AppUiState()
        ui.openSettings()
        ui.settingsSearch = "rew"
        assertTrue(ui.onBack())
        assertNull(ui.settingsSearch, "the first back clears the search")
        assertEquals(Overlay.Settings, ui.overlay, "and keeps Settings open")
        assertTrue(ui.onBack())
        assertEquals(Overlay.None, ui.overlay, "the second back closes Settings")
    }

    @Test
    fun `a page opened from search results pops before the search closes, and reopening Settings starts unsearched`() {
        val ui = AppUiState()
        ui.openSettings()
        ui.settingsSearch = "host"
        ui.pushSettingsPage(SettingsPage.HostRewrites)
        ui.onBack()
        assertNull(ui.settingsPage)
        assertEquals("host", ui.settingsSearch, "back from the page returns to the filtered root")
        ui.closeSettings()
        assertNull(ui.settingsSearch)
        ui.settingsSearch = "x"
        ui.openSettings()
        assertNull(ui.settingsSearch)
    }
}
