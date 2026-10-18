package app.hatch.rules

/**
 * Validation of a [SiteRule] before it is persisted and pushed to the extension (pure, JVM-tested).
 *
 * Mirrors `background.js ruleProblem()` (id, non-empty matches, js or css) and adds what the extension can only
 * report after the fact (`contentScripts.register` throws on a bad match pattern): the WebExtension match-pattern
 * grammar `<scheme>://<host><path>` from MDN / `toolkit/components/extensions/MatchPattern.cpp` at
 * FIREFOX_158_0_RELEASE — scheme `*` (http, https, ws, wss), `http(s)`, `ws(s)`, `ftp(s)` or `file`; host `*`,
 * `*.<domain>` or `<domain>` (no port, `file:` may have an empty host); path starts with `/`; `<all_urls>`.
 */
object RuleValidation {
    val WORLDS: List<String> = listOf("ISOLATED", "MAIN")
    val RUN_AT: List<String> = listOf("document_start", "document_end", "document_idle")

    private val pattern = Regex("^(\\*|https?|wss?|ftps?|file)://([^/]*)(/.*)$")
    private val hostLabel = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?$")

    fun isValidMatchPattern(p: String): Boolean {
        val s = p.trim()
        if (s == "<all_urls>") return true
        val m = pattern.find(s) ?: return false
        val scheme = m.groupValues[1]
        val host = m.groupValues[2]
        if (host.isEmpty()) return scheme == "file"
        if (host == "*") return true
        val bare = if (host.startsWith("*.")) host.substring(2) else host
        if (bare.isEmpty() || bare.contains('*') || bare.contains(':') || bare.contains('@')) return false
        return bare.split('.').all { hostLabel.matches(it) }
    }

    /** Splits a textarea ("one pattern per line, commas allowed") into trimmed, non-empty patterns. */
    fun splitPatterns(text: String): List<String> =
        text.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Human-readable problems; empty means the rule can be saved. */
    fun problems(rule: SiteRule): List<String> {
        val out = ArrayList<String>()
        if (rule.id.isBlank()) out += "Rule has no id"
        if (rule.name.isBlank()) out += "Name is empty"
        if (rule.matches.isEmpty()) out += "At least one match pattern is required (e.g. *://*.example.com/*)"
        rule.matches.filterNot(::isValidMatchPattern).forEach { out += "Invalid match pattern: $it" }
        rule.excludeMatches.filterNot(::isValidMatchPattern).forEach { out += "Invalid exclude pattern: $it" }
        if (rule.js.isNullOrBlank() && rule.css.isNullOrBlank()) out += "The rule needs JavaScript or CSS"
        if (rule.world !in WORLDS) out += "World must be ISOLATED or MAIN"
        if (rule.runAt !in RUN_AT) out += "Run at must be document_start, document_end or document_idle"
        return out
    }

    fun isValid(rule: SiteRule): Boolean = problems(rule).isEmpty()

    /** Trims strings and drops empty lines so what is saved is what the extension registers. */
    fun normalize(rule: SiteRule): SiteRule = rule.copy(
        name = rule.name.trim(),
        matches = rule.matches.map { it.trim() }.filter { it.isNotEmpty() },
        excludeMatches = rule.excludeMatches.map { it.trim() }.filter { it.isNotEmpty() },
        js = rule.js?.takeIf { it.isNotBlank() },
        css = rule.css?.takeIf { it.isNotBlank() },
    )

    const val BUNDLED_SOURCE = "hatch-bundled"

    fun isBundled(rule: SiteRule): Boolean = rule.source == BUNDLED_SOURCE
}
