package app.hatch.settings

import android.content.Context
import android.content.SharedPreferences
import app.hatch.applinks.AppLinksMode // hatch:rules
import app.hatch.ui.theme.HatchPalette
import app.hatch.ui.theme.ThemeMode
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

    /** Settings › Appearance: one of the fixed brand palettes (default Graphite). Read live through [changes]. */
    var palette: HatchPalette
        get() = HatchPalette.fromKey(prefs.getString(KEY_PALETTE, null))
        set(value) = prefs.edit().putString(KEY_PALETTE, value.key).apply()

    /**
     * Settings › Appearance: System / Light / Dark (default System). MainActivity passes it to `HatchTheme` and to
     * `AppCompatDelegate.setDefaultNightMode`, so Android Components' XML dialogs follow it too.
     */
    var themeMode: ThemeMode
        get() = ThemeMode.fromKey(prefs.getString(KEY_THEME_MODE, null))
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value.key).apply()

    // hatch:chrome begin
    /**
     * Scroll-to-hide for the top toolbar (M1 research "Dynamic toolbar"). Defaults OFF: the static toolbar is the
     * reliable layout until the scroll-driven variant is validated on a device. Surfaced in Settings by the rules
     * author; read live by `BrowserScreen` through [changes].
     */
    var hideToolbarOnScroll: Boolean
        get() = prefs.getBoolean(KEY_HIDE_TOOLBAR_ON_SCROLL, false)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_TOOLBAR_ON_SCROLL, value).apply()
    // hatch:chrome end

    // hatch:credentials begin — in-app logins store (both default ON; Fenix defaults: shouldAutofillLogins /
    // shouldPromptToSaveLogins = true). Autofill is read on every engine fetch and mirrored to
    // engine.settings.loginAutofillEnabled by the Settings toggle; "save" gates PromptFeature's save dialog.
    var loginAutofill: Boolean
        get() = prefs.getBoolean(KEY_LOGIN_AUTOFILL, true)
        set(value) = prefs.edit().putBoolean(KEY_LOGIN_AUTOFILL, value).apply()

    var saveLogins: Boolean
        get() = prefs.getBoolean(KEY_SAVE_LOGINS, true)
        set(value) = prefs.edit().putBoolean(KEY_SAVE_LOGINS, value).apply()
    // hatch:credentials end

    // hatch:rules begin — app links policy + per-host disable list for the extension's host rewrites
    /** What a link an installed app claims does (`AppLinksPolicy`); default: stay in Hatch (m1-inventory risk 3). */
    var appLinksMode: AppLinksMode
        get() = AppLinksMode.fromKey(prefs.getString(KEY_APP_LINKS_MODE, null))
        set(value) = prefs.edit().putString(KEY_APP_LINKS_MODE, value.key).apply()

    /**
     * `from` keys of `UrlRewriteRules.extensionHostRewrites` the user switched off. The extension contract has no
     * disabled flag, so `HostRewriteFilter.effective` filters the table app-side before every push.
     */
    var disabledHostRewrites: Set<String>
        get() = prefs.getStringSet(KEY_DISABLED_HOST_REWRITES, null)?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_DISABLED_HOST_REWRITES, value.toSet()).apply()
    // hatch:rules end

    // hatch:surfaces begin — Settings › Developer › "Eruda in menu": appends "Eruda devtools" to the main menu
    // (`mainMenuItems(erudaInMenu = …)`). Default off; Ctrl+Shift+I toggles Eruda regardless.
    var erudaInMenu: Boolean
        get() = prefs.getBoolean(KEY_ERUDA_IN_MENU, false)
        set(value) = prefs.edit().putBoolean(KEY_ERUDA_IN_MENU, value).apply()
    // hatch:surfaces end

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
        const val KEY_PALETTE = "palette"
        const val KEY_THEME_MODE = "themeMode"
        const val KEY_HIDE_TOOLBAR_ON_SCROLL = "hideToolbarOnScroll" // hatch:chrome
        // hatch:credentials begin
        const val KEY_LOGIN_AUTOFILL = "loginAutofill"
        const val KEY_SAVE_LOGINS = "saveLogins"
        // hatch:credentials end
        // hatch:rules begin
        const val KEY_APP_LINKS_MODE = "appLinksMode"
        const val KEY_DISABLED_HOST_REWRITES = "disabledHostRewrites"
        // hatch:rules end
        const val KEY_ERUDA_IN_MENU = "erudaInMenu" // hatch:surfaces
        const val DEFAULT_IDENTITY = "win-firefox"
        const val DEFAULT_REGION = "device"
        const val DEFAULT_HOME = "https://www.google.com/"
    }
}
