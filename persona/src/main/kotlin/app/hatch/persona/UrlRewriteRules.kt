package app.hatch.persona

import kotlinx.serialization.Serializable

/**
 * One host rewrite the extension applies to `main_frame` requests (`webRequest.onBeforeRequest`): [from] is an
 * exact hostname (case-insensitive) or a JS regex literal `"/^.../"` whose `$n` groups [to] may reference
 * (`background.js normalizeHostRewrites`).
 */
@Serializable
data class HostRewrite(val from: String, val to: String)

/**
 * Mobile-URL -> desktop-URL rewriting, shared by the app (address bar / link handling) and, as a host table,
 * by the extension. Rules, in order:
 *  1. exact host map ([defaultHostRewrites]): `m.youtube.com` -> `www.youtube.com`, `mobile.twitter.com` ->
 *     `x.com`, `m.facebook.com` -> `www.facebook.com`, ...
 *  2. Wikimedia mobile hosts: `<lang>.m.wikipedia.org` -> `<lang>.wikipedia.org` (also wiktionary, wikimedia,
 *     wikibooks, wikiquote, wikinews, wikisource, wikiversity, wikivoyage, mediawiki).
 *  3. generic: strip one leading `m.` / `mobile.` / `touch.` label when at least two labels remain.
 *  4. Amazon `/gp/aw/...` (mobile) paths: `/gp/aw/d/<ASIN>` -> `/dp/<ASIN>`, anything else under `/gp/aw` -> `/`.
 *  5. drop the `mf_useformat` (Wikipedia mobile format) and `app=m` (YouTube) query params.
 * Only `http`/`https` URLs are touched; scheme, userinfo, port and fragment are preserved; the host is
 * compared case-insensitively. Returns null when nothing changes.
 */
object UrlRewriteRules {
    val defaultHostRewrites: List<HostRewrite> = listOf(
        HostRewrite("m.youtube.com", "www.youtube.com"),
        HostRewrite("mobile.twitter.com", "x.com"),
        HostRewrite("m.twitter.com", "x.com"),
        HostRewrite("mobile.x.com", "x.com"),
        HostRewrite("m.facebook.com", "www.facebook.com"),
        HostRewrite("touch.facebook.com", "www.facebook.com"),
        HostRewrite("mbasic.facebook.com", "www.facebook.com"),
        HostRewrite("m.reddit.com", "www.reddit.com"),
        HostRewrite("m.imdb.com", "www.imdb.com"),
        HostRewrite("m.soundcloud.com", "soundcloud.com"),
        HostRewrite("m.twitch.tv", "www.twitch.tv"),
        HostRewrite("m.aliexpress.com", "www.aliexpress.com"),
    )

    /**
     * The Wikimedia rule as a JS regex rule for the extension: `<lang>.m.<project>.org` -> `<lang>.<project>.org`
     * (same project list as [wikimediaDomains]).
     */
    const val WIKIMEDIA_HOST_RULE: String =
        "/^([a-z-]+)\\.m\\.(wikipedia|wiktionary|wikimedia|wikibooks|wikiquote|wikinews|wikisource|wikiversity|wikivoyage|mediawiki|wikidata)\\.org$/"
    const val WIKIMEDIA_HOST_TARGET: String = "$1.$2.org"

    /** The generic strip as a JS regex rule: one leading `m.` / `mobile.` / `touch.` label when >= 2 labels remain. */
    const val GENERIC_MOBILE_HOST_RULE: String = "/^(?:m|mobile|touch)\\.([^.]+\\.[^.]+.*)$/"
    const val GENERIC_MOBILE_HOST_TARGET: String = "$1"

    /**
     * The table the app pushes to the extension (`{type:"hostRewrites"}` / `state.hostRewrites`): the exact hosts
     * first, then the Wikimedia rule, then the generic strip. Together they equal [desktopHost] for every host
     * (locked by `UrlRewriteRulesTest`), so a followed link and a typed URL get the same rewrite.
     */
    val extensionHostRewrites: List<HostRewrite> = defaultHostRewrites +
        HostRewrite(WIKIMEDIA_HOST_RULE, WIKIMEDIA_HOST_TARGET) +
        HostRewrite(GENERIC_MOBILE_HOST_RULE, GENERIC_MOBILE_HOST_TARGET)

    private val hostMap: Map<String, String> = defaultHostRewrites.associate { it.from to it.to }

    private val mobilePrefixes = setOf("m", "mobile", "touch")

    private val wikimediaDomains = setOf(
        "wikipedia.org", "wiktionary.org", "wikimedia.org", "wikibooks.org", "wikiquote.org", "wikinews.org",
        "wikisource.org", "wikiversity.org", "wikivoyage.org", "mediawiki.org", "wikidata.org",
    )

