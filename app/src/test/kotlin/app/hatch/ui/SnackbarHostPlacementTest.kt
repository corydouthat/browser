package app.hatch.ui

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UXP-02 structural guard (the Compose tree cannot be rendered on this JVM): the shared snackbar state is hosted
 * exactly once, as the LAST child of HatchApp's root Box (topmost in z-order, above Settings / Saved logins / the
 * tray / the rules overlays), and no screen that receives the shared state hosts it a second time.
 */
class SnackbarHostPlacementTest {
    private fun source(relative: String): String {
        val candidates = listOf(File("src/main/kotlin/app/hatch/$relative"), File("app/src/main/kotlin/app/hatch/$relative"))
        return candidates.firstOrNull { it.isFile }?.readText() ?: error("missing $relative from ${File(".").absolutePath}")
    }

    @Test
    fun `HatchApp hosts the shared snackbar once, after every overlay`() {
        val app = source("ui/HatchApp.kt")
        val hosts = Regex("SnackbarHost\\(snackbar").findAll(app).toList()
        assertEquals(1, hosts.size, "exactly one host of the shared state")
        val hostAt = hosts.single().range.first
        for (overlay in listOf("BrowserScreen(", "SettingsScreen(", "TabTrayScreen(", "SavedLoginsScreen(", "LoginPickerBar(", "RulesOverlays(")) {
            val at = app.lastIndexOf(overlay)
            assertTrue(at in 0 until hostAt, "$overlay must be composed before (under) the snackbar host")
        }
    }

    @Test
    fun `screens that take the shared state do not host it themselves`() {
        for (file in listOf("ui/BrowserScreen.kt", "ui/SettingsScreen.kt", "ui/logins/SavedLoginsScreen.kt", "ui/rules/RulesOverlays.kt")) {
            val text = source(file)
            assertTrue("SnackbarHost(" !in text, "$file must not host the shared snackbar state")
        }
    }
}
