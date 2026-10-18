package app.hatch.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hatch.Components
import app.hatch.persona.Persona
import app.hatch.persona.UrlRewriteRules
import app.hatch.selfcheck.SelfCheckController
import mozilla.components.browser.state.helper.Target
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.lib.state.ext.observeAsComposableState

@Composable
fun BrowserScreen(
    components: Components,
    engineView: mozilla.components.concept.engine.EngineView,
    uiState: AppUiState,
    snackbar: SnackbarHostState,
    selfCheck: SelfCheckController,
    onApplyPersona: (Persona) -> Unit,
    onMessage: (String) -> Unit,
) {
    val store = components.store
    val tab by store.observeAsComposableState { it.selectedTab }
    val tabCount by store.observeAsComposableState { it.tabs.size }
    val persona by components.personaManager.current.collectAsState()
    val fullScreen = tab?.content?.fullScreen == true

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        // Edge-to-edge ignores windowSoftInputMode=adjustResize on API 30+, so the IME must be applied as an
        // inset here: the EngineView shrinks when the keyboard opens, Gecko resizes its viewport and scrolls
        // the focused field into view (HATCH-WC-04).
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
        topBar = {
            if (!fullScreen) {
                Toolbar(
                    url = tab?.content?.url ?: "",
                    loading = tab?.content?.loading == true,
                    progress = tab?.content?.progress ?: 0,
                    canGoBack = tab?.content?.canGoBack == true,
                    canGoForward = tab?.content?.canGoForward == true,
                    tabCount = tabCount,
                    personaLabel = "${persona.identity.name} · ${persona.region.name}",
                    focusRequest = uiState.focusUrlRequest,
                    onGo = { input ->
                        val url = normalizeInput(input, persona.desktop)
                        if (tab == null) components.tabsUseCases.addTab(url) else components.sessionUseCases.loadUrl(url = url, sessionId = tab?.id)
                    },
                    onReload = { if (tab?.content?.loading == true) components.sessionUseCases.stopLoading() else components.sessionUseCases.reload() },
                    onBack = { components.sessionUseCases.goBack() },
                    onForward = { components.sessionUseCases.goForward() },
                    onNewTab = { components.tabsUseCases.addTab(components.settings.homeUrl) },
                    onCloseTab = { tab?.id?.let { components.tabsUseCases.removeTab(it) } },
                    onPersona = { uiState.personaSheet = true },
                    onForceDesktop = {
                        val pm = components.personaManager
                        onApplyPersona(if (persona.desktop) pm.personaOff() else pm.forceDesktop())
                    },
                    onEruda = {
                        val session = tab?.engineState?.engineSession
                        val ok = session != null && components.bridge.toggleEruda(session)
                        if (!ok) onMessage("Eruda: no bridge port on this page (load a http(s) page first)")
                    },
                    onSelfCheck = { uiState.selfCheck = !uiState.selfCheck },
                    onAddons = { uiState.overlay = Overlay.Settings },
                    onSettings = { uiState.overlay = Overlay.Settings },
                    desktop = persona.desktop,
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(if (fullScreen) androidx.compose.foundation.layout.PaddingValues(0.dp) else padding)) {
            if (uiState.selfCheck && !fullScreen) {
                SelfCheckBar(
                    controller = selfCheck,
                    engineView = engineView,
                    onClose = { uiState.selfCheck = false },
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                WebContent(engineView = engineView, store = store, target = Target.SelectedTab)
                if (tab == null) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No tabs. Enter a URL above or press Ctrl+T.", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}

/** Address-bar input -> URL: adds https://, rewrites known mobile hosts when a desktop persona is active. */
internal fun normalizeInput(raw: String, desktop: Boolean): String {
    val s = raw.trim()
    if (s.isEmpty()) return "about:blank"
    val url = when {
        s.startsWith("about:") || s.startsWith("http://") || s.startsWith("https://") || s.startsWith("file://") ||
            s.startsWith("data:") || s.startsWith("resource://") || s.startsWith("moz-extension://") -> s
        s.contains(' ') || (!s.contains('.') && !s.contains("localhost")) ->
            "https://www.google.com/search?q=" + java.net.URLEncoder.encode(s, "UTF-8")
        else -> "https://$s"
    }
    return if (desktop) UrlRewriteRules.desktopUrl(url) ?: url else url
}

@Composable
private fun Toolbar(
    url: String,
    loading: Boolean,
    progress: Int,
    canGoBack: Boolean,
    canGoForward: Boolean,
    tabCount: Int,
    personaLabel: String,
    desktop: Boolean,
    focusRequest: Int,
    onGo: (String) -> Unit,
    onReload: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onNewTab: () -> Unit,
    onCloseTab: () -> Unit,
    onPersona: () -> Unit,
    onForceDesktop: () -> Unit,
    onEruda: () -> Unit,
    onSelfCheck: () -> Unit,
    onAddons: () -> Unit,
    onSettings: () -> Unit,
) {
    var text by remember { mutableStateOf(TextFieldValue(url)) }
    var editing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(url) { if (!editing) text = TextFieldValue(url) }
    LaunchedEffect(focusRequest) {
        if (focusRequest > 0) {
            focusRequester.requestFocus()
            text = text.copy(selection = TextRange(0, text.text.length))
        }
    }

    Surface(tonalElevation = 2.dp) {
        Column(Modifier.statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, enabled = canGoBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                IconButton(onClick = onForward, enabled = canGoForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward") }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .onFocusChanged { f ->
                            editing = f.isFocused
                            if (f.isFocused) text = text.copy(selection = TextRange(0, text.text.length))
                            else text = TextFieldValue(url)
                        },
                    singleLine = true,
                    placeholder = { Text("Search or enter address") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        onGo(text.text)
                        focusManager.clearFocus()
                    }),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                IconButton(onClick = onReload) {
                    if (loading) Icon(Icons.Filled.Close, "Stop") else Icon(Icons.Filled.Refresh, "Reload")
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("New tab") }, onClick = { menu = false; onNewTab() })
                        DropdownMenuItem(text = { Text("Close tab ($tabCount open)") }, onClick = { menu = false; onCloseTab() })
                        DropdownMenuItem(text = { Text("Persona…") }, onClick = { menu = false; onPersona() })
                        DropdownMenuItem(
                            text = { Text(if (desktop) "Persona off (real device)" else "Force desktop") },
                            onClick = { menu = false; onForceDesktop() },
                        )
                        DropdownMenuItem(text = { Text("Eruda devtools") }, onClick = { menu = false; onEruda() })
                        DropdownMenuItem(text = { Text("Self-check") }, onClick = { menu = false; onSelfCheck() })
                        DropdownMenuItem(text = { Text("Add-ons") }, onClick = { menu = false; onAddons() })
                        DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; onSettings() })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = onPersona,
                    label = { Text(personaLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
            if (loading) {
                LinearProgressIndicator(
                    progress = { (progress.coerceIn(0, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
