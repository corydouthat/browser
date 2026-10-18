package app.hatch.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddonUrlTest {
    @Test
    fun `https URLs with a host are accepted, whitespace trimmed`() {
        assertTrue(AddonUrl.isAllowed("https://addons.mozilla.org/firefox/downloads/file/1/ublock_origin.xpi"))
        assertTrue(AddonUrl.isAllowed("  HTTPS://example.org/x.xpi \n"))
        assertTrue(AddonUrl.isAllowed("https://example.org:8443/x.xpi?y=1#z"))
    }

    @Test
    fun `everything else is rejected`() {
        for (u in listOf(
            "http://example.org/x.xpi", "file:///sdcard/Download/x.xpi", "data:application/x-xpinstall;base64,AAAA",
            "resource://android/assets/extension/", "moz-extension://abc/x.xpi", "ftp://example.org/x.xpi",
            "javascript:alert(1)", "https://", "https:///x.xpi", "example.org/x.xpi", "", "   ", "https://exa mple.org/x",
        )) {
            assertFalse(AddonUrl.isAllowed(u), u)
        }
    }
}
