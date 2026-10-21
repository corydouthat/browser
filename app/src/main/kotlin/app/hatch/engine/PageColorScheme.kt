package app.hatch.engine

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.mediaquery.PreferredColorScheme

/**
 * RS-02: what web pages see as `prefers-color-scheme`. The contract is **the system's dark setting**, as in M1,
 * independent of Hatch's own Appearance (System / Light / Dark) and of whether the phone was rotated.
 *
 * Why it must be explicit (GeckoView 158, FIREFOX_158_0_RELEASE): with `COLOR_SCHEME_SYSTEM` Gecko answers from
 * `GeckoSystemStateListener.isNightMode()` (`GeckoRuntime.usesDarkTheme`, GeckoRuntime.java:884-893), which
 * `GeckoView.onConfigurationChanged` → `GeckoRuntime.configurationChanged` → `updateNightMode(newConfig.uiMode)`
 * (GeckoView.java:788-797, GeckoRuntime.java:1042-1045, GeckoSystemStateListener.java:222-230) overwrites with the
 * *Activity's* configuration. `AppCompatDelegate.setDefaultNightMode` overrides that configuration's uiMode, so a
 * rotation would hand the app theme to pages. An explicit LIGHT / DARK makes `usesDarkTheme` ignore it.
 *
 * The value comes from `Resources.getSystem()` (the global configuration, which no per-app night-mode override
 * touches) and is refreshed from an application-level [ComponentCallbacks] whenever the configuration changes.
 */
object PageColorScheme {
    /** The page-visible scheme for a system `uiMode`. */
    fun of(systemUiMode: Int): PreferredColorScheme =
        if ((systemUiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) PreferredColorScheme.Dark else PreferredColorScheme.Light

    /** Pins [engine]'s preferred colour scheme to the system's and keeps it in step (call once, on the main thread). */
    fun install(context: Context, engine: Engine) {
        fun sync() {
            val scheme = of(Resources.getSystem().configuration.uiMode)
            if (engine.settings.preferredColorScheme != scheme) engine.settings.preferredColorScheme = scheme
        }
        sync()
        context.applicationContext.registerComponentCallbacks(
            object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) = sync()

                @Deprecated("Deprecated in Java")
                override fun onLowMemory() = Unit
            },
        )
    }
}
