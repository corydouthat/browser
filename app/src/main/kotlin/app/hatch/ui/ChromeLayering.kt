package app.hatch.ui

import mozilla.components.browser.state.state.TabSessionState

/**
 * Pure layering decisions of `BrowserScreen` (JVM-tested; the Compose tree cannot be exercised here without
 * Robolectric).
 */
object ChromeLayering {
    /**
     * UXP-01: the transparent "tap outside the address bar leaves edit mode" catcher is composed above the web
     * content only. On the Hatch home the controls (desktop switch, "Change…", homepage chip, recent tabs) must get
     * the first tap, so no catcher is layered over `HomeContent`; its own callbacks leave edit mode instead.
     */
    fun editTapCatcherOverContent(editing: Boolean, isHome: Boolean): Boolean = editing && !isHome
}

/**
 * PERF-04: the handful of selected-tab facts the chrome reads, as a value type. `BrowserStore` copies
 * `ContentState` on every progress tick; observing the whole `TabSessionState` recomposed `HatchApp` and
 * `BrowserScreen` dozens of times per page load. Mapping to this snapshot makes `observeAsComposableState`'s
 * structural equality skip every store update that changes nothing the chrome shows.
 */
data class SelectedTabFacts(
    val id: String,
    val url: String,
    val title: String,
    val loading: Boolean,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val fullScreen: Boolean,
    val crashed: Boolean,
) {
    companion object {
        fun of(tab: TabSessionState): SelectedTabFacts = SelectedTabFacts(
            id = tab.id,
            url = tab.content.url,
            title = tab.content.title,
            loading = tab.content.loading,
            canGoBack = tab.content.canGoBack,
            canGoForward = tab.content.canGoForward,
            fullScreen = tab.content.fullScreen,
            crashed = tab.engineState.crashed,
        )
    }
}
