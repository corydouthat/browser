package app.hatch.persona

import android.content.Context
import android.util.Log
import app.hatch.Components
import app.hatch.engine.GeckoPrefs
import app.hatch.engine.GeckoRuntimeFactory
import app.hatch.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.action.DefaultDesktopModeAction
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.window.WindowRequest
import mozilla.components.lib.state.ext.flowScoped
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

/**
 * Owns the active [Persona] (preset or user-defined), persists the selection and applies switches live:
 * Gecko prefs on the user branch ([GeckoPrefs]) -> per-tab session settings (UA override + desktop mode) -> engine default UA ->
 * runtime locales / auto-zoom -> extension push (shim config) -> yaml rewrite -> reload selected tab.
 * Only a `TZ` change needs a process restart ([PersonaToPrefs.needsRestart]).
 */
class PersonaManager(
    private val context: Context,
    private val settings: AppSettings,
    private val components: () -> Components,
) {
    sealed interface ApplyResult {
        data object Applied : ApplyResult
        data object NeedsRestart : ApplyResult
        data class Failed(val error: Throwable) : ApplyResult
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; prettyPrintIndent = "  " }
    private val selectionPrefs = context.getSharedPreferences("persona", Context.MODE_PRIVATE)
    private val userDir = File(context.filesDir, "personas")
    private val applyLock = Mutex()

    private val _identities = MutableStateFlow(loadIdentities())
    private val _regions = MutableStateFlow(loadRegions())
    private val _current = MutableStateFlow(loadSelection())

    /** Presets first, then user-defined identities from `filesDir/personas/identities/<id>.json`. */
    val identities: StateFlow<List<Identity>> get() = _identities
    val regions: StateFlow<List<Region>> get() = _regions
    val current: StateFlow<Persona> get() = _current

    /** The desktop identity the "Force desktop" switch returns to (last non-real-device identity used). */
    var lastDesktopIdentityId: String
        get() = selectionPrefs.getString(KEY_LAST_DESKTOP, settings.defaultIdentityId) ?: settings.defaultIdentityId
        private set(value) = selectionPrefs.edit().putString(KEY_LAST_DESKTOP, value).apply()

    fun identity(id: String): Identity? = _identities.value.firstOrNull { it.id == id }
    fun region(id: String): Region? = _regions.value.firstOrNull { it.id == id }
    fun isPreset(identity: Identity): Boolean = Presets.identity(identity.id) != null
    fun isPreset(region: Region): Boolean = Presets.region(region.id) != null

    /** Shim config for the extension; `tzShim` follows the app setting. */
    fun shimConfig(persona: Persona = _current.value): JsonObject =
        PersonaToPrefs.shimConfig(persona, tzShim = settings.tzShim)

    /** Prefs for [persona]; the "keep touch events" setting applies to every `touch = false` identity. */
    fun prefs(persona: Persona = _current.value): PrefSet = PersonaToPrefs.prefs(persona, settings.keepTouchEvents)

    // ---- new engine sessions (popups, target=_blank, restored tabs) --------------------------------

    private val seenSessions: MutableSet<EngineSession> = Collections.newSetFromMap(WeakHashMap())
    private var sessionScope: CoroutineScope? = null

    /**
     * Applies the persona's UA override + desktop mode to a session that was not created through
     * `CreateEngineSessionMiddleware` (which only consults `tab.content.desktopMode`): AC's
     * `GeckoEngineSession.onNewSession` builds popup / `target=_blank` sessions from `DefaultSettings`,
     * which copies the UA string but leaves `VIEWPORT_MODE_MOBILE` (HATCH-WC-05, verified at
     * FIREFOX_158_0_RELEASE). Safe to call repeatedly; `reload = false` so an in-flight load is not restarted.
     */
    fun applyToSession(session: EngineSession) {
        val p = _current.value
        runCatching {
            session.settings.userAgentString = p.identity.userAgent
            session.toggleDesktopMode(p.desktop, reload = false)
        }.onFailure { Log.w(TAG, "could not apply the persona to a new session", it) }
    }

    /**
     * Hooks every session the store links: (1) the parent's `onWindowRequest` fires synchronously inside
     * GeckoView's `onNewSession` *before* the new GeckoSession is opened, so the viewport mode is part of the
     * open settings; (2) belt and braces, every newly linked session gets the persona and the tab's
     * `desktopMode` flag. Idempotent.
     */
    fun observeSessions(store: BrowserStore) {
        if (sessionScope != null) return
        sessionScope = store.flowScoped(dispatcher = Dispatchers.Main) { flow ->
            flow
                .map { state -> state.tabs.mapNotNull { t -> t.engineState.engineSession?.let { t.id to it } } }
                .distinctUntilChanged()
                .collect { pairs ->
                    for ((tabId, session) in pairs) {
                        if (!seenSessions.add(session)) continue
                        applyToSession(session)
                        store.dispatch(ContentAction.UpdateTabDesktopMode(tabId, _current.value.desktop))
                        session.register(windowObserver)
                    }
                }
        }
    }

    private val windowObserver = object : EngineSession.Observer {
        override fun onWindowRequest(windowRequest: WindowRequest) {
            if (windowRequest.type != WindowRequest.Type.OPEN) return
            val child = runCatching { windowRequest.prepare() }.getOrNull() ?: return
            applyToSession(child)
        }
    }

    fun needsRestart(new: Persona): Boolean = PersonaToPrefs.needsRestart(_current.value, new)

    /** Real-device identity with the current region (the "persona off" control). */
    fun personaOff(): Persona = Persona(Presets.realDevice(), _current.value.region)

    /** Last desktop identity with the current region (the "force desktop" switch on). */
    fun forceDesktop(): Persona {
        val id = identity(lastDesktopIdentityId)?.takeIf { it.desktop } ?: Presets.default().identity
        return Persona(id, _current.value.region)
    }

    /**
     * Applies [new] to the live engine. Safe to call from any thread; the Gecko calls run on Main.
     * The selection is persisted even when a restart is needed, so the relaunch starts with the new persona
     * (yaml) and the matching `TZ`.
     */
    suspend fun apply(new: Persona, reload: Boolean = true): ApplyResult = applyLock.withLock {
        val old = _current.value
        val restart = PersonaToPrefs.needsRestart(old, new)
        try {
            val c = components()
            val prefResult = GeckoPrefs.apply(prefs(new))
            prefResult.filterValues { !it }.keys.takeIf { it.isNotEmpty() }?.let {
                Log.w(TAG, "prefs not set by Gecko: $it")
            }
            withContext(Dispatchers.Main) {
                val store = c.store
                for (tab in store.state.tabs) {
                    val session = tab.engineState.engineSession ?: continue
                    session.settings.userAgentString = new.identity.userAgent
                    session.toggleDesktopMode(new.desktop, reload = false)
                    store.dispatch(ContentAction.UpdateTabDesktopMode(tab.id, new.desktop))
                }
                store.dispatch(DefaultDesktopModeAction.DesktopModeUpdated(new.desktop))
                c.engine.settings.userAgentString = new.identity.userAgent
                c.runtime.settings.setLocales(GeckoRuntimeFactory.localesFor(new))
                c.runtime.settings.setInputAutoZoomEnabled(!new.desktop)
                c.bridge.sendPersona(shimConfig(new))
            }
            withContext(Dispatchers.IO) { GeckoRuntimeFactory.writeConfig(context, new) }
            persistSelection(new)
            if (new.identity.desktop && new.identity.id != Presets.REAL_DEVICE_ID) lastDesktopIdentityId = new.identity.id
            _current.value = new
            if (reload) withContext(Dispatchers.Main) { c.sessionUseCases.reload() }
            if (restart) ApplyResult.NeedsRestart else ApplyResult.Applied
        } catch (e: Exception) {
            Log.e(TAG, "persona apply failed", e)
            ApplyResult.Failed(e)
        }
    }

    /** Re-pushes the current shim config (after the `tzShim` setting changes). */
    suspend fun resendShim() {
        withContext(Dispatchers.Main) { components().bridge.sendPersona(shimConfig()) }
    }

    /**
     * Re-applies the current persona's prefs on the user branch; live, no reload. Called after the
     * `keepTouchEvents` setting changes and once per process before the session restore
     * (`Components.sessionRestore`), so the first load never depends on `prefs.js` having been flushed.
     */
    suspend fun reapplyPrefs() {
        val result = GeckoPrefs.apply(prefs())
        result.filterValues { !it }.keys.takeIf { it.isNotEmpty() }?.let { Log.w(TAG, "prefs not set by Gecko: $it") }
    }

    // ---- user-defined identities / regions ---------------------------------------------------

    fun saveIdentity(identity: Identity) {
        require(Presets.identity(identity.id) == null) { "preset identities are read-only" }
        require(identity.id.matches(ID_PATTERN)) { "id must match $ID_PATTERN" }
        val dir = File(userDir, "identities").apply { mkdirs() }
        File(dir, "${identity.id}.json").writeText(json.encodeToString(Identity.serializer(), identity))
        _identities.value = loadIdentities()
    }

    fun deleteIdentity(id: String) {
        if (Presets.identity(id) != null) return
        File(File(userDir, "identities"), "$id.json").delete()
        _identities.value = loadIdentities()
    }

    fun saveRegion(region: Region) {
        require(Presets.region(region.id) == null) { "preset regions are read-only" }
        require(region.id.matches(ID_PATTERN)) { "id must match $ID_PATTERN" }
        val dir = File(userDir, "regions").apply { mkdirs() }
        File(dir, "${region.id}.json").writeText(json.encodeToString(Region.serializer(), region))
        _regions.value = loadRegions()
    }

    fun deleteRegion(id: String) {
        if (Presets.region(id) != null) return
        File(File(userDir, "regions"), "$id.json").delete()
        _regions.value = loadRegions()
    }

    private fun loadIdentities(): List<Identity> =
        Presets.identities() + loadUser("identities") { json.decodeFromString(Identity.serializer(), it) }
            .filter { u -> Presets.identity(u.id) == null }

    private fun loadRegions(): List<Region> =
        Presets.regions() + loadUser("regions") { json.decodeFromString(Region.serializer(), it) }
            .filter { u -> Presets.region(u.id) == null }

    private fun <T> loadUser(sub: String, decode: (String) -> T): List<T> {
        val dir = File(userDir, sub)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.sortedBy { it.name }.mapNotNull { f ->
            runCatching { decode(f.readText()) }.onFailure { Log.w(TAG, "bad persona file ${f.name}", it) }.getOrNull()
        }
    }

    // ---- persistence -------------------------------------------------------------------------

    private fun loadSelection(): Persona {
        val identityId = selectionPrefs.getString(KEY_IDENTITY, null) ?: settings.defaultIdentityId
        val regionId = selectionPrefs.getString(KEY_REGION, null) ?: settings.defaultRegionId
        val identity = _identities.value.firstOrNull { it.id == identityId }
            ?: Presets.identity(settings.defaultIdentityId) ?: Presets.default().identity
        val region = _regions.value.firstOrNull { it.id == regionId }
            ?: Presets.region(settings.defaultRegionId) ?: Presets.default().region
        return Persona(identity, region)
    }

    private fun persistSelection(p: Persona) {
        selectionPrefs.edit().putString(KEY_IDENTITY, p.identity.id).putString(KEY_REGION, p.region.id).apply()
    }

    companion object {
        private const val TAG = "PersonaManager"
        private const val KEY_IDENTITY = "identityId"
        private const val KEY_REGION = "regionId"
        private const val KEY_LAST_DESKTOP = "lastDesktopIdentityId"
        private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
    }
}
