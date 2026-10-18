package app.hatch.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** UXP-06: back/Esc order and what the keyboard shortcuts clear before acting on the toolbar. */
class AppUiStateTest {
    @Test
    fun `Esc cancels the URL edit before it closes a find bar under it`() {
        val s = AppUiState()
        s.findBar = true
        var editing = true
        var exits = 0
        s.isUrlEditing = { editing }
        s.exitUrlEdit = { if (editing) { exits++; editing = false; true } else false }
        assertTrue(s.hasOverlay())
        assertTrue(s.onBack())
        assertEquals(1, exits, "the edit was cancelled first")
        assertTrue(s.findBar, "the find bar is still open")
        assertTrue(s.onBack())
        assertEquals(1, exits)
        assertFalse(s.findBar)
        assertFalse(s.onBack(), "nothing left to close")
    }

    @Test
    fun `back order after the URL edit is site info, find bar, tray, menu, sheet, settings, self-check`() {
        val s = AppUiState()
        s.siteInfo = true; s.findBar = true; s.overlay = Overlay.Tabs; s.menu = true; s.personaSheet = true; s.selfCheck = true
        assertTrue(s.onBack()); assertFalse(s.siteInfo)
        assertTrue(s.onBack()); assertFalse(s.findBar)
        assertTrue(s.onBack()); assertEquals(Overlay.None, s.overlay)
        s.overlay = Overlay.Settings
        assertTrue(s.onBack()); assertFalse(s.menu)
        assertTrue(s.onBack()); assertFalse(s.personaSheet)
        assertTrue(s.onBack()); assertEquals(Overlay.None, s.overlay)
        assertTrue(s.onBack()); assertFalse(s.selfCheck)
        assertFalse(s.onBack())
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
}
