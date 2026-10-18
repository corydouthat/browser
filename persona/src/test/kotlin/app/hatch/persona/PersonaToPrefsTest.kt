package app.hatch.persona

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonaToPrefsTest {
    private fun p(i: String, r: String) = Persona(Presets.identity(i)!!, Presets.region(r)!!)

    @Test
    fun `desktop identity sets the full table`() {
        val ps = PersonaToPrefs.prefs(p("win-firefox", "us-east"))
        assertEquals(PrefValue.Num(1366), ps.set["browser.viewport.desktopWidth"])
        assertEquals(PrefValue.Num(0), ps.set["dom.w3c_touch_events.enabled"])
        assertEquals(PrefValue.Num(0), ps.set["dom.maxtouchpoints.testing.value"])
        assertEquals(PrefValue.Num(6), ps.set["ui.primaryPointerCapabilities"])
        assertEquals(PrefValue.Num(6), ps.set["ui.allPointerCapabilities"])
        assertEquals(PrefValue.Bool(true), ps.set["layout.accessiblecaret.enabled"])
        assertEquals(PrefValue.Str("Win32"), ps.set["general.platform.override"])
        assertEquals(PrefValue.Str("Windows NT 10.0; Win64; x64"), ps.set["general.oscpu.override"])
        assertEquals(PrefValue.Str("5.0 (Windows)"), ps.set["general.appversion.override"])
        assertEquals(PrefValue.Str("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0"), ps.set["general.useragent.override"])
        assertEquals(PrefValue.Str("en-US, en"), ps.set["intl.accept_languages"])
        assertEquals(PrefValue.Str("en-US"), ps.set["intl.locale.requested"])
        assertTrue(ps.clear.isEmpty())
        assertEquals(PersonaToPrefs.allPrefs.toSet(), ps.set.keys)
    }

    @Test
    fun `real-device on device region clears everything`() {
        val ps = PersonaToPrefs.prefs(p("real-device", "device"))
        assertTrue(ps.set.isEmpty())
        assertEquals(PersonaToPrefs.allPrefs.toSet(), ps.clear)
    }

    @Test
    fun `accessible caret follows the touch flag (HATCH-WC-03)`() {
        for (id in listOf("win-firefox", "mac-firefox", "linux-firefox", "win-chrome")) {
            val ps = PersonaToPrefs.prefs(p(id, "device"))
            assertEquals(PrefValue.Bool(true), ps.set["layout.accessiblecaret.enabled"], id)
            assertFalse("layout.accessiblecaret.enabled" in ps.clear, id)
        }
        val real = PersonaToPrefs.prefs(p("real-device", "jp"))
        assertTrue("layout.accessiblecaret.enabled" in real.clear)
        assertFalse(real.set.containsKey("layout.accessiblecaret.enabled"))
        assertTrue("layout.accessiblecaret.enabled" in PersonaToPrefs.identityPrefs)
    }

    @Test
    fun `keepTouchEvents leaves the touch pref at the engine default and nothing else (HATCH-WC-06)`() {
        val base = PersonaToPrefs.prefs(p("win-firefox", "us-east"))
        val keep = PersonaToPrefs.prefs(p("win-firefox", "us-east"), keepTouchEvents = true)
        assertFalse(keep.set.containsKey("dom.w3c_touch_events.enabled"))
        assertEquals(setOf("dom.w3c_touch_events.enabled"), keep.clear)
        assertEquals(base.set - "dom.w3c_touch_events.enabled", keep.set)
        assertEquals(PrefValue.Num(0), keep.set["dom.maxtouchpoints.testing.value"])
        assertEquals(PrefValue.Num(6), keep.set["ui.primaryPointerCapabilities"])
        // a touch identity is unaffected by the flag
        assertEquals(PersonaToPrefs.prefs(p("real-device", "device")), PersonaToPrefs.prefs(p("real-device", "device"), keepTouchEvents = true))
        // the golden/yaml path never uses the flag
        assertTrue(PersonaToPrefs.yaml(p("win-firefox", "us-east")).contains("\n  \"dom.w3c_touch_events.enabled\": 0\n"))
    }

    @Test
    fun `region prefs are independent of the identity`() {
        val ps = PersonaToPrefs.prefs(p("real-device", "jp"))
        assertEquals(PersonaToPrefs.identityPrefs.toSet(), ps.clear)
        assertEquals(mapOf<String, PrefValue>("intl.accept_languages" to PrefValue.Str("ja-JP, ja, en"), "intl.locale.requested" to PrefValue.Str("ja-JP")), ps.set)
        val dev = PersonaToPrefs.prefs(p("mac-firefox", "device"))
        assertEquals(setOf("intl.accept_languages", "intl.locale.requested"), dev.clear)
        assertFalse(dev.set.containsKey("intl.accept_languages"))
    }

    @Test
    fun `env carries TZ only for pinned zones and drives needsRestart`() {
        assertEquals(emptyMap(), PersonaToPrefs.env(p("win-firefox", "device")))
        assertEquals(mapOf("TZ" to "Europe/Berlin"), PersonaToPrefs.env(p("win-firefox", "de")))
        assertFalse(PersonaToPrefs.needsRestart(p("win-firefox", "de"), p("mac-firefox", "de")))
        assertFalse(PersonaToPrefs.needsRestart(p("win-firefox", "device"), p("real-device", "device")))
        assertTrue(PersonaToPrefs.needsRestart(p("win-firefox", "device"), p("win-firefox", "de")))
        assertTrue(PersonaToPrefs.needsRestart(p("win-firefox", "de"), p("win-firefox", "jp")))
        assertTrue(PersonaToPrefs.needsRestart(p("win-firefox", "jp"), p("win-firefox", "device")))
    }

    @Test
    fun `yaml is a typed geckoview-config document`() {
        val y = PersonaToPrefs.yaml(p("win-firefox", "us-east"))
        val lines = y.lines()
        assertTrue(lines[0].startsWith("# Generated by Hatch"))
        assertEquals("prefs:", lines[1])
        assertTrue(y.contains("\n  \"browser.viewport.desktopWidth\": 1366\n"), y)
        assertTrue(y.contains("\n  \"dom.w3c_touch_events.enabled\": 0\n"))
        assertTrue(y.contains("\n  \"ui.primaryPointerCapabilities\": 6\n"))
        assertTrue(y.contains("\n  \"general.useragent.override\": \"Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0\"\n"))
        assertTrue(y.contains("\n  \"intl.accept_languages\": \"en-US, en\"\n"))
        assertTrue(y.contains("\nenv:\n  \"TZ\": \"America/New_York\"\n"))
        assertTrue(y.endsWith("\nargs: []\n"))
        // pref lines are sorted and all under the prefs: key
        val prefLines = lines.drop(2).takeWhile { it.startsWith("  ") }
        assertEquals(12, prefLines.size)
        assertTrue(y.contains("\n  \"layout.accessiblecaret.enabled\": true\n"))
        assertEquals(prefLines, prefLines.sorted())
        assertTrue(prefLines.all { Regex("^  \"[a-zA-Z0-9_.]+\": (\"[^\"]*\"|-?\\d+|true|false)$").matches(it) }, prefLines.joinToString("\n"))
    }

    @Test
    fun `yaml for real-device on device region has empty maps`() {
        val y = PersonaToPrefs.yaml(p("real-device", "device"))
        assertTrue(y.contains("\nprefs: {}\n"), y)
        assertTrue(y.contains("\nenv: {}\n"), y)
        assertTrue(y.endsWith("args: []\n"))
    }

    @Test
    fun `yaml strings are escaped`() {
        assertEquals("\"a \\\"q\\\" b\\\\c\"", PersonaToPrefs.yamlString("a \"q\" b\\c"))
        assertEquals("\"line\\nbreak\\ttab\"", PersonaToPrefs.yamlString("line\nbreak\ttab"))
        assertEquals("\"1.0\"", PersonaToPrefs.yamlString("1.0"))
        assertEquals("\"\"", PersonaToPrefs.yamlString(""))
    }

    @Test
    fun `tzOffsets window and sign`() {
        val clock = PersonaToPrefs.GOLDEN_CLOCK
        val tokyo = PersonaToPrefs.tzOffsets("Asia/Tokyo", clock)!!
        assertEquals(1, tokyo.size)
        assertEquals(Instant.parse("2025-01-01T00:00:00Z").toEpochMilli(), tokyo[0].fromEpochMs)
        assertEquals(540, tokyo[0].offsetMinutes)

        val ny = PersonaToPrefs.tzOffsets("America/New_York", clock)!!
        // window 2025-01-01 .. 2026-12-31: start + 4 DST transitions (Mar/Nov 2025, Mar/Nov 2026)
        assertEquals(5, ny.size)
        assertEquals(-300, ny[0].offsetMinutes)
        assertEquals(Instant.parse("2025-03-09T07:00:00Z").toEpochMilli(), ny[1].fromEpochMs)
        assertEquals(-240, ny[1].offsetMinutes)
        assertEquals(Instant.parse("2025-11-02T06:00:00Z").toEpochMilli(), ny[2].fromEpochMs)
        assertEquals(-300, ny[2].offsetMinutes)
        assertEquals(Instant.parse("2026-03-08T07:00:00Z").toEpochMilli(), ny[3].fromEpochMs)
        assertEquals(-240, ny[3].offsetMinutes)
        assertEquals(Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(), ny[4].fromEpochMs)
        assertEquals(-300, ny[4].offsetMinutes)
        assertTrue(ny.zipWithNext().all { (a, b) -> a.fromEpochMs < b.fromEpochMs })

        assertNull(PersonaToPrefs.tzOffsets("Not/AZone", clock))
        // a different clock moves the window
        val later = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC)
        assertEquals(Instant.parse("2025-07-01T00:00:00Z").toEpochMilli(), PersonaToPrefs.tzOffsets("Europe/London", later)!![0].fromEpochMs)
    }

    @Test
    fun `shimConfig follows the contract`() {
        val cfg = PersonaToPrefs.shimConfig(p("win-chrome", "uk"), PersonaToPrefs.GOLDEN_CLOCK)
        assertEquals(
            listOf(
                "enabled", "desktop", "touch", "userAgent", "platform", "oscpu", "appVersion", "layoutWidth", "screen",
                "hardwareConcurrency", "webgl", "chromeLike", "uaData", "languages", "locale", "timeZone", "tzShim",
                "tzOffsets", "geolocation",
            ),
            cfg.keys.toList(),
        )
        assertTrue(cfg["enabled"]!!.jsonPrimitive.boolean)
        assertTrue(cfg["desktop"]!!.jsonPrimitive.boolean)
        assertFalse(cfg["touch"]!!.jsonPrimitive.boolean)
        assertEquals(1366, cfg["layoutWidth"]!!.jsonPrimitive.int)
        val screen = cfg["screen"]!!.jsonObject
        assertEquals(listOf("width", "height", "availWidth", "availHeight", "colorDepth", "dpr"), screen.keys.toList())
        assertEquals("1.0", screen["dpr"]!!.jsonPrimitive.content)
        assertEquals(8, cfg["hardwareConcurrency"]!!.jsonPrimitive.int)
        assertTrue(cfg["chromeLike"]!!.jsonPrimitive.boolean)
        val ua = cfg["uaData"]!!.jsonObject
        assertEquals(listOf("brands", "platform", "platformVersion", "architecture", "bitness", "model", "mobile"), ua.keys.toList())
        assertFalse(ua["mobile"]!!.jsonPrimitive.boolean)
        assertEquals(3, ua["brands"]!!.jsonArray.size)
        assertEquals(listOf("en-GB", "en"), cfg["languages"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("en-GB", cfg["locale"]!!.jsonPrimitive.content)
        assertEquals("Europe/London", cfg["timeZone"]!!.jsonPrimitive.content)
        assertFalse(cfg["tzShim"]!!.jsonPrimitive.boolean)
        val offsets = cfg["tzOffsets"]!!.jsonArray
        assertEquals(5, offsets.size)
        assertEquals(listOf("fromEpochMs", "offsetMinutes"), offsets[0].jsonObject.keys.toList())
        assertEquals(0, offsets[0].jsonObject["offsetMinutes"]!!.jsonPrimitive.int)
        assertEquals(60, offsets[1].jsonObject["offsetMinutes"]!!.jsonPrimitive.int)
        assertEquals(Instant.parse("2025-03-30T01:00:00Z").toEpochMilli(), offsets[1].jsonObject["fromEpochMs"]!!.jsonPrimitive.long)
        val geo = cfg["geolocation"]!!.jsonObject
        assertEquals(listOf("latitude", "longitude", "accuracyMeters"), geo.keys.toList())
        assertEquals("51.5074", geo["latitude"]!!.jsonPrimitive.content)

        val tz = PersonaToPrefs.shimConfig(p("win-chrome", "uk"), PersonaToPrefs.GOLDEN_CLOCK, tzShim = true)
        assertTrue(tz["tzShim"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `shimConfig for real-device is disabled with null fields`() {
        val cfg = PersonaToPrefs.shimConfig(p("real-device", "device"), PersonaToPrefs.GOLDEN_CLOCK)
        assertFalse(cfg["enabled"]!!.jsonPrimitive.boolean)
        assertFalse(cfg["desktop"]!!.jsonPrimitive.boolean)
        assertTrue(cfg["touch"]!!.jsonPrimitive.boolean)
        for (k in listOf("userAgent", "platform", "oscpu", "appVersion", "screen", "hardwareConcurrency", "webgl", "uaData", "timeZone", "tzOffsets", "geolocation")) {
            assertEquals(JsonNull, cfg[k], k)
        }
        assertFalse(cfg["tzShim"]!!.jsonPrimitive.boolean)
        assertEquals(JsonArray(emptyList()).size, 0)
        // a region with geolocation re-enables the shim even for real-device (identity fields stay null)
        val geo = PersonaToPrefs.shimConfig(p("real-device", "jp"), PersonaToPrefs.GOLDEN_CLOCK)
        assertTrue(geo["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, geo["userAgent"])
        assertTrue(geo["geolocation"] is JsonObject)
        // tzShim requested but no zone pinned -> stays false
        val noTz = PersonaToPrefs.shimConfig(p("win-firefox", "device"), PersonaToPrefs.GOLDEN_CLOCK, tzShim = true)
        assertFalse(noTz["tzShim"]!!.jsonPrimitive.boolean)
    }
}
