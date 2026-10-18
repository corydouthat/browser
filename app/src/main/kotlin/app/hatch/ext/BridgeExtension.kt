package app.hatch.ext

import android.util.Log
import app.hatch.persona.HostRewrite
import app.hatch.rules.SiteRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.lib.state.ext.flowScoped
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

/**
 * App side of the built-in "bridge" WebExtension (assets/extension, MV2).
 *
 * Background port "hatch": the extension connects once at startup (with retry/backoff); on every connect the app
 * pushes the full state `{type:"state", persona, rules, hostRewrites, settings}`; later changes are pushed as
 * `persona` / `rules` / `hostRewrites` messages. The extension answers with `hello` and `log` messages.
 *
 * Per-tab content port "hatch-tab": `content/bridge.js` connects from the top frame of every page; the app registers
 * `registerContentMessageHandler(session, "hatch-tab")` for every EngineSession as soon as it is linked to a tab
 * (store observer), giving a per-tab channel for the Eruda toggle and Self-check report upload.
 */
class BridgeExtension(
    private val engine: Engine,
    private val storeProvider: () -> BrowserStore,
    private val stateProvider: () -> State,
) {
    private val store: BrowserStore get() = storeProvider()

    /** Full state pushed on every background port connect. */
    data class State(val persona: JsonObject, val rules: List<SiteRule>, val rulesJson: JSONArray, val hostRewrites: List<HostRewrite>)

    data class LogLine(val level: String, val message: String, val at: Long = System.currentTimeMillis())

    /** `{type:"selfcheck-report"}` from a tab: the harness result JSON (or null) plus the page URL and error. */
    data class SelfCheckReport(val session: EngineSession?, val report: JSONObject?, val url: String?, val error: String?)

    private var extension: WebExtension? = null
    private var backgroundPort: Port? = null
    private val contentPorts: MutableMap<EngineSession, Port> = Collections.synchronizedMap(IdentityHashMap())
    private val attached: MutableSet<EngineSession> = Collections.newSetFromMap(WeakHashMap())
    private var storeScope: CoroutineScope? = null

    private val _status = MutableStateFlow("not installed")
    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    private val _reports = MutableSharedFlow<SelfCheckReport>(extraBufferCapacity = 4)
    private val _ready = MutableSharedFlow<Pair<EngineSession, String>>(extraBufferCapacity = 16)

    /** Human-readable state for Settings ("connected v0.1.0", "installing", error text). */
    val status: StateFlow<String> get() = _status

    /** Last 200 `log` messages from the extension background (plus install events). */
    val logs: StateFlow<List<LogLine>> get() = _logs
    val reports: SharedFlow<SelfCheckReport> get() = _reports

    /** `{type:"ready", url}` from a content script: the page's bridge port is up. */
    val ready: SharedFlow<Pair<EngineSession, String>> get() = _ready

    val isConnected: Boolean get() = backgroundPort != null

    /** Installs the built-in extension and starts watching the store for engine sessions. Idempotent. */
    fun install() {
        if (extension != null) return
        _status.value = "installing"
        engine.installBuiltInWebExtension(
            id = EXTENSION_ID,
            url = EXTENSION_URL,
            onSuccess = { ext ->
                extension = ext
                _status.value = "installed, waiting for background port"
                log("info", "built-in extension installed: ${ext.id}")
                ext.registerBackgroundMessageHandler(BACKGROUND_PORT, backgroundHandler)
                store.state.tabs.mapNotNull { it.engineState.engineSession }.forEach { attachSession(it) }
                observeStore()
            },
            onError = { e ->
                _status.value = "install failed: ${e.message}"
                Log.e(TAG, "built-in extension install failed", e)
                log("error", "install failed: ${e.message}")
            },
        )
    }

    private fun observeStore() {
        if (storeScope != null) return
        storeScope = store.flowScoped(dispatcher = Dispatchers.Main) { flow ->
            flow
                .map { state -> state.tabs.mapNotNull { it.engineState.engineSession } }
                .distinctUntilChanged()
                .collect { sessions -> sessions.forEach { attachSession(it) } }
        }
    }

    /** Registers the per-tab "hatch-tab" handler on [session] (before its first page can connect). */
    fun attachSession(session: EngineSession) {
        val ext = extension ?: return
        if (session in attached) return
        if (ext.hasContentMessageHandler(session, CONTENT_PORT)) {
            attached += session
            return
        }
        ext.registerContentMessageHandler(session, CONTENT_PORT, contentHandler)
        attached += session
    }

    // ---- app -> extension --------------------------------------------------------------------

    fun sendPersona(config: JsonObject) = post(JSONObject().put("type", "persona").put("persona", JSONObject(config.toString())))

    fun sendRules(rules: JSONArray) = post(JSONObject().put("type", "rules").put("rules", rules))

    fun sendHostRewrites(rules: List<HostRewrite>) =
        post(JSONObject().put("type", "hostRewrites").put("rules", hostRewritesJson(rules)))

    fun sendState() {
        val s = stateProvider()
        post(
            JSONObject()
                .put("type", "state")
                .put("persona", JSONObject(s.persona.toString()))
                .put("rules", s.rulesJson)
                .put("hostRewrites", hostRewritesJson(s.hostRewrites))
                .put("settings", JSONObject()),
        )
    }

    /** Toggles Eruda in [session]'s page. Returns false when the page has no bridge port (e.g. about:blank). */
    fun toggleEruda(session: EngineSession): Boolean = postToSession(session, JSONObject().put("type", "eruda"))

    /** Asks the harness page in [session] to deliver its result (`selfcheck-report`). */
    fun requestSelfCheckReport(session: EngineSession): Boolean = postToSession(session, JSONObject().put("type", "selfcheck-run"))

    fun hasContentPort(session: EngineSession): Boolean = contentPorts.containsKey(session)

    private fun post(message: JSONObject): Boolean {
        val port = backgroundPort ?: return false
        return runCatching { port.postMessage(message) }
            .onFailure { Log.w(TAG, "background port post failed", it); backgroundPort = null }
            .isSuccess
    }

    private fun postToSession(session: EngineSession, message: JSONObject): Boolean {
        val port = contentPorts[session] ?: return false
        return runCatching { port.postMessage(message) }
            .onFailure { Log.w(TAG, "content port post failed", it); contentPorts.remove(session) }
            .isSuccess
    }

    private fun hostRewritesJson(rules: List<HostRewrite>): JSONArray {
        val arr = JSONArray()
        for (r in rules) arr.put(JSONObject().put("from", r.from).put("to", r.to))
        return arr
    }

    // ---- extension -> app --------------------------------------------------------------------

    private val backgroundHandler = object : MessageHandler {
        override fun onPortConnected(port: Port) {
            backgroundPort = port
            _status.value = "connected"
            log("info", "background port connected")
            sendState()
        }

        override fun onPortDisconnected(port: Port) {
            if (backgroundPort === port) backgroundPort = null
            _status.value = "background port disconnected"
            log("warn", "background port disconnected")
        }

        override fun onPortMessage(message: Any, port: Port) {
            val obj = message as? JSONObject ?: return
            when (obj.optString("type")) {
                "hello" -> {
                    val version = obj.optString("version")
                    _status.value = "connected v$version"
                    log("info", "hello from extension v$version")
                }
                "log" -> log(obj.optString("level", "info"), obj.optString("message"))
                else -> log("warn", "unknown message from background: ${obj.optString("type")}")
            }
        }
    }

    private val contentHandler = object : MessageHandler {
        override fun onPortConnected(port: Port) {
            port.engineSession?.let { contentPorts[it] = port }
        }

        override fun onPortDisconnected(port: Port) {
            val session = port.engineSession ?: return
            if (contentPorts[session] === port) contentPorts.remove(session)
        }

        override fun onPortMessage(message: Any, port: Port) {
            val obj = message as? JSONObject ?: return
            val session = port.engineSession
            when (obj.optString("type")) {
                "ready" -> {
                    val url = obj.optString("url")
                    if (session != null) _ready.tryEmit(session to url)
                }
                "selfcheck-report" -> {
                    val report = obj.optJSONObject("report")
                    val error = if (obj.has("error")) obj.optString("error") else null
                    _reports.tryEmit(SelfCheckReport(session, report, obj.optString("url", null), error))
                }
            }
        }
    }

    private fun log(level: String, message: String) {
        when (level) {
            "error" -> Log.e(TAG, message)
            "warn" -> Log.w(TAG, message)
            else -> Log.i(TAG, message)
        }
        _logs.value = (_logs.value + LogLine(level, message)).takeLast(MAX_LOGS)
    }

    companion object {
        private const val TAG = "BridgeExtension"
        const val EXTENSION_ID = "bridge@hatch.app"
        const val EXTENSION_URL = "resource://android/assets/extension/"
        const val BACKGROUND_PORT = "hatch"
        const val CONTENT_PORT = "hatch-tab"
        private const val MAX_LOGS = 200
    }
}
