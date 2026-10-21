package app.hatch.ui.persona

import app.hatch.persona.GeoSpec
import app.hatch.persona.Identity
import app.hatch.persona.Presets
import app.hatch.persona.Region
import app.hatch.persona.ScreenSpec
import app.hatch.persona.UaDataSpec
import app.hatch.persona.WebGlSpec
import java.time.ZoneId

/**
 * Text-field drafts for the persona editor and their validation (pure, JVM-tested). The rules are the
 * `:persona` module's: a blank optional field is `null` ("engine default / do not override", `Persona.kt`
 * KDoc), ids match `PersonaManager.ID_PATTERN`, presets are read-only (`PersonaManager.saveIdentity` /
 * `saveRegion` reject preset ids). Custom personas are not golden-tested (`docs/persona-model.md`).
 */
data class IdentityDraft(
    val name: String = "",
    val desktop: Boolean = true,
    val userAgent: String = "",
    val platform: String = "",
    val oscpu: String = "",
    val appVersion: String = "",
    val layoutWidth: String = "1366",
    val screenWidth: String = "",
    val screenHeight: String = "",
    val availWidth: String = "",
    val availHeight: String = "",
    val colorDepth: String = "24",
    val dpr: String = "1.0",
    val hardwareConcurrency: String = "",
    val webglVendor: String = "",
    val webglRenderer: String = "",
    val chromeLike: Boolean = false,
    val touch: Boolean = false,
    /** Carried over from the duplicated identity (not editable field by field). */
    val uaData: UaDataSpec? = null,
) {
    companion object {
        fun from(i: Identity, name: String = i.name): IdentityDraft = IdentityDraft(
            name = name,
            desktop = i.desktop,
            userAgent = i.userAgent.orEmpty(),
            platform = i.platform.orEmpty(),
            oscpu = i.oscpu.orEmpty(),
            appVersion = i.appVersion.orEmpty(),
            layoutWidth = i.layoutWidth.toString(),
            screenWidth = i.screen?.width?.toString().orEmpty(),
            screenHeight = i.screen?.height?.toString().orEmpty(),
            availWidth = i.screen?.availWidth?.toString().orEmpty(),
            availHeight = i.screen?.availHeight?.toString().orEmpty(),
            colorDepth = i.screen?.colorDepth?.toString() ?: "24",
            dpr = i.screen?.dpr?.toString() ?: "1.0",
            hardwareConcurrency = i.hardwareConcurrency?.toString().orEmpty(),
            webglVendor = i.webgl?.vendor.orEmpty(),
            webglRenderer = i.webgl?.renderer.orEmpty(),
            chromeLike = i.chromeLike,
            touch = i.touch,
            uaData = i.uaData,
        )
    }
}

data class RegionDraft(
    val name: String = "",
    /** Comma-separated, most preferred first. */
    val languages: String = "",
    val locale: String = "",
    /** IANA zone id or blank for the device zone. */
    val timeZone: String = "",
    val latitude: String = "",
    val longitude: String = "",
    val accuracyMeters: String = "50",
) {
    companion object {
        fun from(r: Region, name: String = r.name): RegionDraft = RegionDraft(
            name = name,
            languages = r.languages.joinToString(", "),
            locale = r.locale,
            timeZone = r.timeZone.orEmpty(),
            latitude = r.geolocation?.latitude?.toString().orEmpty(),
            longitude = r.geolocation?.longitude?.toString().orEmpty(),
            accuracyMeters = r.geolocation?.accuracyMeters?.toString() ?: "50",
        )
    }
}

/** Identity form fields that can carry an inline problem ([Screen] is the combined width×height field). */
enum class IdentityField { Name, UserAgent, Platform, Screen, Dpr, Cores, WebglVendor, WebglRenderer, LayoutWidth, AvailSize, ColorDepth, Touch }

/** Region form fields that can carry an inline problem. */
enum class RegionField { Name, Languages, Locale, TimeZone, Latitude, Longitude, Accuracy }

/**
 * One validation defect: [message] is the full sentence (kept for the save-failure path and tests), [hint] the short
 * helper the form prints under the ringed [field] ("Use 1–64").
 */
data class FieldProblem<F>(val field: F, val message: String, val hint: String)

object PersonaDrafts {
    /** Same as `PersonaManager.ID_PATTERN`. */
    val ID_PATTERN = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
    private val languageTag = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$")

    /** A readable id from a name: lower-case, `[a-z0-9-]`, 64 chars max, never a preset id. */
    fun slug(name: String): String {
        val s = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(64)
        return if (s.isEmpty() || !ID_PATTERN.matches(s)) "custom" else s
    }

    /** [base] made unique against [existing] (and the preset ids) with `-2`, `-3`, ... suffixes. */
    fun uniqueId(base: String, existing: Set<String>): String {
        val taken = existing + Presets.identities().map { it.id } + Presets.regions().map { it.id }
        val root = slug(base)
        if (root !in taken) return root
        var n = 2
        while (true) {
            val candidate = (root.take(60) + "-" + n)
            if (candidate !in taken) return candidate
            n++
        }
    }

