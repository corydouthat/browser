package app.hatch.persona

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpectedReportTest {
    @Test
    fun `desktop persona expectations`() {
        val p = Persona(Presets.identity("mac-firefox")!!, Presets.region("jp")!!)
        val r = p.expectedReport()
        assertEquals(p.identity.userAgent, r.userAgent)
        assertEquals("MacIntel", r.platform)
        assertEquals("Intel Mac OS X 10.15", r.oscpu)
        assertEquals("5.0 (Macintosh)", r.appVersion)
        assertEquals(0, r.maxTouchPoints)
        assertFalse(r.hasTouchEvent)
        assertTrue(r.pointerFine); assertTrue(r.hoverHover)
        assertEquals(1366, r.minLayoutWidth)
        assertEquals(p.identity.screen, r.screen)
        assertEquals(2.0, r.dpr)
        assertEquals(8, r.hardwareConcurrency)
        assertEquals(listOf("ja-JP", "ja", "en"), r.languages)
        assertEquals("ja-JP,ja;q=0.9,en;q=0.8", r.acceptLanguage)
        assertEquals("Asia/Tokyo", r.timeZone)
        assertEquals(GeoSpec(35.6762, 139.6503, 50.0), r.geolocation)
        assertEquals(WebGlSpec("Apple", "Apple M1"), r.webgl)
        assertFalse(r.userAgentData)
        assertEquals("ja-JP", r.locale)
        // round-trips through JSON for the harness
        val json = Json.encodeToString(ExpectedReport.serializer(), r)
        assertEquals(r, Json.decodeFromString(ExpectedReport.serializer(), json))
    }

    @Test
    fun `real-device on device region asserts nothing engine-specific`() {
        val r = Persona(Presets.identity("real-device")!!, Presets.region("device")!!).expectedReport()
        assertNull(r.userAgent); assertNull(r.platform); assertNull(r.oscpu); assertNull(r.appVersion)
        assertNull(r.maxTouchPoints)
        assertTrue(r.hasTouchEvent)
        assertFalse(r.pointerFine); assertFalse(r.hoverHover)
        assertNull(r.minLayoutWidth); assertNull(r.screen); assertNull(r.dpr); assertNull(r.hardwareConcurrency)
        assertNull(r.languages); assertNull(r.acceptLanguage); assertNull(r.timeZone); assertNull(r.geolocation)
        assertNull(r.webgl); assertNull(r.locale)
        assertFalse(r.userAgentData)
    }

    @Test
    fun `win-chrome expects userAgentData`() {
        val r = Persona(Presets.identity("win-chrome")!!, Presets.region("device")!!).expectedReport()
        assertTrue(r.userAgentData)
        assertTrue(r.userAgent!!.contains("Chrome/141"))
    }
}