    private val urlPattern = Regex("^(https?)://([^/?#]*)([^?#]*)(\\?[^#]*)?(#.*)?$", RegexOption.IGNORE_CASE)
    private val amazonHost = Regex("^(www\\.)?amazon\\.[a-z.]+$")
    private val amazonMobileProduct = Regex("^/gp/aw/d/([A-Z0-9]{10})(?:[/?].*|$)")

    /**
     * What `background.js rewriteHost` does with [extensionHostRewrites] for [host] (first matching rule wins;
     * a regex rule only counts when its replacement changes the host). Null when unchanged. Used by the JVM test
     * to prove the extension table and [desktopHost] agree.
     */
    fun extensionRewrite(host: String): String? = rewriteWith(host, extensionHostRewrites)

    /**
     * Applies a host table of the extension's shape ([HostRewrite] list, exact hosts or `/regex/` rules, first
     * match wins) to [host]. This is the path a *filtered* table takes (ENG-04: `Settings → Host rewrites` switches
     * a rule off app-side, `HostRewriteFilter.effective`), so a typed or pasted URL honours the same switches as a
     * followed link. Null when unchanged.
     */
    fun rewriteWith(host: String, hostRewrites: List<HostRewrite>): String? {
        val lower = host.lowercase()
        for (r in hostRewrites) {
            if (r.from.length > 2 && r.from.startsWith("/") && r.from.endsWith("/")) {
                val re = Regex(r.from.substring(1, r.from.length - 1), RegexOption.IGNORE_CASE)
                if (re.containsMatchIn(lower)) {
                    val to = re.replace(lower, r.to).lowercase()
                    if (to.isNotEmpty() && to != lower) return to
                }
            } else if (r.from == lower && r.to != lower) {
                return r.to
            }
        }
        return null
    }

    /**
     * Host-only rewrite (what the extension's exact table + generic strip do). Null when unchanged. With the full
     * [extensionHostRewrites] table the hand-coded fast path runs (proven equal by `UrlRewriteRulesTest`); any
     * other table (a user-filtered one) goes through [rewriteWith].
     */
    fun desktopHost(host: String, hostRewrites: List<HostRewrite> = extensionHostRewrites): String? {
        if (hostRewrites !== extensionHostRewrites) return rewriteWith(host, hostRewrites)
        val lower = host.lowercase()
        hostMap[lower]?.let { return it }
        val labels = lower.split('.')
        if (labels.size >= 3 && labels[1] == "m" && labels.drop(2).joinToString(".") in wikimediaDomains) {
            return (listOf(labels[0]) + labels.drop(2)).joinToString(".")
        }
        if (labels.size >= 3 && labels[0] in mobilePrefixes) {
            return labels.drop(1).joinToString(".")
        }
        return null
    }

    /**
     * Mobile URL -> desktop URL for the app-side paths (address bar, clipboard load). [hostRewrites] is the host
     * table to honour (ENG-04: the app passes `Components.effectiveHostRewrites()`, the table minus the hosts the
     * user switched off); the Amazon path and query rules below are independent of it.
     */
    fun desktopUrl(url: String, hostRewrites: List<HostRewrite> = extensionHostRewrites): String? {
        val m = urlPattern.find(url.trim()) ?: return null
        val scheme = m.groupValues[1]
        val authority = m.groupValues[2]
        var path = m.groupValues[3]
        val query = m.groupValues[4]
        val fragment = m.groupValues[5]

        val at = authority.lastIndexOf('@')
        val userinfo = if (at >= 0) authority.substring(0, at + 1) else ""
        val hostPort = authority.substring(at + 1)
        val colon = hostPort.lastIndexOf(':')
        val host = if (colon >= 0 && !hostPort.endsWith("]")) hostPort.substring(0, colon) else hostPort
        val port = if (colon >= 0 && !hostPort.endsWith("]")) hostPort.substring(colon) else ""
        if (host.isEmpty()) return null

        var changed = false
        var newHost = host
        desktopHost(host, hostRewrites)?.let { newHost = it; changed = true }

        if (amazonHost.matches(newHost.lowercase()) && (path == "/gp/aw" || path.startsWith("/gp/aw/"))) {
            val product = amazonMobileProduct.find(path)
            path = if (product != null) "/dp/${product.groupValues[1]}" else "/"
            changed = true
        }

        var newQuery = query
        if (query.isNotEmpty()) {
            val kept = query.substring(1).split('&').filter { param ->
                if (param.isEmpty()) return@filter false
                val eq = param.indexOf('=')
                val key = if (eq >= 0) param.substring(0, eq) else param
                val value = if (eq >= 0) param.substring(eq + 1) else ""
                when (key) {
                    "mf_useformat" -> false
                    "app" -> value != "m"
                    else -> true
                }
            }
            val rebuilt = if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
            if (rebuilt != query) {
                newQuery = rebuilt
                changed = true
            }
        }

        if (!changed) return null
        return "$scheme://$userinfo$newHost$port$path$newQuery$fragment"
    }
}
