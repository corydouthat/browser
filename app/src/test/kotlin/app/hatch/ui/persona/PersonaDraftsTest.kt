package app.hatch.ui.persona

import app.hatch.persona.Presets
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonaDraftsTest {
    @Test
    fun `preset round-trips through a draft unchanged (except id and name)`() {
        Presets.identities().forEach { preset ->
            val d = IdentityDraft.from(preset, name = "${preset.name} copy")
            assertEquals(emptyList(), PersonaDrafts.identityProblems(d), preset.id)
            val built = PersonaDrafts.buildIdentity("x-${preset.id}", d)
            assertEquals(preset.copy(id = "x-${preset.id}", name = "${preset.name} copy"), built)
        }
        Presets.regions().forEach { preset ->
            val d = RegionDraft.from(preset)
            assertEquals(emptyList(), PersonaDrafts.regionProblems(d), preset.id)
            assertEquals(preset.copy(id = "r-${preset.id}"), PersonaDrafts.buildRegion("r-${preset.id}", d))
        }
    }

    @Test
    fun `slug and unique id never collide with presets or existing ids`() {
        assertEquals("my-laptop-2", PersonaDrafts.slug("My Laptop (2)"))
        assertEquals("custom", PersonaDrafts.slug("!!!"))
        assertEquals("win-firefox-2", PersonaDrafts.uniqueId("win-firefox", emptySet()))
        assertEquals("win-firefox-3", PersonaDrafts.uniqueId("Win Firefox", setOf("win-firefox-2")))
        assertEquals("device-2", PersonaDrafts.uniqueId("device", emptySet()), "preset region id is taken too")
        assertTrue(PersonaDrafts.ID_PATTERN.matches(PersonaDrafts.uniqueId("A".repeat(100), emptySet())))
    }

    @Test
    fun `identity validation names each defect and blanks become engine defaults`() {
        val blank = IdentityDraft(name = "Minimal", layoutWidth = "0", desktop = false, touch = true)
        assertEquals(emptyList(), PersonaDrafts.identityProblems(blank))
        val built = PersonaDrafts.buildIdentity("minimal", blank)
        assertNull(built.userAgent); assertNull(built.screen); assertNull(built.hardwareConcurrency); assertNull(built.webgl); assertNull(built.uaData)
        assertEquals(0, built.layoutWidth)

        val bad = IdentityDraft(
            name = " ", layoutWidth = "-1", screenWidth = "1920", screenHeight = "x", availWidth = "2000", availHeight = "1040",
            colorDepth = "0", dpr = "9", hardwareConcurrency = "0", webglVendor = "V", webglRenderer = "", desktop = true, touch = true,
        )
        val p = PersonaDrafts.identityProblems(bad)
        listOf("Name is empty", "Layout width", "Screen width and height", "Available width cannot exceed", "Color depth", "Device pixel ratio", "Hardware concurrency", "WebGL vendor and renderer", "detectable")
            .forEach { needle -> assertTrue(p.any { it.contains(needle) }, needle) }
    }

    @Test
    fun `uaData is kept only for chrome-like identities`() {
        val chrome = assertNotNull(Presets.identity("win-chrome"))
        val d = IdentityDraft.from(chrome)
        assertNotNull(PersonaDrafts.buildIdentity("c", d).uaData)
        assertNull(PersonaDrafts.buildIdentity("c", d.copy(chromeLike = false)).uaData)
    }

    @Test
    fun `region validation`() {
        val good = RegionDraft(name = "Paris", languages = "fr-FR, fr, en", locale = "fr-FR", timeZone = "Europe/Paris", latitude = "48.8566", longitude = "2.3522", accuracyMeters = "25")
        assertEquals(emptyList(), PersonaDrafts.regionProblems(good))
        val r = PersonaDrafts.buildRegion("paris", good)
        assertEquals(listOf("fr-FR", "fr", "en"), r.languages)
        assertEquals("Europe/Paris", r.timeZone)
        assertEquals(25.0, r.geolocation?.accuracyMeters)

        val deviceTz = PersonaDrafts.buildRegion("x", good.copy(timeZone = "", latitude = "", longitude = ""))
        assertNull(deviceTz.timeZone); assertNull(deviceTz.geolocation)

        val bad = RegionDraft(name = "", languages = "english", locale = "e", timeZone = "Mars/Olympus", latitude = "91", longitude = "x", accuracyMeters = "0")
        val p = PersonaDrafts.regionProblems(bad)
        listOf("Name is empty", "Not a language tag: english", "Not a locale tag", "Unknown time zone", "Latitude", "Longitude", "Accuracy")
            .forEach { needle -> assertTrue(p.any { it.contains(needle) }, needle) }
        assertTrue(PersonaDrafts.regionProblems(RegionDraft(name = "n", languages = "", locale = "en")).any { it.contains("At least one language") })
    }

    @Test
    fun `zone picker`() {
        assertTrue(PersonaDrafts.isZoneId("Asia/Tokyo"))
        assertFalse(PersonaDrafts.isZoneId("asia/tokyo"))
        assertFalse(PersonaDrafts.isZoneId(""))
        val berlin = PersonaDrafts.zoneSuggestions("berl")
        assertEquals(listOf("Europe/Berlin"), berlin)
        val all = PersonaDrafts.zoneSuggestions("", limit = 10000)
        assertTrue(all.size > 300)
        assertTrue(all.none { it.startsWith("Etc/") })
        assertEquals(all.sorted(), all)
    }
}
