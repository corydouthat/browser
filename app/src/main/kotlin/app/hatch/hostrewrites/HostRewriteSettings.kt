package app.hatch.hostrewrites

import app.hatch.persona.HostRewrite
import app.hatch.persona.UrlRewriteRules

/**
 * Per-host enable/disable for the extension's mobile→desktop host rewrites (`UrlRewriteRules.extensionHostRewrites`).
 *
 * The extension contract (`background.js applyHostRewrites`, `{type:"hostRewrites", rules:[{from,to}]}`) has no
 * "disabled" field and replaces the whole table on every message, so the app filters the list before sending:
 * a disabled rule is simply absent. The set of disabled `from` keys is persisted in `AppSettings.disabledHostRewrites`.
 */
object HostRewriteFilter {
    /** Human-readable label for a rule (`from` is either an exact host or a `/regex/` literal). */
    fun label(rule: HostRewrite): String = when (rule.from) {
        UrlRewriteRules.WIKIMEDIA_HOST_RULE -> "Wikimedia mobile hosts (<lang>.m.wikipedia.org → <lang>.wikipedia.org)"
        UrlRewriteRules.GENERIC_MOBILE_HOST_RULE -> "Generic m. / mobile. / touch. prefix strip"
        else -> "${rule.from} → ${rule.to}"
    }

    fun isRegex(rule: HostRewrite): Boolean = rule.from.length > 2 && rule.from.startsWith("/") && rule.from.endsWith("/")

    /** The rules to push to the extension: [all] minus those whose `from` is in [disabled], order preserved. */
    fun effective(all: List<HostRewrite>, disabled: Set<String>): List<HostRewrite> =
        if (disabled.isEmpty()) all else all.filterNot { it.from in disabled }

    /** [disabled] with [from] toggled; unknown keys (rules that no longer exist) are dropped so the set cannot grow stale. */
    fun toggle(all: List<HostRewrite>, disabled: Set<String>, from: String, enabled: Boolean): Set<String> {
        val known = all.mapTo(HashSet()) { it.from }
        val next = disabled.filterTo(LinkedHashSet()) { it in known }
        if (enabled) next.remove(from) else if (from in known) next.add(from)
        return next
    }
}
