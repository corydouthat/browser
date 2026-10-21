package app.hatch.ui.toolbar

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import app.hatch.R
import app.hatch.engine.UrlHost
import app.hatch.ui.theme.HatchColors
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

    /** The persona chip's security glyph (design: lock, globe for insecure, warning for a broken https connection). */
    enum class ChipSecurity { Lock, Globe, Warning }

    /**
     * Chip glyph for the selected tab: [SecurityInfo.Secure] → lock; [SecurityInfo.Insecure] on an `https:` URL (bad
     * certificate, mixed content) → warning; anything else (plain http, unknown while loading, about: pages) → globe.
     */
    fun chipSecurity(info: SecurityInfo?, url: String?): ChipSecurity = when (securityBadge(info)) {
        SecurityBadge.Secure -> ChipSecurity.Lock
        SecurityBadge.Insecure -> if (url?.startsWith("https://", ignoreCase = true) == true) ChipSecurity.Warning else ChipSecurity.Globe
        SecurityBadge.Unknown -> ChipSecurity.Globe
    }

    /**
     * What the display toolbar's pill shows (Chrome-style, the design's "host" text): the host without `www.` for
     * http(s) URLs, [displayUrl] for everything else (about:, file:, moz-extension:), empty for the Hatch home so the
     * hint shows. Edit mode always gets the full URL.
     */
    fun displayHost(url: String?): String {
        val shown = displayUrl(url)
        if (url == null || shown.isEmpty()) return shown
        // RS-03: the real host, also for userinfo URLs (`https://accounts.google.com:x@evil.com/` shows evil.com).
        val httpLike = url.startsWith("http://") || url.startsWith("https://")
        val host = (if (httpLike) UrlHost.of(url) else null) ?: return shown
        return host.removePrefix("www.").takeIf { it.isNotEmpty() } ?: shown
    }

    /** The tab counter's number: the count up to 99, then "∞" (AC ui-tabcounter's rule, fits the 20 dp square). */
    fun tabCountLabel(count: Int): String = if (count > MAX_VISIBLE_TABS) "∞" else count.coerceAtLeast(0).toString()

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
    const val MAX_VISIBLE_TABS = 99
}

/**
 * The toolbar row's colours for one persona mode, per the mockups of section 01 of the design:
 * - desktop: row and status bar [HatchColors.tb], icons/text [HatchColors.ti], pill [HatchColors.pl], persona chip
 *   filled [HatchColors.ac] with [HatchColors.oa] glyphs and the [HatchColors.sep] separator, no bottom divider;
 * - phone: row and status bar [HatchColors.bg], icons/text [HatchColors.ink], pill [HatchColors.s1], chip unfilled
 *   with [HatchColors.mu] glyphs and an [HatchColors.ln2] separator; the find row adds the [HatchColors.ln] hairline.
 * Hints and the find counter are [HatchColors.mu]; the progress bar and the edit / find outline are [HatchColors.ac].
 */
@Immutable
data class ToolbarColors(
    val background: Color,
    val content: Color,
    val pill: Color,
    val chipFill: Color,
    val chipContent: Color,
    val chipSeparator: Color,
    val text: Color,
    val hint: Color,
    val outline: Color,
    val progress: Color,
    val progressTrack: Color,
    val divider: Color,
) {
    companion object {
        fun of(c: HatchColors, desktop: Boolean): ToolbarColors = ToolbarColors(
            background = if (desktop) c.tb else c.bg,
            content = if (desktop) c.ti else c.ink,
            pill = if (desktop) c.pl else c.s1,
            chipFill = if (desktop) c.ac else Color.Transparent,
            chipContent = if (desktop) c.oa else c.mu,
            chipSeparator = if (desktop) c.sep else c.ln2,
            // Desktop: the host is ti on the white/dark pill (mockup); phone: ink on s1.
            text = if (desktop) c.ti else c.ink,
            hint = c.mu,
            outline = c.ac,
            progress = c.ac,
            progressTrack = c.ac.copy(alpha = PROGRESS_TRACK_ALPHA),
            divider = if (desktop) Color.Transparent else c.ln,
        )

        /** "progress 3dp · primary on track": the track is the primary at low alpha, visible only while loading. */
        const val PROGRESS_TRACK_ALPHA = 0.24f
    }
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
            if (!url.startsWith("http://") && !url.startsWith("https://")) return null
            // Tabs whose URL carries userinfo are never offered as completions (the completion would carry it too).
            if (UrlHost.hasUserInfo(url)) return null
            return UrlHost.of(url)
        }
    }
}
