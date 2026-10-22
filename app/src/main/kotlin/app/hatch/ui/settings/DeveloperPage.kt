package app.hatch.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hatch.Components
import app.hatch.engine.GeckoPrefs
import app.hatch.persona.PersonaToPrefs
import app.hatch.ui.surfaces.PreviewTheme
import app.hatch.ui.surfaces.SurfacePreviewFrame
import app.hatch.ui.surfaces.SurfacePreviewThemes
import app.hatch.ui.theme.HatchTheme
import app.hatch.ui.theme.HatchType
import kotlinx.coroutines.launch

/**
 * Settings › Developer (mockup "Settings · Developer"): Remote debugging, Eruda in the menu (`AppSettings.erudaInMenu`;
 * Ctrl+Shift+I toggles Eruda either way), Self-check "Run" ([onSelfCheck]: HatchApp closes Settings, then runs the
 * check on the current tab), the bridge extension's status with its log, and the persona-owned engine prefs read
 * back from Gecko when the page opens (the mockup has no Refresh action: prefs only change on a persona switch,
 * which closes Settings; DF-05). Each pref shows whether Gecko holds a user value or its default (RS-06, as in M1).
 */
@Composable
internal fun DeveloperSettingsPage(
    components: Components,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onRestart: () -> Unit,
    onSelfCheck: () -> Unit,
) {
    val settings = components.settings
    val scope = rememberCoroutineScope()
    val tick by settings.changes.collectAsState()
    val remoteDebugging = remember(tick) { settings.remoteDebugging }
    val eruda = remember(tick) { settings.erudaInMenu }
    val bridgeStatus by components.bridge.status.collectAsState()
    val logs by components.bridge.logs.collectAsState()
    var prefs by remember { mutableStateOf<List<GeckoPrefs.PrefRow>>(emptyList()) }
    var prefsError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { GeckoPrefs.read(PersonaToPrefs.allPrefs) }
            .onSuccess { prefs = it; prefsError = null }
            .onFailure { prefsError = it.message ?: it.toString() }
    }

    SettingsPageScaffold("Developer", onBack) {
        DeveloperContent(
            remoteDebugging = remoteDebugging,
            onRemoteDebugging = { on ->
                settings.remoteDebugging = on
                components.engine.settings.remoteDebuggingEnabled = on
                scope.launch {
                    val r = snackbar.showSnackbar("Remote debugging ${if (on) "on" else "off"} (restart recommended)", actionLabel = "Restart")
                    if (r == SnackbarResult.ActionPerformed) onRestart()
                }
            },
            eruda = eruda,
            onEruda = { settings.erudaInMenu = it },
            onSelfCheck = onSelfCheck,
            bridge = BridgeStatus.of(bridgeStatus, logs.count { it.level == "error" }),
            logLines = logs.map { "[${it.level}] ${it.message}" },
            prefs = prefs.map(DeveloperRows::of),
            prefsError = prefsError,
        )
    }
}

