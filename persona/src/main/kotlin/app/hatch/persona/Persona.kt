package app.hatch.persona

import kotlinx.serialization.Serializable

/**
 * Engine-neutral persona model: `Persona = Identity × Region`, switched independently.
 *
 * The model is deliberately free of Android/Gecko types so the same data drives the Android app,
 * the headless Firefox harness (via [PersonaToPrefs]) and a future iOS port.
 */
object PersonaModule {
    const val SCHEMA_VERSION = 1
}

/** Values the JS shim reports for `window.screen` / `devicePixelRatio`. */
@Serializable
data class ScreenSpec(
    val width: Int,
    val height: Int,
    val availWidth: Int,
    val availHeight: Int,
    val colorDepth: Int = 24,
    val dpr: Double = 1.0,
)

/** `WEBGL_debug_renderer_info` strings the JS shim reports. */
@Serializable
data class WebGlSpec(val vendor: String, val renderer: String)

@Serializable
data class Brand(val brand: String, val version: String)

/** `navigator.userAgentData` shape for Chrome-like identities (Gecko has no native userAgentData). */
@Serializable
data class UaDataSpec(
    val brands: List<Brand>,
    val platform: String,
    val platformVersion: String,
    val architecture: String,
    val bitness: String,
    val model: String,
)

/**
 * What the browser pretends to be. Null fields mean "engine default / do not override".
 *
 * @property desktop desktop UA mode + desktop viewport mode on every tab.
 * @property layoutWidth minimum CSS layout width (`browser.viewport.desktopWidth`).
 * @property chromeLike the shim also installs `navigator.userAgentData` from [uaData].
 * @property touch `true` keeps touch events / coarse pointer; `false` removes them.
 */
@Serializable
data class Identity(
    val id: String,
    val name: String,
    val desktop: Boolean,
    val userAgent: String?,
    val platform: String?,
    val oscpu: String?,
    val appVersion: String?,
    val layoutWidth: Int = 1366,
    val screen: ScreenSpec?,
    val hardwareConcurrency: Int?,
    val webgl: WebGlSpec?,
    val chromeLike: Boolean = false,
    val uaData: UaDataSpec? = null,
    val touch: Boolean = false,
)

@Serializable
data class GeoSpec(val latitude: Double, val longitude: Double, val accuracyMeters: Double = 50.0)

/**
 * Where the browser pretends to be.
 *
 * @property languages Accept-Language / `navigator.languages` order, most preferred first.
 * @property locale requested app locale (`intl.locale.requested`, `GeckoRuntimeSettings.locales`).
 * @property timeZone IANA zone id, or null for the device zone.
 * @property geolocation fake position for the JS shim, or null for the real device position.
 */
@Serializable
data class Region(
    val id: String,
    val name: String,
    val languages: List<String>,
    val locale: String,
    val timeZone: String?,
    val geolocation: GeoSpec?,
)

@Serializable
data class Persona(val identity: Identity, val region: Region) {
    val desktop: Boolean get() = identity.desktop

    /** `<identityId>__<regionId>`, also the golden file stem. */
    val key: String get() = "${identity.id}__${region.id}"
}
