package app.hatch.persona

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Locks `personas/golden/<identity>__<region>.prefs.json` to [PersonaToPrefs.goldenJson].
 *
 * Regenerate (after an intentional mapping change) with
 * `./gradlew --no-daemon --max-workers=1 --console=plain :persona:test -Dhatch.regenGoldens=true`
 * and commit the result. The headless harness reads these files; it never recomputes prefs.
 */
class GoldenTest {
    private val personasDir = File(System.getProperty("hatch.personasDir") ?: "../personas")
    private val goldenDir = File(personasDir, "golden")
    private val regen = System.getProperty("hatch.regenGoldens") == "true"

    /** The combinations that must always have a golden file. */
    private val required = listOf(
        "win-firefox" to "us-east",
        "mac-firefox" to "device",
        "linux-firefox" to "jp",
        "win-chrome" to "uk",
        "real-device" to "device",
        "win-firefox" to "device",
        "win-firefox" to "de",
        "real-device" to "jp",
    )

    private fun persona(identityId: String, regionId: String) = Persona(
        Presets.identity(identityId) ?: fail("unknown identity $identityId"),
        Presets.region(regionId) ?: fail("unknown region $regionId"),
    )

    @Test
    fun `golden files match PersonaToPrefs`() {
        assertTrue(personasDir.isDirectory, "personas dir missing: $personasDir")
        if (regen) {
            goldenDir.mkdirs()
            for ((i, r) in required) {
                val p = persona(i, r)
                File(goldenDir, "${p.key}.prefs.json").writeText(PersonaToPrefs.goldenJson(p))
            }
            println("Regenerated ${required.size} golden files in $goldenDir")
        }
        val files = goldenDir.listFiles { f -> f.name.endsWith(".prefs.json") }?.sortedBy { it.name } ?: emptyList()
        for ((i, r) in required) {
            assertTrue(files.any { it.name == "${i}__$r.prefs.json" }, "missing golden ${i}__$r.prefs.json (run with -Dhatch.regenGoldens=true)")
        }
        for (f in files) {
            val stem = f.name.removeSuffix(".prefs.json")
            val sep = stem.indexOf("__")
            assertTrue(sep > 0, "bad golden name ${f.name}")
            val p = persona(stem.substring(0, sep), stem.substring(sep + 2))
            assertEquals(PersonaToPrefs.goldenJson(p), f.readText(), "golden drift in ${f.name}; regenerate with -Dhatch.regenGoldens=true")
        }
    }

    @Test
    fun `golden json is sorted, indented and deterministic`() {
        val p = persona("win-firefox", "us-east")
        val a = PersonaToPrefs.goldenJson(p)
        val b = PersonaToPrefs.goldenJson(p)
        assertEquals(a, b)
        assertTrue(a.startsWith("{\n  \"clear\": ["))
        assertTrue(a.endsWith("}\n"))
        val topKeys = Regex("^  \"([a-zA-Z]+)\":", RegexOption.MULTILINE).findAll(a).map { it.groupValues[1] }.toList()
        assertEquals(listOf("clear", "env", "prefs", "shim"), topKeys)
        val prefKeys = Regex("^    \"([a-zA-Z0-9_.]+)\":", RegexOption.MULTILINE).findAll(a.substringAfter("\"prefs\": {").substringBefore("\n  }")).map { it.groupValues[1] }.toList()
        assertEquals(prefKeys.sorted(), prefKeys)
    }
}
