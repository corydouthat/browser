package app.hatch.selfcheck

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Debug
import android.util.Log
import androidx.core.content.FileProvider
import app.hatch.BuildConfig
import app.hatch.Components
import app.hatch.persona.Persona
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineView
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume

/**
 * Drives the Self-check screen: owns the [LocalHttpServer] (alive only while the screen is open), loads the
 * harness into the selected tab, collects the report (server POST first, bridge port as fallback) and builds
 * the share zip (report JSON + screenshot PNG + meminfo JSON) through the FileProvider.
 */
class SelfCheckController(
    private val context: Context,
    private val components: Components,
    private val scope: CoroutineScope,
) {
    data class State(
        val serverUrl: String? = null,
        val status: String = "idle",
        val running: Boolean = false,
        val latestReport: File? = null,
        val summary: String? = null,
        val personaOffRun: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state

    val reportsDir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "reports")

    private val server = LocalHttpServer(
        context = context,
        reportsDir = reportsDir,
        personaKey = { components.personaManager.current.value.key },
        onReport = { file, text -> scope.launch(Dispatchers.Main) { onReport(file, text, "server") } },
    )
    private var bridgeJob: Job? = null
    private var readyJob: Job? = null
    private var restorePersona: Persona? = null
    private var runStartedAt = 0L

    /** The one session the controller asked for a report; anything else on the "hatch-tab" ports is ignored. */
    private var requestedSession: EngineSession? = null

    fun open() {
        if (server.isRunning) return
        val url = server.start()
        _state.value = _state.value.copy(serverUrl = url, status = "server up at $url")
        bridgeJob = scope.launch {
            components.bridge.reports.collect { r ->
                val base = server.baseUrl
                val text = r.report?.toString()
                val verdict = BridgeReportFilter.accept(
                    requested = requestedSession, sender = r.session, url = r.url, baseUrl = base,
                    reportBytes = text?.toByteArray(Charsets.UTF_8)?.size?.toLong(),
                )
                if (verdict != BridgeReportFilter.Verdict.ACCEPT) {
                    Log.w(TAG, "bridge report ignored ($verdict) from ${r.url}")
                    return@collect
                }
                if (text != null) {
                    // fallback path: no POST arrived (e.g. fetch blocked); persist what the bridge delivered
                    if (_state.value.latestReport == null || _state.value.latestReport!!.lastModified() < runStartedAt) {
                        val file = saveReport(r.report.toString(2))
                        onReport(file, text, "bridge")
                    }
                } else if (r.error != null) {
                    _state.value = _state.value.copy(running = false, status = "harness error: ${r.error}")
                }
            }
        }
        readyJob = scope.launch {
            components.bridge.ready.collect { (session, url) ->
                val base = server.baseUrl ?: return@collect
                if (url.startsWith(base) && _state.value.running) {
                    // the harness page's bridge is up: ask it (and only it) to hand over the result when done
                    requestedSession = session
                    components.bridge.requestSelfCheckReport(session)
                }
            }
        }
    }

    fun close() {
        bridgeJob?.cancel(); bridgeJob = null
        readyJob?.cancel(); readyJob = null
        requestedSession = null
        server.stop()
        restorePersona?.let { p -> scope.launch { components.personaManager.apply(p) } }
        restorePersona = null
        _state.value = _state.value.copy(serverUrl = null, running = false, status = "closed")
    }

    /** Loads the harness with the active persona; [personaOff] first switches to real-device (restored on close). */
    fun run(personaOff: Boolean) {
        val base = server.baseUrl ?: run { open(); server.baseUrl } ?: return
        scope.launch {
            val pm = components.personaManager
            if (personaOff) {
                if (restorePersona == null) restorePersona = pm.current.value
                pm.apply(pm.personaOff(), reload = false)
            } else if (restorePersona != null) {
                val back = restorePersona!!
                restorePersona = null
                pm.apply(back, reload = false)
            }
            val persona = pm.current.value
            runStartedAt = System.currentTimeMillis()
            val url = base + "index.html?persona=" + persona.key + "&env=device"
            _state.value = _state.value.copy(running = true, status = "running ${persona.key}…", summary = null, personaOffRun = personaOff)
            withContext(Dispatchers.Main) {
                if (components.store.state.selectedTab == null) components.tabsUseCases.addTab(url)
                else components.sessionUseCases.loadUrl(url = url, sessionId = components.store.state.selectedTabId)
            }
        }
    }

    private fun onReport(file: File, text: String, via: String) {
        val summary = runCatching {
            val o = JSONObject(text)
            val s = o.optJSONObject("summary")
            if (s == null) "report saved (no summary)" else
                "${s.optString("overall")} · pass ${s.optInt("pass")} fail ${s.optInt("fail")} skipped ${s.optInt("skipped")} · libs ${s.optInt("libraryPass")}/${s.optInt("libraryTotal")}"
        }.getOrElse { "report saved (unparseable JSON)" }
        _state.value = _state.value.copy(running = false, latestReport = file, summary = summary, status = "report via $via: ${file.name}")
    }

    private fun saveReport(text: String): File {
        val key = LocalHttpServer.safeName(components.personaManager.current.value.key)
        return LocalHttpServer.newReportFile(reportsDir, key).also { it.writeText(text) }
    }

    /** Builds the zip and returns an ACTION_SEND chooser intent, or null when there is nothing to share yet. */
    suspend fun buildShareIntent(engineView: EngineView?): Intent? {
        val report = _state.value.latestReport ?: return null
        val screenshot = engineView?.let { captureThumbnail(it) }
        val meminfo = memInfoJson()
        val zip = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val stem = report.nameWithoutExtension
            val zip = File(dir, "$stem.zip")
            ZipOutputStream(FileOutputStream(zip)).use { out ->
                out.putNextEntry(ZipEntry(report.name)); out.write(report.readBytes()); out.closeEntry()
                if (screenshot != null) {
                    out.putNextEntry(ZipEntry("$stem.png"))
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.closeEntry()
                }
                out.putNextEntry(ZipEntry("$stem-meminfo.json")); out.write(meminfo.toByteArray()); out.closeEntry()
            }
            zip
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Hatch self-check ${report.name}")
            putExtra(Intent.EXTRA_TEXT, _state.value.summary ?: report.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share self-check report")
    }

    private suspend fun captureThumbnail(view: EngineView): Bitmap? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            try {
                view.captureThumbnail { bmp -> if (cont.isActive) cont.resume(bmp) }
            } catch (e: Exception) {
                Log.w(TAG, "captureThumbnail failed", e)
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private fun memInfoJson(): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val sys = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val self = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val procs = am.runningAppProcesses?.filter { it.uid == android.os.Process.myUid() } ?: emptyList()
        val perProcess = procs.map { it.pid }.let { pids ->
            if (pids.isEmpty()) emptyList() else am.getProcessMemoryInfo(pids.toIntArray()).mapIndexed { i, mi ->
                JSONObject().put("pid", pids[i]).put("name", procs[i].processName).put("totalPssKb", mi.totalPss)
                    .put("nativePssKb", mi.nativePss).put("dalvikPssKb", mi.dalvikPss).put("otherPssKb", mi.otherPss)
            }
        }
        val o = JSONObject()
            .put("app", "${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.GIT_SHA}")
            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            .put("at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
            .put("system", JSONObject().put("availMemMb", sys.availMem / 1048576).put("totalMemMb", sys.totalMem / 1048576).put("lowMemory", sys.lowMemory).put("thresholdMb", sys.threshold / 1048576))
            .put("mainProcess", JSONObject().put("totalPssKb", self.totalPss).put("nativePssKb", self.nativePss).put("dalvikPssKb", self.dalvikPss).put("otherPssKb", self.otherPss))
            .put("processes", org.json.JSONArray(perProcess))
            .put("tabs", components.store.state.tabs.size)
        return o.toString(2)
    }

    fun selectedEngineSession(): EngineSession? = components.store.state.selectedTab?.engineState?.engineSession

    companion object {
        private const val TAG = "SelfCheckController"
    }
}
