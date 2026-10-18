package app.hatch.engine

import android.content.Context
import app.hatch.BuildConfig
import app.hatch.persona.Persona
import app.hatch.persona.PersonaToPrefs
import app.hatch.persona.Presets
import app.hatch.settings.AppSettings
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import java.io.File

/**
 * Creates the single [GeckoRuntime]. Before creation the active persona is written to
 * `filesDir/geckoview-config.yaml` (prefs + `env: TZ`), which GeckoView reads once at
 * `GeckoRuntime.create` via `configFilePath` (read regardless of `debuggable`; verified in
 * GeckoRuntime.java at FIREFOX_158_0_RELEASE, lines 512-531). Live persona switches go through [GeckoPrefs]
 * and rewrite the yaml so the next cold start is already correct.
 */
object GeckoRuntimeFactory {
    const val CONFIG_FILE = "geckoview-config.yaml"

    fun configFile(context: Context): File = File(context.filesDir, CONFIG_FILE)

    /** Atomic write (temp file + rename) of the yaml for [persona] (with the "keep touch events" setting). */
    fun writeConfig(context: Context, persona: Persona, keepTouchEvents: Boolean = false): File {
        val target = configFile(context)
        val tmp = File(target.parentFile, "$CONFIG_FILE.tmp")
        val yaml = PersonaToPrefs.yaml(persona, keepTouchEvents)
        tmp.writeText(yaml)
        if (!tmp.renameTo(target)) {
            // rename across the same directory should never fail; fall back to a plain overwrite
            target.writeText(yaml)
            tmp.delete()
        }
        return target
    }

    /** `runtime.settings.locales` value for a persona: null (device locales) for the `device` region. */
    fun localesFor(persona: Persona): Array<String>? =
        if (persona.region.id == Presets.DEVICE_REGION_ID) null else arrayOf(persona.region.locale)

    fun create(context: Context, persona: Persona, settings: AppSettings): GeckoRuntime {
        val config = writeConfig(context, persona, settings.keepTouchEvents)
        // Every builder method below exists in GeckoRuntimeSettings.Builder at FIREFOX_158_0_RELEASE.
        val runtimeSettings = GeckoRuntimeSettings.Builder()
            .configFilePath(config.absolutePath)
            .remoteDebuggingEnabled(settings.remoteDebugging)
            .consoleOutput(BuildConfig.DEBUG)
            .extensionsProcessEnabled(true)
            .extensionsWebAPIEnabled(true)
            .locales(localesFor(persona))
            .fontInflation(false)
            .automaticFontSizeAdjustment(false)
            .forceUserScalableEnabled(true)
            .inputAutoZoomEnabled(!persona.desktop)
            .aboutConfigEnabled(true)
            .build()
        return GeckoRuntime.create(context, runtimeSettings)
    }
}
