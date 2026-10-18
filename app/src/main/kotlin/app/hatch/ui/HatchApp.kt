package app.hatch.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.hatch.Components
import app.hatch.persona.AppRestart
import app.hatch.persona.Persona
import app.hatch.persona.PersonaManager
import app.hatch.rules.RulesUiState
import app.hatch.selfcheck.SelfCheckController
import app.hatch.ui.keys.KeyShortcuts
import app.hatch.ui.menu.MainMenuSheet
import app.hatch.ui.menu.mainMenuItems
import app.hatch.ui.tabs.TabClosePolicy
import app.hatch.ui.tabs.TabTrayScreen
import app.hatch.ui.toolbar.HatchToolbarCallbacks
import app.hatch.ui.toolbar.HatchToolbarController
import app.hatch.ui.toolbar.ToolbarState
import androidx.compose.ui.Alignment
import app.hatch.ui.logins.LoginPickerBar
import app.hatch.ui.logins.SavedLoginsScreen
import app.hatch.ui.rules.RulesOverlays
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.concept.engine.EngineView
import mozilla.components.lib.state.ext.observeAsComposableState

/** Which full-screen overlay (if any) sits above the browser. The browser (and its EngineView) is always composed. */
enum class Overlay { None, Settings, Tabs }

/**
 * Owner: chrome. Everything the Activity's back handler and key shortcuts need to know about the Compose UI.
 * The credentials and rules modules keep their own overlay state (`Components.loginsUi`, `Components.rulesUi`);
 * `MainActivity`'s back callback and [HatchApp]'s Esc handling ask those first, in z-order (rules overlays draw
 * above the Saved logins screen, which draws above [overlay]), then [onBack].
 */
class AppUiState {
    var overlay by mutableStateOf(Overlay.None)
    var personaSheet by mutableStateOf(false)
    var menu by mutableStateOf(false)
    var findBar by mutableStateOf(false)
    var siteInfo by mutableStateOf(false)
    var selfCheck by mutableStateOf(false)

    /** Mirrors `FullScreenFeature` (set by `MainActivity.fullScreenChanged`); hides the whole chrome. */
    var fullScreen by mutableStateOf(false)

    /** Wired by [HatchApp] to the toolbar store: exits URL edit mode, true when it was open. */
    var exitUrlEdit: () -> Boolean = { false }
    var isUrlEditing: () -> Boolean = { false }

    /** A Hatch surface the Esc key / back press should close is open (the page should not see Esc). */
    fun hasOverlay(): Boolean =
        siteInfo || findBar || overlay != Overlay.None || menu || personaSheet || selfCheck || isUrlEditing()

    /**
     * Back order: URL edit (the most recently focused surface, UXP-06: Esc while typing in the address bar must
     * cancel the edit, not close a find bar under it) → site info → find bar → tab tray → menu → persona sheet →
     * settings → self-check.
     */
    fun onBack(): Boolean = when {
        exitUrlEdit() -> true
        siteInfo -> { siteInfo = false; true }
        findBar -> { findBar = false; true }
        overlay == Overlay.Tabs -> { overlay = Overlay.None; true }
        menu -> { menu = false; true }
        personaSheet -> { personaSheet = false; true }
        overlay != Overlay.None -> { overlay = Overlay.None; true }
        selfCheck -> { selfCheck = false; true }
        else -> false
    }

    /** Ctrl+L: the address bar is about to take focus, so whatever covers it (Settings, tray, menu, find bar) goes. */
    fun prepareForUrlEdit() {
        overlay = Overlay.None
        menu = false
        findBar = false
    }

    /** Ctrl+F / menu: the find bar is about to open under the toolbar, so the covering overlays go. */
    fun prepareForFind() {
        overlay = Overlay.None
        menu = false
    }
}

