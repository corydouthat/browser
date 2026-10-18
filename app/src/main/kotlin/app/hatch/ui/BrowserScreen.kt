package app.hatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.hatch.Components
import app.hatch.persona.HostRewrite
import app.hatch.persona.Persona
import app.hatch.persona.UrlRewriteRules
import app.hatch.selfcheck.SelfCheckController
import app.hatch.ui.find.FindBarPolicy
import app.hatch.ui.find.FindInPageBar
import app.hatch.ui.home.HomeContent
import app.hatch.ui.toolbar.DynamicToolbarPlan
import app.hatch.ui.toolbar.HatchToolbar
import app.hatch.ui.toolbar.HatchToolbarController
import app.hatch.ui.toolbar.PersonaStrip
import app.hatch.ui.toolbar.ToolbarState
import app.hatch.ui.toolbar.rememberDynamicToolbar
import mozilla.components.browser.state.action.CrashAction
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.compose.browser.toolbar.store.Mode
import mozilla.components.lib.state.ext.observeAsComposableState

/**
 * The browser: edge-to-edge, Chrome-style TOP chrome (compose-browser-toolbar + persona strip + find bar +
 * self-check bar) over the single EngineView. Layout is a [SubcomposeLayout] so the `AndroidView` hosting the
 * EngineView keeps one stable node whether the toolbar is static (content laid out below it) or dynamic (content
 * below the status bar at full height, translated with the toolbar; see `DynamicToolbarController`). The IME is
 * applied as a bottom inset together with the navigation bar (HATCH-WC-04), the display cutout's horizontal sides
 * are kept clear in landscape (UXP-10), fullscreen removes the chrome and every inset.
 */
