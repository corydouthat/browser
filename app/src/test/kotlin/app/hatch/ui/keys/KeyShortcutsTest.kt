package app.hatch.ui.keys

import android.view.KeyEvent
import app.hatch.ui.UiEvent
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyShortcutsTest {
    private val page = KeyShortcuts.Context(overlayOpen = false, pageInputFocused = false)
    private val overlay = KeyShortcuts.Context(overlayOpen = true, pageInputFocused = false)
    private val typing = KeyShortcuts.Context(overlayOpen = false, pageInputFocused = true)

    private fun map(code: Int, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false, ctx: KeyShortcuts.Context = page) =
        KeyShortcuts.map(code, ctrl, shift, alt, ctx)

    @Test
    fun `browser shortcuts always belong to Hatch`() {
        assertEquals(UiEvent.NewTab, map(KeyEvent.KEYCODE_T, ctrl = true))
        assertEquals(UiEvent.CloseTab, map(KeyEvent.KEYCODE_W, ctrl = true))
        assertEquals(UiEvent.FindInPage, map(KeyEvent.KEYCODE_F, ctrl = true))
        assertEquals(UiEvent.NextTab, map(KeyEvent.KEYCODE_TAB, ctrl = true))
        assertEquals(UiEvent.PrevTab, map(KeyEvent.KEYCODE_TAB, ctrl = true, shift = true))
        assertEquals(UiEvent.ToggleEruda, map(KeyEvent.KEYCODE_I, ctrl = true, shift = true))
        assertEquals(UiEvent.PersonaSheet, map(KeyEvent.KEYCODE_P, ctrl = true, shift = true))
        assertEquals(UiEvent.GoBack, map(KeyEvent.KEYCODE_DPAD_LEFT, alt = true))
        assertEquals(UiEvent.GoForward, map(KeyEvent.KEYCODE_DPAD_RIGHT, alt = true))
        assertEquals(UiEvent.SelectTabIndex(1), map(KeyEvent.KEYCODE_1, ctrl = true))
        assertEquals(UiEvent.SelectTabIndex(9), map(KeyEvent.KEYCODE_9, ctrl = true))
        // Still Hatch's while typing in a page field or with an overlay open.
        assertEquals(UiEvent.NewTab, map(KeyEvent.KEYCODE_T, ctrl = true, ctx = typing))
        assertEquals(UiEvent.FindInPage, map(KeyEvent.KEYCODE_F, ctrl = true, ctx = overlay))
    }

    @Test
    fun `Esc reaches the page unless a Hatch surface is open`() {
        assertNull(map(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(UiEvent.Escape, map(KeyEvent.KEYCODE_ESCAPE, ctx = overlay))
        assertNull(map(KeyEvent.KEYCODE_ESCAPE, ctrl = true, ctx = overlay))
    }

    @Test
    fun `F5 and Ctrl+R reload only when the web content is the active surface`() {
        assertEquals(UiEvent.Reload, map(KeyEvent.KEYCODE_F5))
        assertEquals(UiEvent.Reload, map(KeyEvent.KEYCODE_R, ctrl = true))
        assertNull(map(KeyEvent.KEYCODE_F5, ctx = overlay))
        assertNull(map(KeyEvent.KEYCODE_F5, ctx = typing))
        assertNull(map(KeyEvent.KEYCODE_R, ctrl = true, ctx = typing))
        assertNull(map(KeyEvent.KEYCODE_R, ctrl = true, ctx = overlay))
    }

    @Test
    fun `Ctrl+L focuses the URL bar except while a page input is focused`() {
        assertEquals(UiEvent.FocusUrl, map(KeyEvent.KEYCODE_L, ctrl = true))
        assertEquals(UiEvent.FocusUrl, map(KeyEvent.KEYCODE_L, ctrl = true, ctx = overlay))
        assertNull(map(KeyEvent.KEYCODE_L, ctrl = true, ctx = typing))
    }

    @Test
    fun `plain letters and unknown chords go to the page`() {
        assertNull(map(KeyEvent.KEYCODE_T))
        assertNull(map(KeyEvent.KEYCODE_R))
        assertNull(map(KeyEvent.KEYCODE_1))
        assertNull(map(KeyEvent.KEYCODE_DPAD_LEFT))
        assertNull(map(KeyEvent.KEYCODE_T, ctrl = true, shift = true))
    }

    @Test
    fun `Ctrl+N resolves against the tab list, 9 is the last tab`() {
        val ids = listOf("a", "b", "c")
        assertEquals("a", KeyShortcuts.tabIdForIndex(1, ids))
        assertEquals("c", KeyShortcuts.tabIdForIndex(3, ids))
        assertNull(KeyShortcuts.tabIdForIndex(4, ids))
        assertEquals("c", KeyShortcuts.tabIdForIndex(9, ids))
        assertNull(KeyShortcuts.tabIdForIndex(1, emptyList()))
        assertNull(KeyShortcuts.tabIdForIndex(0, ids))
    }

    @Test
    fun `Ctrl+Tab wraps around`() {
        val ids = listOf("a", "b", "c")
        assertEquals("b", KeyShortcuts.neighbourTabId("a", ids, forward = true))
        assertEquals("a", KeyShortcuts.neighbourTabId("c", ids, forward = true))
        assertEquals("c", KeyShortcuts.neighbourTabId("a", ids, forward = false))
        assertEquals("a", KeyShortcuts.neighbourTabId("zzz", ids, forward = true))
        assertNull(KeyShortcuts.neighbourTabId("a", listOf("a"), forward = true))
    }
}
