package app.hatch.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalBrowserTest {
    @Test
    fun `only http and https pages are handed to other apps`() {
        assertTrue(ExternalBrowser.isShareableUrl("https://example.org/"))
        assertTrue(ExternalBrowser.isShareableUrl("http://example.org"))
        assertFalse(ExternalBrowser.isShareableUrl("about:blank"))
        assertFalse(ExternalBrowser.isShareableUrl("file:///sdcard/x.html"))
        assertFalse(ExternalBrowser.isShareableUrl("data:text/html,hi"))
        assertFalse(ExternalBrowser.isShareableUrl("moz-extension://abc/popup.html"))
        assertFalse(ExternalBrowser.isShareableUrl(null))
        assertFalse(ExternalBrowser.isShareableUrl("https://"))
    }

    @Test
    fun `the chooser excludes every Hatch package's MainActivity, once`() {
        val targets = ExternalBrowser.excludedTargets(listOf("app.hatch.browser.debug", "app.hatch.browser", "app.hatch.browser", ""), ExternalBrowser.MAIN_ACTIVITY)
        assertEquals(
            listOf("app.hatch.browser.debug" to "app.hatch.MainActivity", "app.hatch.browser" to "app.hatch.MainActivity"),
            targets,
        )
    }
}
