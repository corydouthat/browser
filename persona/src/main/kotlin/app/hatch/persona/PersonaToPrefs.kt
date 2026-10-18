package app.hatch.persona

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** A typed Gecko pref value (`prefs:` in geckoview-config.yaml are bool/int/string). */
sealed interface PrefValue {
    @JvmInline value class Str(val v: String) : PrefValue
    @JvmInline value class Num(val v: Int) : PrefValue
    @JvmInline value class Bool(val v: Boolean) : PrefValue
}

/**
 * Prefs to set and prefs to clear (back to the engine default) for a persona.
 * Iteration order of [set] is the mapping-table order; consumers that need determinism sort by key.
 */
data class PrefSet(val set: Map<String, PrefValue>, val clear: Set<String>)

/** One row of the precomputed UTC-offset table the JS Date/Intl shim fallback uses. */
data class TzOffset(val fromEpochMs: Long, val offsetMinutes: Int)

/**
 * The single "field -> mechanism" table: turns a [Persona] into Gecko prefs, process env, a
 * `geckoview-config.yaml`, and the JSON the built-in extension inlines into `persona-shim.js`.
 *
 * Mapping (see docs/persona-model.md and the plan):
 *  - desktop identity: `browser.viewport.desktopWidth=layoutWidth`, touch removal
 *    (`dom.w3c_touch_events.enabled=0`, `dom.maxtouchpoints.testing.value=0`), fine+hover pointer
 *    (`ui.primaryPointerCapabilities=6`, `ui.allPointerCapabilities=6`), platform/oscpu/appversion
 *    overrides, `general.useragent.override` (so headless desktop Firefox reports the same UA; on Android
 *    the per-session `userAgentString` override wins anyway).
 *  - `touch = false` identities also set `layout.accessiblecaret.enabled=true`: Gecko's touch selection UI
 *    (long-press word selection + handles) is otherwise gated on the touch pref
 *    (`PresShell::AccessibleCaretEnabled` at FIREFOX_158_0_RELEASE); touch identities clear it so the
 *    engine's `enabled_on_touch` default applies.
 *  - `keepTouchEvents` (an app setting for sites that need gestures): `dom.w3c_touch_events.enabled` is left
 *    at the engine default instead of 0 while everything else of the desktop table stays; the shim still
 *    hides the JS touch surface, `document.createEvent("TouchEvent")` becomes detectable (documented).
 *  - `real-device` identity: every identity pref above goes to `clear`.
 *  - region: `intl.accept_languages` (Gecko format `"en-US, en"`) + `intl.locale.requested`; the `device`
 *    region clears both so the phone keeps its own languages. Region prefs are independent of the identity
 *    (a `real-device` identity with the `jp` region still sets the intl prefs).
 *  - `TZ` env only when `region.timeZone != null`; env changes need a process restart.
 *  - Everything the engine cannot do (screen, dpr, hardwareConcurrency, WebGL, geolocation, userAgentData,
 *    optional Date/Intl shim) travels in [shimConfig].
 */
object PersonaToPrefs {
    const val PREF_DESKTOP_WIDTH = "browser.viewport.desktopWidth"
    const val PREF_TOUCH_EVENTS = "dom.w3c_touch_events.enabled"
    const val PREF_MAX_TOUCH_POINTS = "dom.maxtouchpoints.testing.value"
    const val PREF_PRIMARY_POINTER = "ui.primaryPointerCapabilities"
    const val PREF_ALL_POINTERS = "ui.allPointerCapabilities"
    const val PREF_PLATFORM = "general.platform.override"
    const val PREF_OSCPU = "general.oscpu.override"
    const val PREF_APPVERSION = "general.appversion.override"
    const val PREF_USERAGENT = "general.useragent.override"
    const val PREF_ACCESSIBLE_CARET = "layout.accessiblecaret.enabled"
    const val PREF_ACCEPT_LANGUAGES = "intl.accept_languages"
    const val PREF_LOCALE_REQUESTED = "intl.locale.requested"

