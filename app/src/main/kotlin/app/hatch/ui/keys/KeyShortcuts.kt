package app.hatch.ui.keys

import android.view.KeyEvent
import app.hatch.ui.UiEvent

/**
 * Hardware-keyboard shortcut table (pure; `MainActivity.dispatchKeyEvent` feeds it).
 *
 * Rules (M1 inventory risk 6):
 * - Esc is consumed only while a Hatch surface is open (find bar, tray, menu, sheet, settings, URL edit mode);
 *   otherwise the page sees it (sites close modals on Escape).
 * - F5 / Ctrl+R / Ctrl+L are browser shortcuts while the web content is the active surface; they are not fired
 *   while a page input is focused (detected from "engine view focused + IME visible", best effort) nor while a
 *   Hatch overlay is open, so typing in a page never reloads it.
 * - Ctrl+1..9, Ctrl+Tab / Ctrl+Shift+Tab, Alt+Left / Alt+Right, Ctrl+F, Ctrl+T, Ctrl+W, Ctrl+Shift+I (Eruda),
 *   Ctrl+Shift+P (persona sheet) are always Hatch's (desktop-browser convention).
 */
object KeyShortcuts {
    data class Context(
        /** A Hatch overlay that back/Esc should close is open (find bar, tray, menu, sheet, settings, edit mode). */
        val overlayOpen: Boolean,
        /** Best-effort: the page has a focused text input. */
        val pageInputFocused: Boolean,
    )

    /** Returns the event to emit, or null when the key should go to the page. Only ACTION_DOWN is mapped. */
    fun map(keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean, ctx: Context): UiEvent? {
        val plain = !ctrl && !shift && !alt
        return when {
            ctrl && shift && keyCode == KeyEvent.KEYCODE_I -> UiEvent.ToggleEruda
            ctrl && shift && keyCode == KeyEvent.KEYCODE_P -> UiEvent.PersonaSheet
            ctrl && shift && keyCode == KeyEvent.KEYCODE_TAB -> UiEvent.PrevTab
            ctrl && !shift && keyCode == KeyEvent.KEYCODE_TAB -> UiEvent.NextTab
            ctrl && !shift && keyCode == KeyEvent.KEYCODE_T -> UiEvent.NewTab
            ctrl && !shift && keyCode == KeyEvent.KEYCODE_W -> UiEvent.CloseTab
            ctrl && !shift && keyCode == KeyEvent.KEYCODE_F -> UiEvent.FindInPage
            ctrl && !shift && keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 ->
                UiEvent.SelectTabIndex(keyCode - KeyEvent.KEYCODE_1 + 1)
            alt && !ctrl && keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> UiEvent.GoBack
            alt && !ctrl && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> UiEvent.GoForward
            ctrl && !shift && keyCode == KeyEvent.KEYCODE_L ->
                if (ctx.pageInputFocused) null else UiEvent.FocusUrl
            ctrl && !shift && keyCode == KeyEvent.KEYCODE_R ->
                if (ctx.pageInputFocused || ctx.overlayOpen) null else UiEvent.Reload
            plain && keyCode == KeyEvent.KEYCODE_F5 ->
                if (ctx.pageInputFocused || ctx.overlayOpen) null else UiEvent.Reload
            plain && keyCode == KeyEvent.KEYCODE_ESCAPE -> if (ctx.overlayOpen) UiEvent.Escape else null
            else -> null
        }
    }

    /** Resolves Ctrl+N against the open tab list: 1..8 = position, 9 = last; null when out of range. */
    fun tabIdForIndex(index: Int, tabIds: List<String>): String? {
        if (tabIds.isEmpty() || index < 1) return null
        return when {
            index == 9 -> tabIds.last()
            index <= tabIds.size -> tabIds[index - 1]
            else -> null
        }
    }

    /** Ctrl+Tab / Ctrl+Shift+Tab: wraps around; null when there is nothing to switch to. */
    fun neighbourTabId(selectedId: String?, tabIds: List<String>, forward: Boolean): String? {
        if (tabIds.size < 2) return null
        val current = tabIds.indexOf(selectedId)
        if (current < 0) return tabIds.first()
        val next = if (forward) (current + 1) % tabIds.size else (current - 1 + tabIds.size) % tabIds.size
        return tabIds[next]
    }
}
