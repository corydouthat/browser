package app.hatch.ui.toolbar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import app.hatch.Components
import app.hatch.R
import app.hatch.persona.Persona
import app.hatch.ui.normalizeInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.SecurityInfo
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.compose.base.theme.darkColorPalette
import mozilla.components.compose.base.theme.lightColorPalette
import mozilla.components.compose.base.theme.localAcornColors
import mozilla.components.compose.browser.toolbar.BrowserToolbar
import mozilla.components.compose.browser.toolbar.concept.Action
import mozilla.components.compose.browser.toolbar.concept.Action.ActionButton
import mozilla.components.compose.browser.toolbar.concept.Action.ActionButtonRes
import mozilla.components.compose.browser.toolbar.concept.Action.TabCounterAction
import mozilla.components.compose.browser.toolbar.concept.PageOrigin
import mozilla.components.compose.browser.toolbar.concept.PageOrigin.Companion.ContextualMenuOption
import mozilla.components.compose.browser.toolbar.concept.PageOrigin.Companion.PageOriginContextualMenuInteractions.CopyToClipboardClicked
import mozilla.components.compose.browser.toolbar.concept.PageOrigin.Companion.PageOriginContextualMenuInteractions.LoadFromClipboardClicked
import mozilla.components.compose.browser.toolbar.concept.PageOrigin.Companion.PageOriginContextualMenuInteractions.PasteFromClipboardClicked
import mozilla.components.compose.browser.toolbar.store.BrowserDisplayToolbarAction
import mozilla.components.compose.browser.toolbar.store.BrowserEditToolbarAction
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarAction
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction.BrowserToolbarEvent
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction.BrowserToolbarMenu
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarMenuItem.BrowserToolbarMenuButton
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarState
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarStore
import mozilla.components.compose.browser.toolbar.store.EditState
import mozilla.components.compose.browser.toolbar.store.ProgressBarConfig
import mozilla.components.compose.browser.toolbar.store.ToolbarGravity
import mozilla.components.compose.browser.toolbar.ui.BrowserToolbarQuery
import mozilla.components.concept.toolbar.AutocompleteProvider
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.ui.icons.R as iconsR

/** Events Hatch's toolbar elements dispatch on the [BrowserToolbarStore]; the middleware maps them to use cases. */
sealed interface ToolbarEvents : BrowserToolbarEvent {
    data object OriginClicked : ToolbarEvents
    data object SiteInfoClicked : ToolbarEvents
    data object DesktopToggleClicked : ToolbarEvents
    data object ReloadClicked : ToolbarEvents
    data object StopClicked : ToolbarEvents
    data object TabCounterClicked : ToolbarEvents
    data object MenuClicked : ToolbarEvents
    data object NewTabClicked : ToolbarEvents
    data object CloseTabClicked : ToolbarEvents

    /** Edit mode's end action (UXP-11): empties the query, like Fenix's clear button. */
    data object ClearClicked : ToolbarEvents
}

/** What the toolbar needs from the surrounding UI (overlays live in `AppUiState`, not in the toolbar store). */
class HatchToolbarCallbacks(
    val onOpenTabs: () -> Unit,
    val onOpenMenu: () -> Unit,
    val onSiteInfo: () -> Unit,
    val onToggleDesktop: () -> Unit,
    val onNewTab: () -> Unit,
    val onCloseTab: () -> Unit,
)

/**
 * Owns the [BrowserToolbarStore] (compose-browser-toolbar 158, `ToolbarGravity.Top`) for the Activity's lifetime
 * and keeps it in sync with the [BrowserStore]: URL, progress, security badge, tab count and the persona's
 * desktop flag. The [HatchToolbarMiddleware] handles every [BrowserToolbarEvent] (Fenix's
 * `BrowserToolbarMiddleware` is the pattern); edit mode gets inline autocomplete from the open tabs and
 * `ShippedDomainsProvider`, and `CommitUrl` goes through the existing [normalizeInput].
 */
