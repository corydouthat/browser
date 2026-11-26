package app.hatch.ui.persona

import app.hatch.persona.GeoSpec
import app.hatch.persona.Presets
import app.hatch.persona.ScreenSpec
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PersonaTextTest {
    private val win = Presets.identity("win-firefox")!!

    @Test
    fun `identity subtitle reads platform, screen, then DPR or cores`() {
        val base = win.copy(platform = "Win32", screen = ScreenSpec(1920, 1080, 1920, 1040, 24, 1.0), hardwareConcurrency = 8)
        assertEquals("Win32 · 1920×1080 · 8 cores", PersonaText.identitySubtitle(base))
        assertEquals("MacIntel · 1512×982 · DPR 2", PersonaText.identitySubtitle(base.copy(platform = "MacIntel", screen = ScreenSpec(1512, 982, 1512, 944, 30, 2.0))))
        assertEquals("Win32 · 1920×1080 · DPR 1.25", PersonaText.identitySubtitle(base.copy(screen = base.screen!!.copy(dpr = 1.25))))
        assertEquals("Linux x86_64 · 1920×1080", PersonaText.identitySubtitle(base.copy(platform = "Linux x86_64", hardwareConcurrency = null)))
        assertEquals("Real device · engine defaults", PersonaText.identitySubtitle(Presets.realDevice()))
        assertEquals("Desktop · engine defaults", PersonaText.identitySubtitle(base.copy(platform = null, screen = null, hardwareConcurrency = null)))
    }

    @Test
    fun `region subtitle and the time-zone notice`() {
        val uk = Presets.regions().first { it.timeZone == "Europe/London" }
        val text = PersonaText.regionSubtitle(uk.copy(geolocation = null))
        assertEquals(uk.languages.joinToString(", ") + " · Europe/London", text)
        assertEquals("en-GB · device time zone · 51.51, -0.13", PersonaText.regionSubtitle(uk.copy(languages = listOf("en-GB"), timeZone = null, geolocation = GeoSpec(51.5074, -0.1278))))
        assertEquals("Time zone changes to Europe/London. Open tabs reload once.", PersonaText.timeZoneNotice(true, uk))
        assertEquals("Time zone changes to the device time zone. Open tabs reload once.", PersonaText.timeZoneNotice(true, uk.copy(timeZone = null)))
        assertNull(PersonaText.timeZoneNotice(false, uk))
        assertEquals("2", PersonaText.formatNumber(2.0))
        assertEquals("1.25", PersonaText.formatNumber(1.25))
    }
}