@Composable
fun HatchApp(
    components: Components,
    engineView: EngineView,
    uiState: AppUiState,
    uiEvents: UiEvents,
    selfCheck: SelfCheckController,
    onRestart: () -> Unit,
    requestScreenshot: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val persona by components.personaManager.current.collectAsState()
    // PERF-04: a value snapshot, so progress ticks and other ContentState copies do not recompose the app.
    val tab by components.store.observeAsComposableState { it.selectedTab?.let(SelectedTabFacts::of) }
    val keyLossNotice by components.logins.keyLossNotice.collectAsState()

    fun applyPersona(persona: Persona) {
        scope.launch {
            when (val r = components.personaManager.apply(persona)) {
                PersonaManager.ApplyResult.Applied -> snackbar.showSnackbar("Persona: ${persona.identity.name} · ${persona.region.name}")
                PersonaManager.ApplyResult.NeedsRestart -> {
                    val res = snackbar.showSnackbar(
                        message = "Restart to apply time zone",
                        actionLabel = "Restart",
                        duration = SnackbarDuration.Long,
                    )
                    if (res == SnackbarResult.ActionPerformed) onRestart()
                }
                is PersonaManager.ApplyResult.Failed -> snackbar.showSnackbar("Persona switch failed: ${r.error.message}")
            }
        }
    }

    fun toggleDesktop() {
        val pm = components.personaManager
        applyPersona(if (pm.current.value.desktop) pm.personaOff() else pm.forceDesktop())
    }

    fun newTab() {
        uiState.overlay = Overlay.None
        uiState.menu = false
        uiState.findBar = false
        components.tabsUseCases.addTab(ToolbarState.HOME_URL)
    }

    fun openTabs() {
        uiState.exitUrlEdit()
        // The capture lands a few frames later; ThumbnailGeneration re-renders the current card then (ENG-06).
        requestScreenshot()
        uiState.menu = false
        uiState.overlay = Overlay.Tabs
    }

    /** UXP-04: the one close path (tray, tab-counter menu, Ctrl+W): remove, then offer undo on the app-wide host. */
    fun offerUndo(count: Int) {
        val request = TabClosePolicy.undoRequest(count)
        scope.launch {
            val r = snackbar.showSnackbar(request.message, actionLabel = request.actionLabel, withDismissAction = true, duration = request.duration)
            if (r == SnackbarResult.ActionPerformed) components.tabsUseCases.undo()
        }
    }

    fun closeTabs(ids: List<String>) {
        if (ids.isEmpty()) return
        if (ids.size == 1) components.tabsUseCases.removeTab(ids.first()) else components.tabsUseCases.removeTabs(ids)
        offerUndo(ids.size)
    }

    fun closeSelectedTab() {
        components.store.state.selectedTabId?.let { closeTabs(listOf(it)) }
    }

    fun closeAllTabs() {
        val n = components.store.state.tabs.size
        if (n == 0) return
        components.tabsUseCases.removeAllTabs(recoverable = true)
        offerUndo(n)
    }

    val toolbar = remember {
        HatchToolbarController(
            components,
            HatchToolbarCallbacks(
                onOpenTabs = ::openTabs,
                onOpenMenu = { uiState.menu = true },
                onSiteInfo = { uiState.siteInfo = true },
                onToggleDesktop = ::toggleDesktop,
                onNewTab = ::newTab,
                onCloseTab = ::closeSelectedTab,
            ),
        )
    }
    DisposableEffect(toolbar) {
        uiState.exitUrlEdit = { toolbar.exitEditMode() }
        uiState.isUrlEditing = { toolbar.isEditing }
        onDispose { toolbar.stop() }
    }

    fun toggleEruda() {
        val session = components.store.state.selectedTab?.engineState?.engineSession
        val ok = session != null && components.bridge.toggleEruda(session)
        if (!ok) scope.launch { snackbar.showSnackbar("Eruda: no bridge port on this page (load a http(s) page first)") }
    }

    fun startActivitySafely(intent: android.content.Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar("No app can open this link") }
        }
    }

    /** Esc: closes the topmost Hatch surface in z-order (rules overlays → Saved logins → the chrome's own). */
    fun closeTopSurface(): Boolean =
        components.rulesUi.onBack() || components.loginsUi.onBack() || uiState.onBack()

    /** The rules overlays / Saved logins sit above the chrome: toolbar and find shortcuts are ignored under them. */
    fun otherModuleCovers(): Boolean = components.rulesUi.isOpen || components.loginsUi.savedLoginsOpen

    // SEC-6: the logins store found its database without its key (KeyStore loss) and had to reset it.
    LaunchedEffect(keyLossNotice) {
        val notice = keyLossNotice ?: return@LaunchedEffect
        components.logins.keyLossNotice.value = null
        snackbar.showSnackbar(notice, duration = SnackbarDuration.Long)
    }

    // UXP-15: nothing in this collector suspends; every handler is a state flip or a launch of its own.
    LaunchedEffect(uiEvents) {
        uiEvents.events.collect { e ->
            when (e) {
                UiEvent.FocusUrl -> if (!otherModuleCovers()) {
                    uiState.prepareForUrlEdit()
                    toolbar.enterEditMode()
                }
                UiEvent.NewTab -> newTab()
                UiEvent.CloseTab -> closeSelectedTab()
                UiEvent.Reload -> components.sessionUseCases.reload()
                UiEvent.GoBack -> components.sessionUseCases.goBack()
                UiEvent.GoForward -> components.sessionUseCases.goForward()
                UiEvent.NextTab, UiEvent.PrevTab -> {
                    val st = components.store.state
                    KeyShortcuts.neighbourTabId(st.selectedTabId, st.tabs.map { it.id }, forward = e == UiEvent.NextTab)
                        ?.let { components.tabsUseCases.selectTab(it) }
                }
                is UiEvent.SelectTabIndex ->
                    KeyShortcuts.tabIdForIndex(e.index, components.store.state.tabs.map { it.id })
                        ?.let { components.tabsUseCases.selectTab(it) }
                UiEvent.OpenTabs -> openTabs()
                UiEvent.OpenMenu -> uiState.menu = true
                UiEvent.FindInPage -> if (!otherModuleCovers() && components.store.state.selectedTab != null) {
                    toolbar.exitEditMode()
                    uiState.prepareForFind()
                    uiState.findBar = true
                }
                UiEvent.ToggleEruda -> toggleEruda()
                UiEvent.PersonaSheet -> uiState.personaSheet = !uiState.personaSheet
                UiEvent.SelfCheck -> uiState.selfCheck = !uiState.selfCheck
                UiEvent.OpenSettings, UiEvent.OpenAddons -> uiState.overlay = Overlay.Settings
                UiEvent.Escape -> closeTopSurface()
                // Credentials module: menu "Saved logins", Settings and the picker's "Manage logins".
                UiEvent.OpenSavedLogins -> components.loginsUi.savedLoginsOpen = true
                // Rules module: each event replaces the rules overlay stack with its screen (drawn above Settings).
                UiEvent.OpenSiteRules -> components.rulesUi.show(RulesUiState.Screen.SiteRules)
                UiEvent.OpenSitePermissions -> components.rulesUi.show(RulesUiState.Screen.SitePermissions)
                UiEvent.OpenPersonaEditor -> components.rulesUi.show(RulesUiState.Screen.PersonaEditor)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        BrowserScreen(
            components = components,
            engineView = engineView,
            uiState = uiState,
            toolbar = toolbar,
            selfCheck = selfCheck,
            onApplyPersona = ::applyPersona,
            onSitePermissions = { uiEvents.emit(UiEvent.OpenSitePermissions) },
        )
        if (uiState.personaSheet) {
            PersonaSheet(
                personaManager = components.personaManager,
                onDismiss = { uiState.personaSheet = false },
                onApply = { p -> uiState.personaSheet = false; applyPersona(p) },
            )
        }
        if (uiState.menu) {
            val url = tab?.url
            MainMenuSheet(
                persona = persona,
                canGoBack = tab?.canGoBack == true,
                canGoForward = tab?.canGoForward == true,
                loading = tab?.loading == true,
                items = mainMenuItems(
                    onNewTab = ::newTab,
                    onFindInPage = { uiEvents.emit(UiEvent.FindInPage) },
                    onOpenInBrowser = { url?.let { startActivitySafely(ExternalBrowser.chooserIntent(context, it)) } },
                    openInBrowserEnabled = ExternalBrowser.isShareableUrl(url),
                    onAddons = { uiEvents.emit(UiEvent.OpenAddons) },
                    onSavedLogins = { uiEvents.emit(UiEvent.OpenSavedLogins) },
                    onSiteRules = { uiEvents.emit(UiEvent.OpenSiteRules) },
                    onSitePermissions = { uiEvents.emit(UiEvent.OpenSitePermissions) },
                    onSelfCheck = { uiEvents.emit(UiEvent.SelfCheck) },
                    onEruda = ::toggleEruda,
                    onPersona = { uiEvents.emit(UiEvent.OpenPersonaEditor) },
                    onSettings = { uiEvents.emit(UiEvent.OpenSettings) },
                ),
                onDismiss = { uiState.menu = false },
                onPersona = { uiState.personaSheet = true },
                onToggleDesktop = ::toggleDesktop,
                onBack = { components.sessionUseCases.goBack() },
                onForward = { components.sessionUseCases.goForward() },
                onShare = { url?.let { startActivitySafely(ExternalBrowser.shareIntent(it, tab?.title)) } },
                onReload = {
                    if (tab?.loading == true) components.sessionUseCases.stopLoading() else components.sessionUseCases.reload()
                },
            )
        }
        when (uiState.overlay) {
            Overlay.Settings -> SettingsScreen(
                components = components,
                onClose = { uiState.overlay = Overlay.None },
                onRestart = onRestart,
                snackbar = snackbar,
            )
            Overlay.Tabs -> TabTrayScreen(
                components = components,
                onDismiss = { uiState.overlay = Overlay.None },
                onNewTab = ::newTab,
                onCloseTabs = ::closeTabs,
                onCloseAll = ::closeAllTabs,
            )
            Overlay.None -> Unit
        }
        // Z-order above here: Saved logins (credentials; device-credential gated) and the engine's login picker bar,
        // then the rules overlays (site rules / editor / site permissions / persona editor) on top of everything,
        // so "Site rules…" from Settings opens over Settings and back returns there. Keep MainActivity's back chain
        // and closeTopSurface() in the same order.
        if (components.loginsUi.savedLoginsOpen) {
            SavedLoginsScreen(components = components, onClose = { components.loginsUi.savedLoginsOpen = false }, snackbar = snackbar)
        }
        LoginPickerBar(components.loginsUi.picker, Modifier.align(Alignment.BottomCenter))
        RulesOverlays(
            components = components,
            snackbar = snackbar,
            onApplyPersona = ::applyPersona,
            onOpenPersonaSheet = { uiState.personaSheet = true },
            onShowPage = { uiState.overlay = Overlay.None; uiState.menu = false },
        )
        // UXP-02: the one host for the shared SnackbarHostState, last in the Box = above every overlay (Settings,
        // Saved logins, tray, rules screens), so "Password copied", "Restart" and "Tab closed – Undo" are visible
        // wherever they are raised. The rules screens host their own local states inside their scaffolds.
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding())
    }
}