class HatchToolbarController(
    private val components: Components,
    callbacks: HatchToolbarCallbacks,
) {
    private val middleware = HatchToolbarMiddleware(components, callbacks)
    val store: BrowserToolbarStore = BrowserToolbarStore(
        initialState = BrowserToolbarState(
            gravity = ToolbarGravity.Top,
            editState = EditState(hint = R.string.toolbar_search_hint),
        ),
        middleware = listOf(middleware),
    )

    val isEditing: Boolean get() = store.state.isEditMode()

    /** Ctrl+L / tap on the origin / Hatch home: edit mode with the current URL preselected. */
    fun enterEditMode(prefill: String? = null) {
        val tab = components.store.state.selectedTab
        val query = prefill ?: tab?.content?.url?.takeUnless { it == ToolbarState.HOME_URL } ?: ""
        store.dispatch(BrowserToolbarAction.EnterEditMode(isPrivate = false))
        store.dispatch(BrowserEditToolbarAction.SearchQueryUpdated(BrowserToolbarQuery(query), isQueryPrefilled = true))
    }

    /** @return true when edit mode was open (back press consumed). */
    fun exitEditMode(): Boolean {
        if (!store.state.isEditMode()) return false
        store.dispatch(BrowserToolbarAction.ExitEditMode)
        return true
    }

    fun stop() = middleware.stop()
}

