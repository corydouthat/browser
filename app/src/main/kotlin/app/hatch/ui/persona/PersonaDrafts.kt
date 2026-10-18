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

    fun identityProblems(d: IdentityDraft): List<String> {
        val out = ArrayList<String>()
        if (d.name.isBlank()) out += "Name is empty"
        val lw = d.layoutWidth.trim().toIntOrNull()
        if (lw == null || lw < 0) out += "Layout width must be a whole number ≥ 0 (0 = engine default)"
        val screenFields = listOf(d.screenWidth, d.screenHeight, d.availWidth, d.availHeight)
        val anyScreen = screenFields.any { it.isNotBlank() }
        if (anyScreen) {
            val w = d.screenWidth.trim().toIntOrNull()
            val h = d.screenHeight.trim().toIntOrNull()
            val aw = d.availWidth.trim().toIntOrNull()
            val ah = d.availHeight.trim().toIntOrNull()
            if (w == null || w <= 0 || h == null || h <= 0) out += "Screen width and height must be positive whole numbers (or leave all four screen fields empty)"
            if (aw == null || aw <= 0 || ah == null || ah <= 0) out += "Available width and height must be positive whole numbers"
            if (w != null && aw != null && aw > w) out += "Available width cannot exceed the screen width"
            if (h != null && ah != null && ah > h) out += "Available height cannot exceed the screen height"
            val cd = d.colorDepth.trim().toIntOrNull()
            if (cd == null || cd <= 0) out += "Color depth must be a positive whole number"
            val dpr = d.dpr.trim().toDoubleOrNull()
            if (dpr == null || dpr <= 0.0 || dpr > 8.0) out += "Device pixel ratio must be between 0 and 8"
        }
        if (d.hardwareConcurrency.isNotBlank()) {
            val hc = d.hardwareConcurrency.trim().toIntOrNull()
            if (hc == null || hc < 1 || hc > 256) out += "Hardware concurrency must be 1–256 (or empty for the engine default)"
        }
        if (d.webglVendor.isBlank() != d.webglRenderer.isBlank()) out += "WebGL vendor and renderer go together (fill both or neither)"
        if (d.desktop && d.touch) out += "A desktop identity with touch on is detectable (touch events + fine pointer); turn touch off or desktop off"
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

    fun regionProblems(d: RegionDraft): List<String> {
        val out = ArrayList<String>()
        if (d.name.isBlank()) out += "Name is empty"
        val langs = parseLanguages(d.languages)
        if (langs.isEmpty()) out += "At least one language tag (e.g. en-US, en)"
        langs.filterNot { languageTag.matches(it) }.forEach { out += "Not a language tag: $it" }
        if (d.locale.isBlank()) out += "Locale is empty (e.g. en-US)"
        else if (!languageTag.matches(d.locale.trim())) out += "Not a locale tag: ${d.locale.trim()}"
        if (d.timeZone.isNotBlank() && !isZoneId(d.timeZone)) out += "Unknown time zone: ${d.timeZone.trim()} (IANA id such as Europe/Berlin)"
        val geoFields = listOf(d.latitude, d.longitude)
        if (geoFields.any { it.isNotBlank() }) {
            val lat = d.latitude.trim().toDoubleOrNull()
            val lon = d.longitude.trim().toDoubleOrNull()
            if (lat == null || lat < -90.0 || lat > 90.0) out += "Latitude must be between -90 and 90"
            if (lon == null || lon < -180.0 || lon > 180.0) out += "Longitude must be between -180 and 180"
            val acc = d.accuracyMeters.trim().toDoubleOrNull()
            if (acc == null || acc <= 0.0) out += "Accuracy must be a positive number of metres"
        }
        return out
    }

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
