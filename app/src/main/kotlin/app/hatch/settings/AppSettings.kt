package app.hatch.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Small, synchronous app settings (SharedPreferences). Persona selection lives in [app.hatch.persona.PersonaManager]. */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("hatch", Context.MODE_PRIVATE)

    /** Gecko remote debugging (USB `about:debugging`). Applied at runtime creation; a restart makes it stick. */
    var remoteDebugging: Boolean
        get() = prefs.getBoolean(KEY_REMOTE_DEBUGGING, false)
        set(value) = prefs.edit().putBoolean(KEY_REMOTE_DEBUGGING, value).apply()

    /** Fallback JS Date/Intl shim for the region time zone (when `TZ` does not reach content processes). */
    var tzShim: Boolean
        get() = prefs.getBoolean(KEY_TZ_SHIM, false)
        set(value) = prefs.edit().putBoolean(KEY_TZ_SHIM, value).apply()

    /**
     * Keep Gecko touch events on for desktop personas (sites that need gestures: maps, drawing, carousels).
     * `dom.w3c_touch_events.enabled` stays at the engine default; the shim still hides the JS touch surface.
     */
    var keepTouchEvents: Boolean
        get() = prefs.getBoolean(KEY_KEEP_TOUCH_EVENTS, false)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_TOUCH_EVENTS, value).apply()

    /** Persona used when nothing is selected yet (fresh install / invalid stored selection). */
    var defaultIdentityId: String
        get() = prefs.getString(KEY_DEFAULT_IDENTITY, DEFAULT_IDENTITY) ?: DEFAULT_IDENTITY
        set(value) = prefs.edit().putString(KEY_DEFAULT_IDENTITY, value).apply()

    var defaultRegionId: String
        get() = prefs.getString(KEY_DEFAULT_REGION, DEFAULT_REGION) ?: DEFAULT_REGION
        set(value) = prefs.edit().putString(KEY_DEFAULT_REGION, value).apply()

    /** Homepage loaded into the first tab. */
    var homeUrl: String
        get() = prefs.getString(KEY_HOME_URL, DEFAULT_HOME) ?: DEFAULT_HOME
        set(value) = prefs.edit().putString(KEY_HOME_URL, value).apply()

    private val _changes = MutableStateFlow(0)

    /** Bumps whenever a setting changes so Compose screens can re-read. */
    val changes: StateFlow<Int> get() = _changes

    // SharedPreferencesImpl keeps listeners in a WeakHashMap: the instance must be strongly held here, or
    // it is collected at the next GC and `changes` stops ticking (PERF-05).
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _changes.value = _changes.value + 1 }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val KEY_REMOTE_DEBUGGING = "remoteDebugging"
        const val KEY_TZ_SHIM = "tzShim"
        const val KEY_KEEP_TOUCH_EVENTS = "keepTouchEvents"
        const val KEY_DEFAULT_IDENTITY = "defaultIdentityId"
        const val KEY_DEFAULT_REGION = "defaultRegionId"
        const val KEY_HOME_URL = "homeUrl"
        const val DEFAULT_IDENTITY = "win-firefox"
        const val DEFAULT_REGION = "device"
        const val DEFAULT_HOME = "https://www.google.com/"
    }
}