    /** `ui.*PointerCapabilities` bit set: 2 = fine, 4 = hover (1 = coarse). */
    const val POINTER_FINE_HOVER = 6

    /** Every pref the identity half of the table may touch, in table order. */
    val identityPrefs: List<String> = listOf(
        PREF_DESKTOP_WIDTH, PREF_TOUCH_EVENTS, PREF_MAX_TOUCH_POINTS, PREF_PRIMARY_POINTER, PREF_ALL_POINTERS,
        PREF_ACCESSIBLE_CARET, PREF_PLATFORM, PREF_OSCPU, PREF_APPVERSION, PREF_USERAGENT,
    )

    /** Every pref the region half of the table may touch. */
    val regionPrefs: List<String> = listOf(PREF_ACCEPT_LANGUAGES, PREF_LOCALE_REQUESTED)

    /** All prefs this mapper owns (the app clears exactly these when switching to engine defaults). */
    val allPrefs: List<String> = identityPrefs + regionPrefs

    /** Fixed instant used by [goldenJson] so the `tzOffsets` table in the goldens is deterministic. */
    val GOLDEN_CLOCK: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)

    /**
     * @param keepTouchEvents leave `dom.w3c_touch_events.enabled` at the engine default for a `touch = false`
     *   identity (sites that need real gestures: maps, drawing, carousels); the rest of the table is unchanged.
     */
    fun prefs(p: Persona, keepTouchEvents: Boolean = false): PrefSet {
        val set = LinkedHashMap<String, PrefValue>()
        val clear = LinkedHashSet<String>()
        val id = p.identity
        if (id.desktop) {
            set[PREF_DESKTOP_WIDTH] = PrefValue.Num(id.layoutWidth)
        } else {
            clear += PREF_DESKTOP_WIDTH
        }
        if (id.touch) {
            clear += listOf(PREF_TOUCH_EVENTS, PREF_MAX_TOUCH_POINTS, PREF_PRIMARY_POINTER, PREF_ALL_POINTERS, PREF_ACCESSIBLE_CARET)
        } else {
            if (keepTouchEvents) clear += PREF_TOUCH_EVENTS else set[PREF_TOUCH_EVENTS] = PrefValue.Num(0)
            set[PREF_MAX_TOUCH_POINTS] = PrefValue.Num(0)
            set[PREF_PRIMARY_POINTER] = PrefValue.Num(POINTER_FINE_HOVER)
            set[PREF_ALL_POINTERS] = PrefValue.Num(POINTER_FINE_HOVER)
            set[PREF_ACCESSIBLE_CARET] = PrefValue.Bool(true)
        }
        setOrClear(set, clear, PREF_PLATFORM, id.platform)
        setOrClear(set, clear, PREF_OSCPU, id.oscpu)
        setOrClear(set, clear, PREF_APPVERSION, id.appVersion)
        setOrClear(set, clear, PREF_USERAGENT, id.userAgent)

        val region = p.region
        if (region.id == Presets.DEVICE_REGION_ID) {
            clear += regionPrefs
        } else {
            set[PREF_ACCEPT_LANGUAGES] = PrefValue.Str(GeckoAcceptLanguage.prefValue(region.languages))
            set[PREF_LOCALE_REQUESTED] = PrefValue.Str(region.locale)
        }
        return PrefSet(set, clear)
    }

    private fun setOrClear(set: MutableMap<String, PrefValue>, clear: MutableSet<String>, name: String, v: String?) {
        if (v != null) set[name] = PrefValue.Str(v) else clear += name
    }

    /** Process environment for the persona: `TZ` only when the region pins a zone. */
    fun env(p: Persona): Map<String, String> {
        val tz = p.region.timeZone ?: return emptyMap()
        return mapOf("TZ" to tz)
    }

    /** A restart is needed only when the process environment changes (env is read once at startup). */
    fun needsRestart(old: Persona, new: Persona): Boolean = env(old) != env(new)

    /**
     * `geckoview-config.yaml` content (read once by `GeckoRuntime.create` via `configFilePath`; parsed by
     * `org.mozilla.gecko.util.DebugConfig` with SnakeYAML into `prefs: Map<String,Object>`,
     * `env: Map<String,String>`, `args: List<String>`). Cleared prefs cannot be expressed in the yaml; they are
     * simply absent (engine default), which is the same thing for a fresh process.
     */
    fun yaml(p: Persona, keepTouchEvents: Boolean = false): String {
        val ps = prefs(p, keepTouchEvents)
        val sb = StringBuilder()
        sb.append("# Generated by Hatch PersonaToPrefs for persona ").append(p.key).append(" -- do not edit.\n")
        if (ps.set.isEmpty()) {
            sb.append("prefs: {}\n")
        } else {
            sb.append("prefs:\n")
            for ((k, v) in ps.set.toSortedMap()) {
                sb.append("  ").append(yamlString(k)).append(": ").append(yamlValue(v)).append('\n')
            }
        }
        val env = env(p)
        if (env.isEmpty()) {
            sb.append("env: {}\n")
        } else {
            sb.append("env:\n")
            for ((k, v) in env.toSortedMap()) {
                sb.append("  ").append(yamlString(k)).append(": ").append(yamlString(v)).append('\n')
            }
        }
        sb.append("args: []\n")
        return sb.toString()
    }

    private fun yamlValue(v: PrefValue): String = when (v) {
        is PrefValue.Str -> yamlString(v.v)
        is PrefValue.Num -> v.v.toString()
        is PrefValue.Bool -> v.v.toString()
    }

    /** Always double-quoted so "1.0", "true", "null", "" and ": " never get re-typed by the YAML parser. */
    internal fun yamlString(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 || c.code == 0x7f -> sb.append(String.format("\\x%02x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /**
     * UTC-offset table for [zoneId] covering now-1y .. now+1y: the first row is the offset in force at the
     * window start, then one row per transition inside the window. `offsetMinutes` is east-positive
     * (the opposite sign of JS `Date.getTimezoneOffset()`). Null when [zoneId] is unknown to java.time.
     */
    fun tzOffsets(zoneId: String, clock: Clock = Clock.systemUTC()): List<TzOffset>? {
        val zone = runCatching { ZoneId.of(zoneId) }.getOrNull() ?: return null
        val rules = zone.rules
        val now = clock.instant()
        val start = now.minus(365, ChronoUnit.DAYS)
        val end = now.plus(365, ChronoUnit.DAYS)
        val out = ArrayList<TzOffset>()
        out += TzOffset(start.toEpochMilli(), rules.getOffset(start).totalSeconds / 60)
        var t = rules.nextTransition(start)
        while (t != null && t.instant.isBefore(end)) {
            out += TzOffset(t.instant.toEpochMilli(), t.offsetAfter.totalSeconds / 60)
            t = rules.nextTransition(t.instant)
        }
        return out
    }

    /**
     * The JSON object the app sends to the extension (`{"type":"persona","persona":...}`) and the extension
     * inlines into persona-shim.js as `__PERSONA__`. Field order follows the SHIM CONFIG CONTRACT.
     *
     * `enabled=false` only for the `real-device` identity with nothing region-specific for the shim to do
     * (no geolocation, no tz shim); then the extension registers no content script at all.
     *
     * @param clock drives the `tzOffsets` window; pass a fixed clock for deterministic output.
     * @param tzShim `true` tells the shim to patch `Date`/`Intl` from `tzOffsets` (fallback when `TZ` env
     *   does not reach content processes).
     */
    fun shimConfig(p: Persona, clock: Clock = Clock.systemUTC(), tzShim: Boolean = false): JsonObject {
        val id = p.identity
        val r = p.region
        val realDevice = id.id == Presets.REAL_DEVICE_ID
        val enabled = !realDevice || r.geolocation != null || (tzShim && r.timeZone != null)
        val offsets = r.timeZone?.let { tzOffsets(it, clock) }
        return buildJsonObject {
            put("enabled", enabled)
            put("desktop", id.desktop)
            put("touch", id.touch)
            put("userAgent", id.userAgent)
            put("platform", id.platform)
            put("oscpu", id.oscpu)
            put("appVersion", id.appVersion)
            put("layoutWidth", id.layoutWidth)
            val s = id.screen
            if (s == null) put("screen", JsonNull) else putJsonObject("screen") {
                put("width", s.width)
                put("height", s.height)
                put("availWidth", s.availWidth)
                put("availHeight", s.availHeight)
                put("colorDepth", s.colorDepth)
                put("dpr", s.dpr)
            }
            put("hardwareConcurrency", id.hardwareConcurrency)
            val w = id.webgl
            if (w == null) put("webgl", JsonNull) else putJsonObject("webgl") {
                put("vendor", w.vendor)
                put("renderer", w.renderer)
            }
            put("chromeLike", id.chromeLike)
            val u = id.uaData
            if (u == null) put("uaData", JsonNull) else putJsonObject("uaData") {
                putJsonArray("brands") {
                    for (b in u.brands) addJsonObject {
                        put("brand", b.brand)
                        put("version", b.version)
                    }
                }
                put("platform", u.platform)
                put("platformVersion", u.platformVersion)
                put("architecture", u.architecture)
                put("bitness", u.bitness)
                put("model", u.model)
                put("mobile", false)
            }
            putJsonArray("languages") { for (l in r.languages) add(l) }
            put("locale", r.locale)
            put("timeZone", r.timeZone)
            put("tzShim", tzShim && r.timeZone != null)
            if (offsets == null) put("tzOffsets", JsonNull) else putJsonArray("tzOffsets") {
                for (o in offsets) addJsonObject {
                    put("fromEpochMs", o.fromEpochMs)
                    put("offsetMinutes", o.offsetMinutes)
                }
            }
            val g = r.geolocation
            if (g == null) put("geolocation", JsonNull) else putJsonObject("geolocation") {
                put("latitude", g.latitude)
                put("longitude", g.longitude)
                put("accuracyMeters", g.accuracyMeters)
            }
        }
    }

    private val goldenFormat = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /**
     * `{"prefs":{name:value},"clear":[...],"env":{...},"shim":<shim config>}` with keys sorted recursively,
     * 2-space indent, trailing newline. The shim config is computed with [GOLDEN_CLOCK] (2026-01-01T00:00Z),
     * so `tzOffsets` are the transitions of 2025-01-01 .. 2026-12-31 and the file is stable across runs.
     * The headless harness reads `prefs`/`env`/`shim` from these files instead of recomputing them.
     */
    fun goldenJson(p: Persona): String {
        val ps = prefs(p)
        val root = buildJsonObject {
            putJsonObject("prefs") {
                for ((k, v) in ps.set) {
                    when (v) {
                        is PrefValue.Str -> put(k, v.v)
                        is PrefValue.Num -> put(k, v.v)
                        is PrefValue.Bool -> put(k, v.v)
                    }
                }
            }
            putJsonArray("clear") { for (c in ps.clear.sorted()) add(c) }
            putJsonObject("env") { for ((k, v) in env(p)) put(k, v) }
            put("shim", shimConfig(p, GOLDEN_CLOCK))
        }
        return goldenFormat.encodeToString(JsonElement.serializer(), sortedDeep(root)) + "\n"
    }

    private fun sortedDeep(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.entries.sortedBy { it.key }.associate { it.key to sortedDeep(it.value) })
        is JsonArray -> JsonArray(e.map(::sortedDeep))
        is JsonPrimitive -> e
    }
}