@Composable
fun BrowserScreen(
    components: Components,
    engineView: mozilla.components.concept.engine.EngineView,
    uiState: AppUiState,
    toolbar: HatchToolbarController,
    selfCheck: SelfCheckController,
    onApplyPersona: (Persona) -> Unit,
    onSitePermissions: () -> Unit,
) {
    val store = components.store
    // PERF-04: only the facts the chrome reads; progress ticks and other ContentState copies do not recompose here.
    val tab by store.observeAsComposableState { it.selectedTab?.let(SelectedTabFacts::of) }
    val persona by components.personaManager.current.collectAsState()
    val settingsTick by components.settings.changes.collectAsState()
    val hideOnScroll = remember(settingsTick) { components.settings.hideToolbarOnScroll }
    val mode by toolbar.store.observeAsComposableState { it.mode }
    val editing = mode == Mode.EDIT
    val fullScreen = uiState.fullScreen || tab?.fullScreen == true
    val crashed = tab?.crashed == true
    val isHome = tab != null && ToolbarState.isHome(tab?.url, tab?.canGoBack == true)
    val loading = tab?.loading == true
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val statusBarPx = WindowInsets.statusBars.getTop(density)

    // The Hatch home: typing starts immediately (toolbar in edit mode), like a desktop browser's new tab.
    LaunchedEffect(isHome, tab?.id) { if (isHome) toolbar.enterEditMode("") }

    // UXP-05: the find bar closes on a tab switch or a navigation to another document (fragment changes keep it).
    val findAnchor = FindBarPolicy.Anchor(tab?.id, tab?.url)
    var findOpenedOn by remember { mutableStateOf<FindBarPolicy.Anchor?>(null) }
    LaunchedEffect(uiState.findBar) { findOpenedOn = if (uiState.findBar) findAnchor else null }
    LaunchedEffect(findAnchor) {
        val opened = findOpenedOn ?: return@LaunchedEffect
        if (uiState.findBar && FindBarPolicy.shouldClose(opened, findAnchor)) uiState.findBar = false
    }

    // ENG-03: the feature (Settings switch) decides Gecko's toolbar reservation once; the "locked" states only
    // keep the toolbar expanded. The status-bar inset never hides, so the hideable height excludes it.
    val featureOn = hideOnScroll && !fullScreen
    val locked = imeVisible || uiState.findBar || editing || isHome || crashed || loading
    var chromeHeightPx by remember { mutableIntStateOf(0) }
    val toolbarPx = DynamicToolbarPlan.hideablePx(chromeHeightPx, statusBarPx)
    val dynamic = rememberDynamicToolbar(
        engineView = engineView,
        featureOn = featureOn,
        locked = locked,
        fullScreen = fullScreen,
        toolbarHeightPx = toolbarPx,
    )

    fun toggleDesktop() {
        val pm = components.personaManager
        onApplyPersona(if (persona.desktop) pm.personaOff() else pm.forceDesktop())
    }

    val cutoutSides = WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)
    val rootInsets = if (fullScreen) Modifier else Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).union(cutoutSides))
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).then(rootInsets)) {
        SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
            val chromePlaceables = if (fullScreen) {
                emptyList()
            } else {
                subcompose(Slot.Chrome) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .onSizeChanged { chromeHeightPx = it.height }
                            .graphicsLayer { translationY = -dynamic.hidden.value },
                    ) {
                        Surface(tonalElevation = 2.dp) {
                            Column(Modifier.windowInsetsPadding(WindowInsets.statusBars.union(cutoutSides))) {
                                HatchToolbar(toolbar)
                                if (uiState.findBar && tab != null) {
                                    FindBarSlot(store = store, onClose = { uiState.findBar = false })
                                } else if (!editing) {
                                    PersonaStrip(
                                        persona = persona,
                                        onPersona = { uiState.personaSheet = true },
                                        onToggleDesktop = ::toggleDesktop,
                                    )
                                }
                            }
                        }
                        if (uiState.selfCheck) {
                            SelfCheckBar(controller = selfCheck, engineView = engineView, onClose = { uiState.selfCheck = false })
                        }
                    }
                }.map { it.measure(constraints.copy(minHeight = 0)) }
            }
            val chromeHeight = chromePlaceables.sumOf { it.height }
            // Static: content below the chrome. Dynamic (feature on): content below the status bar at full height,
            // translated down by (toolbar - hidden) so it sits under the toolbar and grows into the space the toolbar
            // vacates; the bottom `toolbar - hidden` px go off-screen, exactly Gecko's dynamic-toolbar reservation.
            val staticLayout = !featureOn || fullScreen
            val contentTop = if (staticLayout) chromeHeight else statusBarPx.coerceAtMost(chromeHeight)
            val contentHeight = (constraints.maxHeight - contentTop).coerceAtLeast(0)
            val contentPlaceables = subcompose(Slot.Content) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationY = if (staticLayout) 0f else toolbarPx - dynamic.hidden.value },
                ) {
                    WebContent(engineView = engineView)
                    // UXP-01: tap outside the address bar leaves edit mode; over the web content only, so the home's
                    // own controls get the first tap (their callbacks leave edit mode themselves).
                    if (ChromeLayering.editTapCatcherOverContent(editing, isHome)) {
                        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { toolbar.exitEditMode() } })
                    }
                    when {
                        tab == null -> EmptyState()
                        crashed -> CrashedState(onRestore = { tab?.id?.let { store.dispatch(CrashAction.RestoreCrashedSessionAction(it)) } })
                        isHome -> HomeSlot(
                            components = components,
                            persona = persona,
                            currentTabId = tab?.id,
                            editing = editing,
                            exitEdit = { toolbar.exitEditMode() },
                            onChangePersona = { uiState.personaSheet = true },
                            onToggleDesktop = ::toggleDesktop,
                        )
                    }
                }
            }.map { it.measure(constraints.copy(minHeight = 0, maxHeight = contentHeight)) }

            layout(constraints.maxWidth, constraints.maxHeight) {
                contentPlaceables.forEach { it.placeRelative(0, contentTop) }
                chromePlaceables.forEach { it.placeRelative(0, 0) }
            }
        }
        if (uiState.siteInfo) {
            SiteInfoSlot(store = store, onSitePermissions = onSitePermissions, onDismiss = { uiState.siteInfo = false })
        }
    }
}

