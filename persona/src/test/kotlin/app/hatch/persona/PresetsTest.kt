package app.hatch.persona

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresetsTest {
    @Test
    fun `identities load, are unique and well formed`() {
        val ids = Presets.identities()
        assertEquals(listOf("win-firefox", "mac-firefox", "linux-firefox", "win-chrome", "real-device"), ids.map { it.id })
        assertEquals(ids.size, ids.map { it.id }.toSet().size, "identity ids must be unique")
        assertEquals(ids.size, ids.map { it.name }.toSet().size, "identity names must be unique")
        for (id in ids) {
            assertTrue(Regex("^[a-z0-9-]+$").matches(id.id), "id ${id.id} must be url/file safe")
            if (id.desktop) {
                assertTrue(id.layoutWidth >= 1280, "${id.id}: layoutWidth ${id.layoutWidth} < 1280")
                assertNotNull(id.userAgent, "${id.id}: desktop identity needs a UA")
                assertNotNull(id.platform, "${id.id}: desktop identity needs a platform")
                assertNotNull(id.screen, "${id.id}: desktop identity needs a screen")
                assertFalse(id.touch, "${id.id}: desktop identity must not keep touch")
                val s = id.screen
                assertTrue(s.availWidth <= s.width && s.availHeight <= s.height, "${id.id}: avail > screen")
                assertTrue(s.dpr > 0.0)
                assertTrue(id.userAgent.startsWith("Mozilla/5.0 ("))
                assertFalse(id.userAgent.contains("Android"), "${id.id}: desktop UA must not mention Android")
                assertFalse(id.userAgent.contains("Mobile"), "${id.id}: desktop UA must not mention Mobile")
            }
            if (id.chromeLike) {
                assertNotNull(id.uaData, "${id.id}: chromeLike needs uaData")
                assertTrue(id.uaData.brands.any { it.brand == "Chromium" })
                assertTrue(id.userAgent!!.contains("Chrome/"))
            } else {
                assertNull(id.uaData, "${id.id}: only chromeLike identities carry uaData")
            }
        }
    }

    @Test
    fun `firefox identities carry Firefox 158 desktop UA strings`() {
        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0",
            Presets.identity("win-firefox")!!.userAgent,
        )
        assertEquals("Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:158.0) Gecko/20100101 Firefox/158.0", Presets.identity("mac-firefox")!!.userAgent)
        assertEquals("Mozilla/5.0 (X11; Linux x86_64; rv:158.0) Gecko/20100101 Firefox/158.0", Presets.identity("linux-firefox")!!.userAgent)
        assertEquals("Win32", Presets.identity("win-firefox")!!.platform)
        assertEquals("MacIntel", Presets.identity("mac-firefox")!!.platform)
        assertEquals("Linux x86_64", Presets.identity("linux-firefox")!!.platform)
        assertEquals("5.0 (Windows)", Presets.identity("win-firefox")!!.appVersion)
        assertEquals("5.0 (Macintosh)", Presets.identity("mac-firefox")!!.appVersion)
        assertEquals("5.0 (X11)", Presets.identity("linux-firefox")!!.appVersion)
    }

    @Test
    fun `real-device identity leaves every engine default alone`() {
        val rd = Presets.realDevice()
        assertEquals(Presets.REAL_DEVICE_ID, rd.id)
        assertFalse(rd.desktop)
        assertTrue(rd.touch)
        assertNull(rd.userAgent); assertNull(rd.platform); assertNull(rd.oscpu); assertNull(rd.appVersion)
        assertNull(rd.screen); assertNull(rd.hardwareConcurrency); assertNull(rd.webgl); assertNull(rd.uaData)
        assertFalse(rd.chromeLike)
    }

    @Test
    fun `regions load, are unique and well formed`() {
        val regions = Presets.regions()
        assertEquals(listOf("device", "us-east", "uk", "de", "jp"), regions.map { it.id })
        assertEquals(regions.size, regions.map { it.id }.toSet().size)
        for (r in regions) {
            assertTrue(r.languages.isNotEmpty(), "${r.id}: languages empty")
            assertTrue(r.locale.isNotEmpty())
            assertTrue(r.languages.first().startsWith(r.locale.substringBefore('-')), "${r.id}: first language should match locale")
            if (r.timeZone != null) {
                java.time.ZoneId.of(r.timeZone) // throws for unknown ids
            }
            r.geolocation?.let { g ->
                assertTrue(g.latitude in -90.0..90.0 && g.longitude in -180.0..180.0 && g.accuracyMeters > 0)
            }
        }
        val device = Presets.region(Presets.DEVICE_REGION_ID)!!
        assertNull(device.timeZone); assertNull(device.geolocation)
        assertEquals(listOf("en-US", "en"), device.languages)
        assertEquals("America/New_York", Presets.region("us-east")!!.timeZone)
        assertEquals("Europe/London", Presets.region("uk")!!.timeZone)
        assertEquals("Europe/Berlin", Presets.region("de")!!.timeZone)
        assertEquals("Asia/Tokyo", Presets.region("jp")!!.timeZone)
        assertEquals("en-GB", Presets.region("uk")!!.locale)
    }

    @Test
    fun `default persona is win-firefox on the device region`() {
        val p = Presets.default()
        assertEquals("win-firefox", p.identity.id)
        assertEquals("device", p.region.id)
        assertTrue(p.desktop)
        assertEquals("win-firefox__device", p.key)
        assertNull(Presets.identity("nope"))
        assertNull(Presets.region("nope"))
    }
}