    private fun String.orNull(): String? = trim().takeIf { it.isNotEmpty() }

    /** Human-readable problems, one per defect, in form order; empty means the identity can be saved. */
    fun identityProblems(d: IdentityDraft): List<String> = identityFieldProblems(d).map { it.message }

    /**
     * The same defects as [identityProblems], each keyed by the form field that shows it inline: the editor rings
     * that field in the error colour and prints [FieldProblem.hint] under it ("Use 1–64"), and keeps Save disabled
     * while the list is non-empty.
     */
    fun identityFieldProblems(d: IdentityDraft): List<FieldProblem<IdentityField>> {
        val out = ArrayList<FieldProblem<IdentityField>>()
        fun add(field: IdentityField, message: String, hint: String) {
            out += FieldProblem(field, message, hint)
        }
        if (d.name.isBlank()) add(IdentityField.Name, "Name is empty", "Enter a name")
        val lw = d.layoutWidth.trim().toIntOrNull()
        if (lw == null || lw < 0) {
            add(IdentityField.LayoutWidth, "Layout width must be a whole number ≥ 0 (0 = engine default)", "Use a whole number; 0 = engine default")
        }
        val screenFields = listOf(d.screenWidth, d.screenHeight, d.availWidth, d.availHeight)
        val anyScreen = screenFields.any { it.isNotBlank() }
        if (anyScreen) {
            val w = d.screenWidth.trim().toIntOrNull()
            val h = d.screenHeight.trim().toIntOrNull()
            val aw = d.availWidth.trim().toIntOrNull()
            val ah = d.availHeight.trim().toIntOrNull()
            if (w == null || w <= 0 || h == null || h <= 0) {
                add(
                    IdentityField.Screen,
                    "Screen width and height must be positive whole numbers (or leave all four screen fields empty)",
                    "Use width×height, e.g. 1920×1080",
                )
            }
            if (aw == null || aw <= 0 || ah == null || ah <= 0) {
                add(IdentityField.AvailSize, "Available width and height must be positive whole numbers", "Use width×height, e.g. 1920×1040")
            }
            if (w != null && aw != null && aw > w) add(IdentityField.AvailSize, "Available width cannot exceed the screen width", "At most the screen width")
            if (h != null && ah != null && ah > h) add(IdentityField.AvailSize, "Available height cannot exceed the screen height", "At most the screen height")
            val cd = d.colorDepth.trim().toIntOrNull()
            if (cd == null || cd <= 0) add(IdentityField.ColorDepth, "Color depth must be a positive whole number", "Use a whole number, e.g. 24")
            val dpr = d.dpr.trim().toDoubleOrNull()
            if (dpr == null || dpr <= 0.0 || dpr > MAX_DPR) add(IdentityField.Dpr, "Device pixel ratio must be between 0 and 8", "Use more than 0, up to 8")
        }
        if (d.hardwareConcurrency.isNotBlank()) {
            val hc = d.hardwareConcurrency.trim().toIntOrNull()
            if (hc == null || hc < 1 || hc > MAX_CORES) {
                add(IdentityField.Cores, "CPU cores (hardwareConcurrency) must be 1–$MAX_CORES, or empty for the engine default", "Use 1–$MAX_CORES")
            }
        }
        if (d.webglVendor.isBlank() != d.webglRenderer.isBlank()) {
            val missing = if (d.webglVendor.isBlank()) IdentityField.WebglVendor else IdentityField.WebglRenderer
            add(missing, "WebGL vendor and renderer go together (fill both or neither)", "Fill both or neither")
        }
        if (d.desktop && d.touch) {
            add(
                IdentityField.Touch,
                "A desktop identity with touch on is detectable (touch events + fine pointer); turn touch off or desktop off",
                "Detectable on a desktop identity: turn touch or desktop off",
            )
        }
        return out
    }

    /** Builds the identity; call only when [identityProblems] is empty. */
    fun buildIdentity(id: String, d: IdentityDraft): Identity {
        require(ID_PATTERN.matches(id)) { "id must match $ID_PATTERN" }
        val screen = if (listOf(d.screenWidth, d.screenHeight, d.availWidth, d.availHeight).any { it.isNotBlank() }) {
            ScreenSpec(
                width = d.screenWidth.trim().toInt(),
                height = d.screenHeight.trim().toInt(),
                availWidth = d.availWidth.trim().toInt(),
                availHeight = d.availHeight.trim().toInt(),
                colorDepth = d.colorDepth.trim().toInt(),
                dpr = d.dpr.trim().toDouble(),
            )
        } else {
            null
        }
        return Identity(
            id = id,
            name = d.name.trim(),
            desktop = d.desktop,
            userAgent = d.userAgent.orNull(),
            platform = d.platform.orNull(),
            oscpu = d.oscpu.orNull(),
            appVersion = d.appVersion.orNull(),
            layoutWidth = d.layoutWidth.trim().toInt(),
            screen = screen,
            hardwareConcurrency = d.hardwareConcurrency.orNull()?.toInt(),
            webgl = if (d.webglVendor.isNotBlank()) WebGlSpec(d.webglVendor.trim(), d.webglRenderer.trim()) else null,
            chromeLike = d.chromeLike,
            uaData = if (d.chromeLike) d.uaData else null,
            touch = d.touch,
        )
    }