@Composable
internal fun DeveloperContent(
    remoteDebugging: Boolean,
    onRemoteDebugging: (Boolean) -> Unit,
    eruda: Boolean,
    onEruda: (Boolean) -> Unit,
    onSelfCheck: () -> Unit,
    bridge: BridgeStatus,
    logLines: List<String>,
    prefs: List<Pair<String, String>>,
    prefsError: String?,
) {
    val c = HatchTheme.colors
    var showLog by rememberSaveable { mutableStateOf(false) }
    PageNote("For troubleshooting. Nothing here changes everyday browsing.")
    GroupedCard {
        SwitchCardRow("Remote debugging", "USB · about:debugging", remoteDebugging, onRemoteDebugging)
        CardDivider()
        SwitchCardRow("Eruda devtools", "Adds a console to the menu", eruda, onEruda)
        CardDivider()
        CardRow("Self-check", summary = "Test what sites see on this tab", minHeight = 64.dp, contentPadding = PaddingValues(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
            TonalPill("Run", onClick = onSelfCheck, height = 36.dp, container = c.s2)
        }
    }
    GroupedCard {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (bridge.ok) c.ok else c.dg)
                        .semantics { contentDescription = if (bridge.ok) "Connected" else "Not connected" },
                )
                Text("Bridge extension", style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f))
                TopBarTextAction(if (showLog) "Hide log" else "View log", onClick = { showLog = !showLog })
            }
            Text(bridge.line, style = HatchType.mono, color = c.mu)
            if (showLog) {
                if (logLines.isEmpty()) {
                    Text("No log lines yet.", style = MaterialTheme.typography.bodyMedium, color = c.mu)
                } else {
                    Column(Modifier.padding(top = 6.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        logLines.takeLast(LOG_LINES).forEach { Text(it, style = HatchType.mono.copy(fontSize = 11.sp), color = c.ink) }
                    }
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GroupTitle("Engine prefs (read back from Gecko)", padding = PaddingValues(start = 8.dp, end = 8.dp, top = 6.dp))
        prefsError?.let { Text("Read failed: $it", style = MaterialTheme.typography.bodySmall, color = c.dg, modifier = Modifier.padding(horizontal = 8.dp)) }
        GroupedCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                if (prefs.isEmpty() && prefsError == null) {
                    Text("Reading…", style = MaterialTheme.typography.bodyMedium, color = c.mu, modifier = Modifier.padding(vertical = 10.dp))
                }
                prefs.forEach { (name, value) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 40.dp).clearAndSetSemantics { contentDescription = DeveloperRows.spoken(name, value) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // DF-05: the key takes the remaining width; the value sits at its natural width at the row end
                        // (capped so a long UA override cannot squeeze the key out).
                        Text(name, style = PrefStyle, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(
                            value,
                            style = PrefStyle,
                            color = c.mu,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                            modifier = Modifier.widthIn(max = PrefValueMaxWidth),
                        )
                    }
                }
            }
        }
        if (prefs.any { it.second.endsWith(DeveloperRows.USER_MARK) }) {
            Text(DeveloperRows.FOOTNOTE, style = MaterialTheme.typography.bodySmall, color = c.mu, modifier = Modifier.padding(horizontal = 8.dp))
        }
    }
}

private val PrefStyle = HatchType.mono.copy(fontSize = 11.5.sp)
private val PrefValueMaxWidth = 132.dp

/**
 * The engine-pref rows (RS-06; M1 printed `[user]` / `[default]`): name → value, with [USER_MARK] appended when Gecko
 * holds a user-branch value, so a pref that merely equals its default is told apart from an applied one. A one-char
 * mark keeps the mockup's one-line rows (the full pref names still fit at 360 dp); [FOOTNOTE] explains it and
 * [spoken] is what TalkBack reads for the row.
 */
internal object DeveloperRows {
    const val UNSET = "<unset>"
    const val USER_MARK = "*"
    const val FOOTNOTE = "* set by Hatch (user value); the others are Gecko defaults."

    fun of(row: GeckoPrefs.PrefRow): Pair<String, String> =
        row.name to (row.value ?: UNSET) + if (row.userSet) USER_MARK else ""

    /** "name, value, user value" / "name, value, default" for a displayed (name, value) pair. */
    fun spoken(name: String, shown: String): String =
        if (shown.endsWith(USER_MARK)) "$name, ${shown.removeSuffix(USER_MARK)}, user value" else "$name, $shown, default"
}
private const val LOG_LINES = 40

@Preview(name = "Settings · Developer", widthDp = 360, heightDp = 1320)
@Composable
internal fun DeveloperPreview(@PreviewParameter(SurfacePreviewThemes::class) theme: PreviewTheme) {
    SurfacePreviewFrame(theme) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar("Developer", onNav = {}, endPadding = TopBarEndPaddingIcon)
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DeveloperContent(
                    remoteDebugging = false, onRemoteDebugging = {}, eruda = true, onEruda = {}, onSelfCheck = {},
                    bridge = BridgeStatus.of("connected v0.3", 0), logLines = listOf("[info] background ready"),
                    prefs = listOf(
                        "general.useragent.override" to "set*", "privacy.resistFingerprinting" to "false",
                        "dom.w3c_touch_events.enabled" to "0*", "intl.accept_languages" to "en-GB, en*", "javascript.options.wasm" to "true",
                    ),
                    prefsError = null,
                )
            }
        }
    }
}