internal class HatchToolbarMiddleware(
    private val components: Components,
    private val callbacks: HatchToolbarCallbacks,
    private val mainScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    private val autocompleteProviders: () -> List<AutocompleteProvider> = {
        listOf(components.openTabsAutocompleteProvider, components.domainsAutocompleteProvider)
    },
) : Middleware<BrowserToolbarState, BrowserToolbarAction> {
    private val browserStore: BrowserStore get() = components.store
    private var observers: CoroutineScope? = null
    private var autocompleteJob: Job? = null
    private var lastUrl: String = ""

    override fun invoke(
        store: Store<BrowserToolbarState, BrowserToolbarAction>,
        next: (BrowserToolbarAction) -> Unit,
        action: BrowserToolbarAction,
    ) {
        when (action) {
            is BrowserToolbarAction.Init -> {
                next(action)
                startObservers(store)
            }
            is BrowserToolbarAction.CommitUrl -> {
                load(action.text)
                store.dispatch(BrowserToolbarAction.ExitEditMode)
            }
            is BrowserEditToolbarAction.SearchQueryUpdated -> {
                next(action)
                store.dispatch(BrowserEditToolbarAction.SearchActionsEndUpdated(ToolbarState.editEndActions(action.query.current)))
                updateAutocomplete(store, action.query, action.isQueryPrefilled)
            }
            is ToolbarEvents.ClearClicked -> {
                store.dispatch(BrowserEditToolbarAction.SearchQueryUpdated(BrowserToolbarQuery(""), isQueryPrefilled = false))
            }
            is BrowserToolbarAction.ExitEditMode -> {
                autocompleteJob?.cancel()
                next(action)
                store.dispatch(BrowserEditToolbarAction.SearchActionsEndUpdated(emptyList()))
            }
            is ToolbarEvents.OriginClicked -> {
                val url = browserStore.state.selectedTab?.content?.url?.takeUnless { it == ToolbarState.HOME_URL } ?: ""
                store.dispatch(BrowserToolbarAction.EnterEditMode(isPrivate = false))
                store.dispatch(BrowserEditToolbarAction.SearchQueryUpdated(BrowserToolbarQuery(url), isQueryPrefilled = true))
            }
            is ToolbarEvents.SiteInfoClicked -> callbacks.onSiteInfo()
            is ToolbarEvents.DesktopToggleClicked -> callbacks.onToggleDesktop()
            is ToolbarEvents.ReloadClicked -> components.sessionUseCases.reload()
            is ToolbarEvents.StopClicked -> components.sessionUseCases.stopLoading()
            is ToolbarEvents.TabCounterClicked -> callbacks.onOpenTabs()
            is ToolbarEvents.MenuClicked -> callbacks.onOpenMenu()
            is ToolbarEvents.NewTabClicked -> callbacks.onNewTab()
            is ToolbarEvents.CloseTabClicked -> callbacks.onCloseTab()
            is CopyToClipboardClicked -> {
                val url = browserStore.state.selectedTab?.content?.url ?: return
                clipboard()?.setPrimaryClip(ClipData.newPlainText("URL", url))
            }
            is PasteFromClipboardClicked -> {
                val text = clipboardText()
                store.dispatch(BrowserToolbarAction.EnterEditMode(isPrivate = false))
                store.dispatch(BrowserEditToolbarAction.SearchQueryUpdated(BrowserToolbarQuery(text), isQueryPrefilled = true))
            }
            is LoadFromClipboardClicked -> {
                val text = clipboardText()
                if (text.isNotBlank()) load(text)
            }
            else -> next(action)
        }
    }

    fun stop() {
        observers?.cancel()
        observers = null
        autocompleteJob?.cancel()
    }

    private fun load(input: String) {
        val persona = components.personaManager.current.value
        // ENG-04: the typed / pasted path honours the same per-host switches as the extension's onBeforeRequest.
        val url = normalizeInput(input, persona.desktop, components.effectiveHostRewrites())
        val tab = browserStore.state.selectedTab
        if (tab == null) components.tabsUseCases.addTab(url) else components.sessionUseCases.loadUrl(url = url, sessionId = tab.id)
    }

    private fun updateAutocomplete(
        store: Store<BrowserToolbarState, BrowserToolbarAction>,
        query: BrowserToolbarQuery,
        prefilled: Boolean,
    ) {
        autocompleteJob?.cancel()
        // Fenix rule: no suggestion for prefilled text, empty text, or while backspacing.
        val backspacing = query.previous?.let { it.startsWith(query.current) && it.length == query.current.length + 1 } == true
        if (prefilled || query.current.isEmpty() || backspacing) {
            store.dispatch(BrowserEditToolbarAction.AutocompleteSuggestionUpdated(null))
            return
        }
        autocompleteJob = mainScope.launch {
            val result = autocompleteProviders().sorted().firstNotNullOfOrNull { it.getAutocompleteSuggestion(query.current) }
            store.dispatch(BrowserEditToolbarAction.AutocompleteSuggestionUpdated(result))
        }
    }

    // ---- BrowserStore -> toolbar display state ----------------------------------------------------

    private fun startObservers(store: Store<BrowserToolbarState, BrowserToolbarAction>) {
        observers?.cancel()
        // One scope owns every collector so stop() cancels them all together.
        observers = browserStore.flowScoped(dispatcher = Dispatchers.Main) { flow ->
            coroutineScope {
                launch {
                    flow.map { it.selectedTab?.content?.url }.distinctUntilChanged().collect { url ->
                        lastUrl = url ?: ""
                        store.dispatch(BrowserDisplayToolbarAction.PageOriginUpdated(pageOrigin(url)))
                        store.dispatch(
                            BrowserDisplayToolbarAction.PageActionsStartUpdated(
                                startPageActions(browserStore.state.selectedTab?.content?.securityInfo, url),
                            ),
                        )
                    }
                }
                // PERF-04: progress ticks (many per load) only move the progress bar; the reload/stop action list is
                // rebuilt on loading flips (two per load), not per tick.
                launch {
                    flow.map { it.selectedTab?.content?.let { c -> ToolbarState.progress(c.loading, c.progress) } }
                        .distinctUntilChanged()
                        .collect { progress ->
                            store.dispatch(BrowserDisplayToolbarAction.UpdateProgressBarConfig(progress?.let { ProgressBarConfig(it) }))
                        }
                }
                launch {
                    flow.map { it.selectedTab?.content?.loading == true }
                        .distinctUntilChanged()
                        .collect { loading ->
                            store.dispatch(
                                BrowserDisplayToolbarAction.PageActionsEndUpdated(
                                    endPageActions(loading, components.personaManager.current.value),
                                ),
                            )
                        }
                }
                launch {
                    flow.map { it.selectedTab?.content?.securityInfo }.distinctUntilChanged().collect { info ->
                        store.dispatch(BrowserDisplayToolbarAction.PageActionsStartUpdated(startPageActions(info, lastUrl)))
                    }
                }
                launch {
                    flow.map { it.tabs.size }.distinctUntilChanged().collect { count ->
                        store.dispatch(BrowserDisplayToolbarAction.BrowserActionsEndUpdated(endBrowserActions(count)))
                    }
                }
                launch {
                    components.personaManager.current.map { it.desktop }.distinctUntilChanged().collect {
                        val loading = browserStore.state.selectedTab?.content?.loading == true
                        store.dispatch(
                            BrowserDisplayToolbarAction.PageActionsEndUpdated(
                                endPageActions(loading, components.personaManager.current.value),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun pageOrigin(url: String?) = PageOrigin(
        hint = R.string.toolbar_search_hint,
        title = null,
        url = ToolbarState.displayUrl(url),
        contextualMenuOptions = ContextualMenuOption.entries,
        onClick = ToolbarEvents.OriginClicked,
    )

    private fun startPageActions(info: SecurityInfo?, url: String?): List<Action> {
        if (ToolbarState.displayUrl(url).isEmpty()) return emptyList()
        val icon = when (ToolbarState.securityBadge(info)) {
            ToolbarState.SecurityBadge.Unknown -> iconsR.drawable.mozac_ic_globe_24
            ToolbarState.SecurityBadge.Secure -> iconsR.drawable.mozac_ic_lock_24
            ToolbarState.SecurityBadge.Insecure -> iconsR.drawable.mozac_ic_lock_slash_24
        }
        return listOf(
            ActionButtonRes(
                drawableResId = icon,
                contentDescription = R.string.toolbar_site_info,
                onClick = ToolbarEvents.SiteInfoClicked,
            ),
        )
    }

    private fun endPageActions(loading: Boolean, persona: Persona): List<Action> = listOf(
        ActionButtonRes(
            drawableResId = iconsR.drawable.mozac_ic_device_desktop_24,
            contentDescription = if (persona.desktop) R.string.toolbar_desktop_on else R.string.toolbar_desktop_off,
            state = if (persona.desktop) ActionButton.State.ACTIVE else ActionButton.State.DEFAULT,
            onClick = ToolbarEvents.DesktopToggleClicked,
        ),
        if (loading) {
            ActionButtonRes(
                drawableResId = iconsR.drawable.mozac_ic_cross_24,
                contentDescription = R.string.toolbar_stop,
                onClick = ToolbarEvents.StopClicked,
            )
        } else {
            ActionButtonRes(
                drawableResId = iconsR.drawable.mozac_ic_arrow_clockwise_24,
                contentDescription = R.string.toolbar_reload,
                onClick = ToolbarEvents.ReloadClicked,
            )
        },
    )

    private fun endBrowserActions(tabCount: Int): List<Action> = listOf(
        TabCounterAction(
            count = tabCount,
            contentDescription = components.appContext.getString(R.string.toolbar_tabs),
            showPrivacyMask = false,
            onClick = ToolbarEvents.TabCounterClicked,
            onLongClick = BrowserToolbarMenu {
                listOf(
                    BrowserToolbarMenuButton(
                        icon = BrowserToolbarMenuButton.Icon.DrawableResIcon(iconsR.drawable.mozac_ic_plus_24),
                        text = BrowserToolbarMenuButton.Text.StringResText(R.string.toolbar_new_tab),
                        contentDescription = BrowserToolbarMenuButton.ContentDescription.StringResContentDescription(R.string.toolbar_new_tab),
                        onClick = ToolbarEvents.NewTabClicked,
                    ),
                    BrowserToolbarMenuButton(
                        icon = BrowserToolbarMenuButton.Icon.DrawableResIcon(iconsR.drawable.mozac_ic_cross_24),
                        text = BrowserToolbarMenuButton.Text.StringResText(R.string.toolbar_close_tab),
                        contentDescription = BrowserToolbarMenuButton.ContentDescription.StringResContentDescription(R.string.toolbar_close_tab),
                        onClick = ToolbarEvents.CloseTabClicked,
                    ),
                )
            },
        ),
        ActionButtonRes(
            drawableResId = iconsR.drawable.mozac_ic_ellipsis_vertical_24,
            contentDescription = R.string.toolbar_menu,
            onClick = ToolbarEvents.MenuClicked,
        ),
    )

    private fun clipboard(): ClipboardManager? =
        components.appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private fun clipboardText(): String =
        clipboard()?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(components.appContext)?.toString().orEmpty()
}

/**
 * The compose toolbar themed by [app.hatch.ui.HatchTheme]'s Material 3 scheme. compose-browser-toolbar reads two
 * colours (`autofillText`, `selectedText`) from compose-base's `localAcornColors`, whose default is the light
 * palette, so the matching Acorn palette is provided here for dark mode; everything else comes from
 * `MaterialTheme.colorScheme`.
 */
@Composable
fun HatchToolbar(controller: HatchToolbarController, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(localAcornColors provides if (dark) darkColorPalette else lightColorPalette) {
        Box(modifier.fillMaxWidth()) {
            BrowserToolbar(store = controller.store)
        }
    }
}
