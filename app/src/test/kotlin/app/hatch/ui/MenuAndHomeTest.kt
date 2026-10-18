package app.hatch.ui

import app.hatch.ui.home.recentTabs
import app.hatch.ui.menu.mainMenuItems
import mozilla.components.browser.state.state.createTab
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MenuAndHomeTest {
    @Test
    fun `menu order matches the M1 design and disables open-in-browser for non-web pages`() {
        val none = {}
        val items = mainMenuItems(none, none, none, openInBrowserEnabled = false, none, none, none, none, none, none, none, none)
        assertEquals(
            listOf(
                "New tab", "Find in page", "Open in another browser", "Add-ons", "Saved logins", "Site rules",
                "Site permissions", "Self-check", "Eruda devtools", "Persona…", "Settings",
            ),
            items.map { it.label },
        )
        assertFalse(items[2].enabled)
        assertTrue(items.filterIndexed { i, _ -> i != 2 }.all { it.enabled })
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
    }
}
