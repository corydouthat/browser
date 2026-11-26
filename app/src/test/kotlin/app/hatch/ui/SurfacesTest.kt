package app.hatch.ui

import app.hatch.ui.logins.loginChipDescription
import app.hatch.ui.logins.loginChipLabel
import app.hatch.ui.tabs.TabItemSelection
import app.hatch.ui.tabs.TabTrayState
import app.hatch.ui.tabs.trayTitle
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure pieces of the redesigned surfaces (tab tray, login picker). */
class SurfacesTest {
    @Test
    fun `tray title follows the Tab tray mockups`() {
        assertEquals("4 tabs", trayTitle(4, TabTrayState()))
        assertEquals("1 tab", trayTitle(1, TabTrayState()))
        assertEquals("Tabs", trayTitle(0, TabTrayState()), "Tab tray · Empty")
        assertEquals("2 selected", trayTitle(4, TabTrayState().enterSelect().toggle("a").toggle("b")))
        assertEquals("0 selected", trayTitle(4, TabTrayState().enterSelect()))
    }

    @Test
    fun `the accent ring marks the current tab, or the selected tabs in select mode`() {
        assertTrue(TabItemSelection(multiSelect = false, isSelected = false, isCurrent = true).ringed)
        assertFalse(TabItemSelection(multiSelect = false, isSelected = true, isCurrent = false).ringed)
        assertTrue(TabItemSelection(multiSelect = true, isSelected = true, isCurrent = false).ringed)
        assertFalse(TabItemSelection(multiSelect = true, isSelected = false, isCurrent = true).ringed)
    }

    @Test
    fun `login chips show the username and announce the site`() {
        assertEquals("alex.r", loginChipLabel("alex.r"))
        assertEquals("(no username)", loginChipLabel(""))
        assertEquals("Fill alex.r on chase.com", loginChipDescription("alex.r", "chase.com"))
    }
}
