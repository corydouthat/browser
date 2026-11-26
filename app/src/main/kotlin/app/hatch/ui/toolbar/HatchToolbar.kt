package app.hatch.ui.toolbar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.hatch.Components
import app.hatch.R
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
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.SecurityInfo
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.compose.browser.toolbar.concept.PageOrigin.Companion.PageOriginContextualMenuInteractions.CopyToClipboardClicked
import mozilla.components.compose.browser.toolbar.concept.PageOrigin.Companion.PageOriginContextualMenuInteractions.LoadFromClipboardClicked
import mozilla.components.compose.browser.toolbar.concept.PageOrigin.Companion.PageOriginContextualMenuInteractions.PasteFromClipboardClicked
import mozilla.components.compose.browser.toolbar.store.BrowserDisplayToolbarAction
import mozilla.components.compose.browser.toolbar.store.BrowserEditToolbarAction
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarAction
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction.BrowserToolbarEvent
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
import mozilla.components.lib.state.ext.observeAsComposableState

/** Events Hatch's toolbar elements dispatch on the [BrowserToolbarStore]; the middleware maps them to use cases. */
sealed interface ToolbarEvents : BrowserToolbarEvent {
    data object OriginClicked : ToolbarEvents
    data object SiteInfoClicked : ToolbarEvents
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
    val onNewTab: () -> Unit,
    val onCloseTab: () -> Unit,
    /** Edit mode opened from the origin tap or "Paste" (the chrome closes the site-info card under it). */
    val onEditStarted: () -> Unit = {},
)

/**
 * Owns the [BrowserToolbarStore] (compose-browser-toolbar 158, `ToolbarGravity.Top`) for the Activity's lifetime.
 * The store holds the toolbar's mode, the edit state (query, suggestion, prefilled flag, end actions) and the
 * progress bar; the display facts (host, security, tab count) are read from the [BrowserStore] by [HatchToolbar].
 * The [HatchToolbarMiddleware] handles every [BrowserToolbarEvent] (Fenix's `BrowserToolbarMiddleware` is the
 * pattern); edit mode gets inline autocomplete from the open tabs and `ShippedDomainsProvider`, and `CommitUrl` goes
 * through the existing [normalizeInput]. The store's display actions (page origin, page/browser actions) are not used:
 * the display toolbar is hand-rolled ([DisplayToolbar]) because AC's `BrowserToolbar` hard-codes its sizes.
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

    /** The browser state the display toolbar reads (selected tab URL / security, tab count). */
    internal val browserStore: BrowserStore get() = components.store

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
                callbacks.onEditStarted()
                val url = browserStore.state.selectedTab?.content?.url?.takeUnless { it == ToolbarState.HOME_URL } ?: ""
                store.dispatch(BrowserToolbarAction.EnterEditMode(isPrivate = false))
                store.dispatch(BrowserEditToolbarAction.SearchQueryUpdated(BrowserToolbarQuery(url), isQueryPrefilled = true))
            }
            is ToolbarEvents.SiteInfoClicked -> callbacks.onSiteInfo()
            is ToolbarEvents.TabCounterClicked -> callbacks.onOpenTabs()
            is ToolbarEvents.MenuClicked -> callbacks.onOpenMenu()
            is ToolbarEvents.NewTabClicked -> callbacks.onNewTab()
            is ToolbarEvents.CloseTabClicked -> callbacks.onCloseTab()
            is CopyToClipboardClicked -> {
                val url = browserStore.state.selectedTab?.content?.url ?: return
                clipboard()?.setPrimaryClip(ClipData.newPlainText("URL", url))
            }
            is PasteFromClipboardClicked -> {
                callbacks.onEditStarted()
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

    // ---- BrowserStore -> toolbar store ------------------------------------------------------------------------------

    private fun startObservers(store: Store<BrowserToolbarState, BrowserToolbarAction>) {
        observers?.cancel()
        // One scope owns every collector so stop() cancels them all together. Only the progress bar goes through the
        // toolbar store; reload / stop and the desktop toggle are no longer toolbar actions (menu, F5 / Ctrl+R).
        observers = browserStore.flowScoped(dispatcher = Dispatchers.Main) { flow ->
            coroutineScope {
                launch {
                    flow.map { it.selectedTab?.content?.let { c -> ToolbarState.progress(c.loading, c.progress) } }
                        .distinctUntilChanged()
                        .collect { progress ->
                            store.dispatch(BrowserDisplayToolbarAction.UpdateProgressBarConfig(progress?.let { ProgressBarConfig(it) }))
                        }
                }
            }
        }
    }

    private fun clipboard(): ClipboardManager? =
        components.appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private fun clipboardText(): String =
        clipboard()?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(components.appContext)?.toString().orEmpty()
}

/**
 * The display facts the toolbar reads from the [BrowserStore], as a value (PERF-04: progress ticks and other
 * `ContentState` copies do not recompose the toolbar unless one of these changes).
 */
internal data class ToolbarFacts(val url: String?, val security: SecurityInfo?, val tabCount: Int) {
    companion object {
        fun of(state: BrowserState): ToolbarFacts = ToolbarFacts(
            url = state.selectedTab?.content?.url,
            security = state.selectedTab?.content?.securityInfo,
            tabCount = state.tabs.size,
        )
    }
}

/**
 * Hatch's toolbar: the hand-rolled [DisplayToolbar] or [EditToolbar] over the [BrowserToolbarStore], switched on
 * the store's mode. [colors] come from [rememberToolbarColors] (BrowserScreen shares them with the status-bar strip);
 * [desktop] is the persona's flag (chip glyph). Every interaction is dispatched on the store, exactly as AC's
 * `BrowserToolbar` does (BrowserToolbar.kt:105-107 at FIREFOX_158_0_RELEASE).
 */
@Composable
fun HatchToolbar(controller: HatchToolbarController, colors: ToolbarColors, desktop: Boolean, modifier: Modifier = Modifier) {
    val store = controller.store
    val state by store.observeAsComposableState { it }
    val facts by controller.browserStore.observeAsComposableState { ToolbarFacts.of(it) }
    val onEvent: (BrowserToolbarEvent) -> Unit = { store.dispatch(it) }
    if (state.isEditMode()) {
        val edit = state.editState
        EditToolbar(
            query = edit.query.current,
            hint = stringResource(edit.hint),
            suggestion = edit.suggestion,
            isQueryPrefilled = edit.isQueryPrefilled,
            endActions = edit.editActionsEnd,
            tabCount = facts.tabCount,
            colors = colors,
            onUrlEdit = { query: BrowserToolbarQuery -> store.dispatch(BrowserEditToolbarAction.SearchQueryUpdated(query)) },
            onUrlCommitted = { text -> store.dispatch(BrowserToolbarAction.CommitUrl(text)) },
            onEvent = onEvent,
            modifier = modifier,
        )
    } else {
        DisplayToolbar(
            model = DisplayToolbarModel(
                host = ToolbarState.displayHost(facts.url),
                security = ToolbarState.chipSecurity(facts.security, facts.url),
                desktop = desktop,
                progress = state.displayState.progressBarConfig?.progress,
                tabCount = facts.tabCount,
            ),
            colors = colors,
            onEvent = onEvent,
            modifier = modifier,
        )
    }
}