    fun parseLanguages(text: String): List<String> = text.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    fun isZoneId(id: String): Boolean = id.isNotBlank() && ZoneId.getAvailableZoneIds().contains(id.trim())

    /** Zone ids containing [query] (case-insensitive), region zones first, sorted; all zones for a blank query. */
    fun zoneSuggestions(query: String, limit: Int = 60): List<String> {
        val q = query.trim().lowercase()
        return ZoneId.getAvailableZoneIds().asSequence()
            .filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
            .filter { q.isEmpty() || it.lowercase().contains(q) }
            .sorted()
            .take(limit)
            .toList()
    }

    /** Human-readable problems, one per defect, in form order; empty means the region can be saved. */
    fun regionProblems(d: RegionDraft): List<String> = regionFieldProblems(d).map { it.message }

    /** The same defects as [regionProblems], keyed by the form field that shows each one inline. */
    fun regionFieldProblems(d: RegionDraft): List<FieldProblem<RegionField>> {
        val out = ArrayList<FieldProblem<RegionField>>()
        fun add(field: RegionField, message: String, hint: String) {
            out += FieldProblem(field, message, hint)
        }
        if (d.name.isBlank()) add(RegionField.Name, "Name is empty", "Enter a name")
        val langs = parseLanguages(d.languages)
        if (langs.isEmpty()) add(RegionField.Languages, "At least one language tag (e.g. en-US, en)", "At least one tag, e.g. en-US, en")
        langs.filterNot { languageTag.matches(it) }.forEach { add(RegionField.Languages, "Not a language tag: $it", "Not a language tag: $it") }
        if (d.locale.isBlank()) {
            add(RegionField.Locale, "Locale is empty (e.g. en-US)", "Use a tag such as en-US")
        } else if (!languageTag.matches(d.locale.trim())) {
            add(RegionField.Locale, "Not a locale tag: ${d.locale.trim()}", "Use a tag such as en-US")
        }
        if (d.timeZone.isNotBlank() && !isZoneId(d.timeZone)) {
            add(RegionField.TimeZone, "Unknown time zone: ${d.timeZone.trim()} (IANA id such as Europe/Berlin)", "Use an IANA id such as Europe/Berlin")
        }
        val geoFields = listOf(d.latitude, d.longitude)
        if (geoFields.any { it.isNotBlank() }) {
            val lat = d.latitude.trim().toDoubleOrNull()
            val lon = d.longitude.trim().toDoubleOrNull()
            if (lat == null || lat < -90.0 || lat > 90.0) add(RegionField.Latitude, "Latitude must be between -90 and 90", "Use −90 to 90")
            if (lon == null || lon < -180.0 || lon > 180.0) add(RegionField.Longitude, "Longitude must be between -180 and 180", "Use −180 to 180")
            val acc = d.accuracyMeters.trim().toDoubleOrNull()
            if (acc == null || acc <= 0.0) add(RegionField.Accuracy, "Accuracy must be a positive number of metres", "Use more than 0 m")
        }
        return out
    }

    /** The first problem per field (what the field shows inline); later ones still block Save. */
    fun <F> byField(problems: List<FieldProblem<F>>): Map<F, FieldProblem<F>> {
        val out = LinkedHashMap<F, FieldProblem<F>>()
        problems.forEach { if (it.field !in out) out[it.field] = it }
        return out
    }

    /** "1920×1080" for the combined Screen field; blank when both parts are blank. */
    fun screenText(width: String, height: String): String =
        if (width.isBlank() && height.isBlank()) "" else "${width.trim()}×${height.trim()}"

    /** Splits the combined Screen field ("1920×1080", "1920x1080", "1920 x 1080") into width and height text. */
    fun splitScreenText(text: String): Pair<String, String> {
        val parts = text.split('×', 'x', 'X', '*', limit = 2)
        return parts[0].trim() to parts.getOrElse(1) { "" }.trim()
    }

    const val MAX_CORES = 64
    private const val MAX_DPR = 8.0

    fun buildRegion(id: String, d: RegionDraft): Region {
        require(ID_PATTERN.matches(id)) { "id must match $ID_PATTERN" }
        val geo = if (d.latitude.isNotBlank() || d.longitude.isNotBlank()) {
            GeoSpec(d.latitude.trim().toDouble(), d.longitude.trim().toDouble(), d.accuracyMeters.trim().toDouble())
        } else {
            null
        }
        return Region(
            id = id,
            name = d.name.trim(),
            languages = parseLanguages(d.languages),
            locale = d.locale.trim(),
            timeZone = d.timeZone.orNull(),
            geolocation = geo,
        )
    }
}
