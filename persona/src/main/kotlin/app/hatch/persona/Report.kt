package app.hatch.persona

import kotlinx.serialization.Serializable

/**
 * The values the detection harness / in-app Self-check must observe for a persona. A null field means
 * "not asserted" (engine default or unknown device value, e.g. the device's own languages for the `device`
 * region or the real touch-point count for `real-device`).
 *
 * This is the PASS/FAIL contract shared by `harness/evaluate.js`, the Android Self-check screen and a future
 * iOS port; the signal ids match the harness result schema v1.
 */
@Serializable
data class ExpectedReport(
    /** `navigator.userAgent` and the echoed `user-agent` request header. */
    val userAgent: String?,
    val platform: String?,
    val oscpu: String?,
    val appVersion: String?,
    /** `navigator.maxTouchPoints`; null = any value > 0 (real touch device). */
    val maxTouchPoints: Int?,
    /** `"ontouchstart" in window || typeof TouchEvent !== "undefined"`. */
    val hasTouchEvent: Boolean,
    /** CSS-level `@media (pointer: fine)`. */
    val pointerFine: Boolean,
    /** CSS-level `@media (hover: hover)`. */
    val hoverHover: Boolean,
    /** `document.documentElement.clientWidth >= minLayoutWidth`; null = not asserted. */
    val minLayoutWidth: Int?,
    val screen: ScreenSpec?,
    val dpr: Double?,
    val hardwareConcurrency: Int?,
    /** `navigator.languages`; null = device languages (not asserted). */
    val languages: List<String>?,
    /** Echoed `accept-language` request header exactly as Gecko 158 emits it; null = not asserted. */
    val acceptLanguage: String?,
    /** `Intl.DateTimeFormat().resolvedOptions().timeZone`; null = device zone. */
    val timeZone: String?,
    val geolocation: GeoSpec?,
    val webgl: WebGlSpec?,
    /** Whether `navigator.userAgentData` must exist (`chromeLike`) or must be absent. */
    val userAgentData: Boolean,
    /** `Intl.DateTimeFormat().resolvedOptions().locale` family, i.e. the region locale; null = device. */
    val locale: String?,
)

fun Persona.expectedReport(): ExpectedReport {
    val id = identity
    val r = region
    val deviceRegion = r.id == Presets.DEVICE_REGION_ID
    return ExpectedReport(
        userAgent = id.userAgent,
        platform = id.platform,
        oscpu = id.oscpu,
        appVersion = id.appVersion,
        maxTouchPoints = if (id.touch) null else 0,
        hasTouchEvent = id.touch,
        pointerFine = !id.touch,
        hoverHover = !id.touch,
        minLayoutWidth = if (id.desktop) id.layoutWidth else null,
        screen = id.screen,
        dpr = id.screen?.dpr,
        hardwareConcurrency = id.hardwareConcurrency,
        languages = if (deviceRegion) null else GeckoAcceptLanguage.navigatorLanguages(GeckoAcceptLanguage.prefValue(r.languages)),
        acceptLanguage = if (deviceRegion) null else GeckoAcceptLanguage.header(GeckoAcceptLanguage.prefValue(r.languages)),
        timeZone = r.timeZone,
        geolocation = r.geolocation,
        webgl = id.webgl,
        userAgentData = id.chromeLike && id.uaData != null,
        locale = if (deviceRegion) null else r.locale,
    )
}
