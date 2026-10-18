package app.hatch.ui.toolbar

import app.hatch.R
import mozilla.components.browser.state.state.SecurityInfo
import mozilla.components.compose.browser.toolbar.concept.Action
import mozilla.components.compose.browser.toolbar.concept.Action.ActionButtonRes
import mozilla.components.ui.icons.R as iconsR
import mozilla.components.concept.toolbar.AutocompleteProvider
import mozilla.components.concept.toolbar.AutocompleteResult

/** Pure mapping from `BrowserState` facts to what the toolbar shows (JVM-tested; no Android types). */
object ToolbarState {
    /** What the page-start security badge shows. */
    enum class SecurityBadge { Unknown, Secure, Insecure }

    /** Mirrors Fenix 158 `BrowserToolbarMiddleware.updateStartPageActions`: Unknown → globe, else by `isSecure`. */
    fun securityBadge(info: SecurityInfo?): SecurityBadge = when {
        info == null || info == SecurityInfo.Unknown -> SecurityBadge.Unknown
        info.isSecure -> SecurityBadge.Secure
        else -> SecurityBadge.Insecure
    }

    /**
     * The URL as the display toolbar shows it: scheme and `www.` stripped for http(s), trailing slash dropped from
     * bare origins; `about:blank` (the Hatch home) shows nothing so the toolbar renders its hint.
     */
    fun displayUrl(url: String?): String {
        if (url.isNullOrBlank() || url == HOME_URL) return ""
        var s: String = url
        for (scheme in listOf("https://", "http://")) {
            if (s.startsWith(scheme)) { s = s.removePrefix(scheme); break }
        }
        if (s.startsWith("www.") && s.indexOf('/').let { it < 0 || it > 4 }) s = s.removePrefix("www.")
        if (s.endsWith("/") && s.count { it == '/' } == 1) s = s.dropLast(1)
        return s
    }

    /** `about:blank` with no history is the Hatch home: HomeContent overlays it and the toolbar enters edit mode. */
    fun isHome(url: String?, canGoBack: Boolean): Boolean = url == HOME_URL && !canGoBack

    /** The progress bar is shown while loading; `null` hides it. */
    fun progress(loading: Boolean, progress: Int): Int? = if (loading) progress.coerceIn(0, 100) else null

    /** Short persona label for the chip: "Win · Firefox · US" style, built from the preset names. */
    fun personaLabel(identityName: String, regionName: String): String = "$identityName · $regionName"

    /**
     * Edit mode's end actions for the current [query] (UXP-11, Fenix `BrowserToolbarSearchMiddleware` clear button):
     * a clear (x) button while there is text to clear, nothing otherwise.
     */
    fun editEndActions(query: String): List<Action> =
        if (query.isEmpty()) {
            emptyList()
        } else {
            listOf(
                ActionButtonRes(
                    drawableResId = iconsR.drawable.mozac_ic_cross_circle_fill_24,
                    contentDescription = R.string.toolbar_clear,
                    onClick = ToolbarEvents.ClearClicked,
                ),
            )
        }

    const val HOME_URL = "about:blank"
}

/**
 * Inline autocomplete from the open tabs (Fenix uses its `SessionAutocompleteProvider`; this is the same idea with
 * no Places dependency): the first open tab whose host (or `www.`-less host) starts with the typed text wins.
 * Runs before [mozilla.components.browser.domains.autocomplete.ShippedDomainsProvider] (priority 0 < 1).
 */
class OpenTabsAutocompleteProvider(
    private val tabUrls: () -> List<String>,
    override val autocompletePriority: Int = 0,
) : AutocompleteProvider {
    override suspend fun getAutocompleteSuggestion(query: String): AutocompleteResult? = suggest(query, tabUrls())

    companion object {
        const val SOURCE = "open-tabs"

        fun suggest(query: String, urls: List<String>): AutocompleteResult? {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return null
            for (url in urls) {
                val host = hostOf(url) ?: continue
                val bare = host.removePrefix("www.")
                val candidate = when {
                    host.startsWith(q) -> host
                    bare.startsWith(q) -> bare
                    else -> continue
                }
                return AutocompleteResult(
                    input = q,
                    text = query.trim() + candidate.substring(q.length),
                    url = url,
                    source = SOURCE,
                    totalItems = urls.size,
                )
            }
            return null
        }

        internal fun hostOf(url: String): String? {
            val scheme = url.indexOf("://")
            if (scheme <= 0) return null
            if (!url.startsWith("http://") && !url.startsWith("https://")) return null
            val rest = url.substring(scheme + 3)
            val end = rest.indexOfAny(charArrayOf('/', '?', '#', ':')).let { if (it < 0) rest.length else it }
            val host = rest.substring(0, end).lowercase()
            return host.takeIf { it.isNotEmpty() && !it.contains('@') }
        }
    }
}
