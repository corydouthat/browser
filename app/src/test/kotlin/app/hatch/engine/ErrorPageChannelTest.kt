package app.hatch.engine

import app.hatch.engine.PersonaOffLink.Decision
import app.hatch.ui.theme.HatchPalette
import app.hatch.ui.theme.ThemeMode
import mozilla.components.concept.engine.request.ErrorType
import org.junit.jupiter.api.Test
import java.net.URLEncoder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ErrorPageChannelTest {
    private val failed = "https://maps.google.com/maps?q=a&b=c"
    private val token = "0123456789abcdef0123456789abcdef"
    private val pending = PersonaOffLink.Pending(failed, token)

    private fun link(u: String = failed, n: String = token, host: String = "persona-off") =
        "hatch-internal://$host?u=" + URLEncoder.encode(u, "UTF-8") + "&n=" + URLEncoder.encode(n, "UTF-8")

    @Test
    fun `the error page's own link, tapped, is honoured`() {
        assertEquals(Decision.Honour(failed), PersonaOffLink.decide(link(), hasUserGesture = true, isSubframeRequest = false, pending = pending))
    }

    @Test
    fun `a normal https page cannot trigger persona off`() {
        // A site navigating its own tab (no error page there, so no pending token): refused, never honoured.
        assertEquals(Decision.Refuse, PersonaOffLink.decide(link(), hasUserGesture = true, isSubframeRequest = false, pending = null))
        // A site guessing while an error page exists in that tab: the token does not match.
        assertEquals(Decision.Refuse, PersonaOffLink.decide(link(n = "f".repeat(32)), true, false, pending))
        assertEquals(Decision.Refuse, PersonaOffLink.decide("hatch-internal://persona-off?u=" + URLEncoder.encode(failed, "UTF-8"), true, false, pending))
        // A frame (an embedded page) or a scripted navigation without a user gesture.
        assertEquals(Decision.Refuse, PersonaOffLink.decide(link(), hasUserGesture = true, isSubframeRequest = true, pending = pending))
        assertEquals(Decision.Refuse, PersonaOffLink.decide(link(), hasUserGesture = false, isSubframeRequest = false, pending = pending))
    }

    @Test
    fun `the link must target the failed URL, over http(s), on the persona-off host`() {
        assertEquals(Decision.Refuse, PersonaOffLink.decide(link(u = "https://evil.example/"), true, false, pending))
        val fileFailure = PersonaOffLink.Pending("file:///sdcard/x.html", token)
        assertEquals(Decision.Refuse, PersonaOffLink.decide(link(u = "file:///sdcard/x.html"), true, false, fileFailure))
        assertEquals(Decision.Refuse, PersonaOffLink.decide(link(host = "settings"), true, false, pending))
        assertEquals(Decision.Refuse, PersonaOffLink.decide(link() + "&n=$token", true, false, pending), "duplicate keys")
        assertEquals(Decision.Refuse, PersonaOffLink.decide("hatch-internal://persona-off?u=%E0%A4%A&n=$token", true, false, pending), "bad escape")
    }

    @Test
    fun `every hatch-internal URI is ours in any case, everything else passes through`() {
        assertEquals(Decision.Refuse, PersonaOffLink.decide("HATCH-INTERNAL://persona-off", true, false, null))
        assertEquals(Decision.Refuse, PersonaOffLink.decide("hatch-internal:persona-off", true, false, null))
        assertEquals(Decision.NotOurs, PersonaOffLink.decide("https://example.org/?hatch-internal://x", true, false, pending))
        assertEquals(Decision.NotOurs, PersonaOffLink.decide("intent://scan/#Intent;scheme=zxing;end", true, false, pending))
        assertEquals(Decision.NotOurs, PersonaOffLink.decide("hatch-internals://x", true, false, pending))
        assertFalse(PersonaOffLink.isOurs("hatch-internal"))
    }

    @Test
    fun `tokens are random, per session, single use and invalidated`() {
        val tokens = ErrorPageTokens<Any>()
        val a = Any()
        val b = Any()
        val t1 = tokens.issue(a, failed)
        assertEquals(32, t1.length)
        assertTrue(t1.all { it in '0'..'9' || it in 'a'..'f' })
        val t2 = tokens.issue(a, failed)
        assertNotEquals(t1, t2, "a new error page replaces the token")
        assertEquals(PersonaOffLink.Pending(failed, t2), tokens.peek(a))
        assertNull(tokens.peek(b), "other sessions have none")
        assertEquals(PersonaOffLink.Pending(failed, t2), tokens.take(a))
        assertNull(tokens.take(a), "single use")
        tokens.issue(b, failed)
        tokens.invalidate(b)
        assertNull(tokens.peek(b))
    }

    @Test
    fun `error page params carry the URL, the token only while a persona is on, and both palettes`() {
        val on = ErrorPageParams.build(failed, token, HatchErrorPage.Kind.Network, personaOn = true, HatchPalette.Graphite, ThemeMode.System)
        assertTrue(on.startsWith("&u=" + URLEncoder.encode(failed, "UTF-8") + "&n=$token&po=1&kind=net&mode=system"))
        assertTrue("&l_ac=2B2B30" in on && "&l_oa=FFFFFF" in on && "&l_bg=FFFFFF" in on && "&l_mu=5F5F69" in on)
        assertTrue("&d_ac=D9D9DE" in on && "&d_bg=121214" in on && "&d_ink=ECECEF" in on)

        val off = ErrorPageParams.build(failed, token, HatchErrorPage.Kind.Network, personaOn = false, HatchPalette.Phosphor, ThemeMode.Dark)
        assertFalse("&n=" in off || "po=" in off, "no persona-off button when the persona is already off")
        assertTrue("&mode=dark" in off && "&l_ac=0F6E57" in off && "&d_ac=5DF27A" in off)

        val file = ErrorPageParams.build("file:///x", token, HatchErrorPage.Kind.Warning, personaOn = true, HatchPalette.Graphite, ThemeMode.Light)
        assertFalse("&n=" in file, "only web URLs can be retried with the persona off")
        assertEquals("00FF00", ErrorPageParams.hex(0xFF00FF00.toInt()))
    }

    @Test
    fun `error copy follows the Page error mockup and names verified nsresult codes`() {
        val timeout = HatchErrorPage.copyFor(ErrorType.ERROR_NET_TIMEOUT, "https://maps.google.com/x")
        assertEquals("Can't reach this site", timeout.title)
        assertEquals("maps.google.com took too long to respond. Your connection looks weak.", timeout.message)
        assertEquals("NS_ERROR_NET_TIMEOUT", timeout.code)
        assertEquals(HatchErrorPage.Kind.Network, timeout.kind)
        assertEquals("NS_ERROR_PORT_ACCESS_NOT_ALLOWED", HatchErrorPage.copyFor(ErrorType.ERROR_PORT_BLOCKED, null).code)
        assertEquals(HatchErrorPage.Kind.Security, HatchErrorPage.copyFor(ErrorType.ERROR_SECURITY_BAD_CERT, failed).kind)
        // Every type has copy (the `when` is exhaustive) and nothing contains markup (the page uses textContent).
        for (type in ErrorType.entries) {
            val copy = HatchErrorPage.copyFor(type, failed)
            assertTrue(copy.title.isNotBlank() && copy.message.isNotBlank() && copy.code.isNotBlank(), type.name)
            assertFalse('<' in copy.title || '<' in copy.message, type.name)
        }
    }

    @Test
    fun `hosts for the copy`() {
        assertEquals("maps.google.com", HatchErrorPage.hostOf("https://www.maps.google.com:8443/a"))
        assertEquals("example.org", HatchErrorPage.hostOf("http://user:pw@example.org/"))
        assertEquals("[::1]", HatchErrorPage.hostOf("http://[::1]:8080/"))
        assertNull(HatchErrorPage.hostOf("about:blank"))
        assertNull(HatchErrorPage.hostOf(null))
    }
}