private enum class Slot { Chrome, Content }

/** Observes the full selected tab only while the find bar is open (it needs `findResults` + the engine session). */
@Composable
private fun FindBarSlot(store: BrowserStore, onClose: () -> Unit) {
    val tab by store.observeAsComposableState { it.selectedTab }
    FindInPageBar(store = store, tab = tab, onClose = onClose)
}

/** Observes the full selected tab only while the site-info dialog is open. */
@Composable
private fun SiteInfoSlot(store: BrowserStore, onSitePermissions: () -> Unit, onDismiss: () -> Unit) {
    val tab by store.observeAsComposableState { it.selectedTab }
    val t = tab
    LaunchedEffect(t == null) { if (t == null) onDismiss() }
    if (t != null) SiteInfoDialog(tab = t, onSitePermissions = onSitePermissions, onDismiss = onDismiss)
}

/**
 * The home surface: observes the tab list and the thumbnail generation only while shown. Every control leaves URL
 * edit mode first and then acts (UXP-01), and a tap on blank home area leaves edit mode like a tap on a page would.
 */
@Composable
private fun HomeSlot(
    components: Components,
    persona: Persona,
    currentTabId: String?,
    editing: Boolean,
    exitEdit: () -> Unit,
    onChangePersona: () -> Unit,
    onToggleDesktop: () -> Unit,
) {
    val tabs by components.store.observeAsComposableState { it.tabs }
    val generation by components.thumbnailGeneration.generation.collectAsState()
    HomeContent(
        persona = persona,
        tabs = tabs,
        currentTabId = currentTabId,
        thumbnails = components.thumbnailStorage,
        thumbnailGeneration = generation,
        homeUrl = components.settings.homeUrl,
        onChangePersona = { exitEdit(); onChangePersona() },
        onToggleDesktop = { exitEdit(); onToggleDesktop() },
        onOpenTab = { id -> exitEdit(); components.tabsUseCases.selectTab(id) },
        onOpenHomepage = {
            exitEdit()
            components.sessionUseCases.loadUrl(components.settings.homeUrl, currentTabId)
        },
        modifier = Modifier.pointerInput(editing) { if (editing) detectTapGestures { exitEdit() } },
    )
}

@Composable
private fun EmptyState() {
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No tabs. Tap the address bar or press Ctrl+T.", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** `tab.engineState.crashed`: SessionFeature released the view; Restore re-creates the session from its state. */
@Composable
private fun CrashedState(onRestore: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
            Text("This tab crashed", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "The content process for this tab stopped. Restore reloads it from the last saved state.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRestore) { Text("Restore tab") }
        }
    }
}

/**
 * Address-bar input -> URL: adds https://, rewrites known mobile hosts when a desktop persona is active.
 * [hostRewrites] is the host table to honour (ENG-04: the toolbar passes `Components.effectiveHostRewrites()`, the
 * table minus the hosts switched off in Settings, so typed URLs follow the same switches as followed links).
 */
internal fun normalizeInput(
    raw: String,
    desktop: Boolean,
    hostRewrites: List<HostRewrite> = UrlRewriteRules.extensionHostRewrites,
): String {
    val s = raw.trim()
    if (s.isEmpty()) return "about:blank"
    val url = when {
        s.startsWith("about:") || s.startsWith("http://") || s.startsWith("https://") || s.startsWith("file://") ||
            s.startsWith("data:") || s.startsWith("resource://") || s.startsWith("moz-extension://") -> s
        s.contains(' ') || (!s.contains('.') && !s.contains("localhost")) ->
            "https://www.google.com/search?q=" + java.net.URLEncoder.encode(s, "UTF-8")
        else -> "https://$s"
    }
    return if (desktop) UrlRewriteRules.desktopUrl(url, hostRewrites) ?: url else url
}
