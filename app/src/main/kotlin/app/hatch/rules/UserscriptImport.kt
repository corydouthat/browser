package app.hatch.rules

import app.hatch.persona.UserscriptHeader
import app.hatch.persona.UserscriptMeta

/**
 * Turns a `.user.js` / `.user.css` text (from a URL or the clipboard) into a disabled [SiteRule] plus a preview,
 * applying the security rules from `docs/limitations.md` ("GM API subset") and `docs/extension.md`:
 *
 * - `@require` is refused: Hatch never loads external code into a rule (SEC: a remote library would run with the
 *   script's privileges and could change at any time). Inline the library instead.
 * - `GM_xmlhttpRequest` / `GM.xmlHttpRequest` (header grant or body reference) is refused: the GM shim grants no
 *   cross-origin network access (SEC-8 scope).
 * - Any other `@grant` outside the GM subset the shim provides is refused with the list of what exists.
 * - `@match` and match-pattern-shaped `@include`s become `matches`; regex / glob includes are dropped with a warning.
 * - `@run-at document-start|end|idle` maps to the `runAt` wire values; the world is ISOLATED (page-invisible GM
 *   storage), the user can switch to MAIN in the editor.
 *
 * `.user.css` (`==UserStyle==`) files keep their text as the rule's CSS (`cssOrigin: "user"` sheets may use
 * `@-moz-document`); `domain()` / `url-prefix()` / `url()` conditions become match patterns.
 */
object UserscriptImport {
    enum class Kind { USER_JS, USER_CSS }

    data class Result(
        val kind: Kind,
        val meta: UserscriptMeta?,
        val rule: SiteRule?,
        /** Refusals: the import cannot proceed. */
        val problems: List<String>,
        /** Non-fatal notes shown in the preview. */
        val warnings: List<String>,
    ) {
        val ok: Boolean get() = rule != null && problems.isEmpty()
    }

    /** `GM_*` / `GM.*` names `gm/gm-shim.js` provides (plus `none`, which Tampermonkey uses for "no API"). */
    val SUPPORTED_GRANTS: Set<String> = setOf(
        "none", "unsafeWindow",
        "GM_addStyle", "GM_getValue", "GM_setValue", "GM_deleteValue", "GM_listValues", "GM_log", "GM_info",
        "GM_registerMenuCommand",
        "GM.addStyle", "GM.getValue", "GM.setValue", "GM.deleteValue", "GM.listValues", "GM.log", "GM.info",
        "GM.registerMenuCommand",
    )

    const val REQUIRE_REFUSED = "@require is not supported: Hatch never loads external code into a rule. Inline the library in the script instead."
    const val XHR_REFUSED = "GM_xmlhttpRequest is not available: userscripts in Hatch get no cross-origin network access."
    const val NO_HEADER = "No ==UserScript== header found. Paste plain JavaScript into a new rule instead."
    const val NO_MATCHES = "The header has no @match (or usable @include); the rule would never run."

    private val xhrUse = Regex("\\bGM_xmlhttpRequest\\b|\\bGM\\.xmlHttpRequest\\b")
    private val userStyleStart = Regex("==UserStyle==")
    private val userStyleBlock = Regex("/\\*\\s*==UserStyle==([\\s\\S]*?)==/UserStyle==\\s*\\*/")
    private val headerKey = Regex("^\\s*@([A-Za-z0-9_:\\-]+)(?:[ \\t]+(.*?))?\\s*$")
    private val mozDocument = Regex("@-moz-document\\s+([^{]+)\\{")
    private val mozCondition = Regex("(domain|url-prefix|url|regexp)\\(\\s*([\"']?)(.*?)\\2\\s*\\)")

    fun kindOf(text: String, hintUrl: String?): Kind {
        val u = hintUrl?.lowercase()?.substringBefore('?')?.substringBefore('#') ?: ""
        if (u.endsWith(".user.css")) return Kind.USER_CSS
        if (u.endsWith(".user.js")) return Kind.USER_JS
        return if (userStyleStart.containsMatchIn(text) && UserscriptHeader.parse(text) == null) Kind.USER_CSS else Kind.USER_JS
    }

    /**
     * @param text the file contents
     * @param hintUrl where it came from (https URL) or null for the clipboard; becomes `SiteRule.source`
     */
    fun fromText(text: String, hintUrl: String?): Result = when (kindOf(text, hintUrl)) {
        Kind.USER_JS -> fromUserJs(text, hintUrl)
        Kind.USER_CSS -> fromUserCss(text, hintUrl)
    }

    fun runAtOf(headerValue: String?): String = when (headerValue?.trim()?.lowercase()) {
        "document-start", "document_start" -> "document_start"
        "document-end", "document_end" -> "document_end"
        else -> "document_idle"
    }

    private fun source(hintUrl: String?): String = hintUrl?.takeIf { it.isNotBlank() } ?: "clipboard"

    private fun fromUserJs(text: String, hintUrl: String?): Result {
        val meta = UserscriptHeader.parse(text)
            ?: return Result(Kind.USER_JS, null, null, listOf(NO_HEADER), emptyList())
        val problems = ArrayList<String>()
        val warnings = ArrayList<String>()

        if (meta.requires.isNotEmpty()) problems += REQUIRE_REFUSED + " (" + meta.requires.joinToString(", ") + ")"
        val grants = meta.grants.map { it.trim() }.filter { it.isNotEmpty() }
        if (grants.any { it == "GM_xmlhttpRequest" || it == "GM.xmlHttpRequest" } || xhrUse.containsMatchIn(text)) problems += XHR_REFUSED
        grants.filterNot { it in SUPPORTED_GRANTS || it == "GM_xmlhttpRequest" || it == "GM.xmlHttpRequest" }.forEach { g ->
            problems += "Unsupported @grant $g. Hatch provides: " + SUPPORTED_GRANTS.filter { it.startsWith("GM_") }.joinToString(", ") + " (and the GM.* promise variants)."
        }

        val matches = ArrayList<String>()
        meta.matches.forEach { m ->
            if (RuleValidation.isValidMatchPattern(m)) matches += m.trim() else warnings += "Ignored invalid @match $m"
        }
        meta.includes.forEach { inc ->
            when {
                RuleValidation.isValidMatchPattern(inc) -> matches += inc.trim()
                inc.startsWith("/") -> warnings += "Ignored regex @include $inc (not a match pattern)"
                else -> warnings += "Ignored glob @include $inc (rewrite it as a match pattern)"
            }
        }
        if (matches.isEmpty()) problems += NO_MATCHES
        val excludes = ArrayList<String>()
        meta.excludes.forEach { ex ->
            if (RuleValidation.isValidMatchPattern(ex)) excludes += ex.trim() else warnings += "Ignored @exclude $ex (not a match pattern)"
        }
        val runAtHeader = meta.runAt
        if (runAtHeader != null && runAtOf(runAtHeader) == "document_idle" && runAtHeader.trim().lowercase() !in setOf("document-idle", "document_idle")) {
            warnings += "Unknown @run-at $runAtHeader; using document_idle"
        }
        if (grants.isEmpty()) warnings += "No @grant: the script still gets unsafeWindow and the GM subset (Hatch does not sandbox by grant)"

        val rule = SiteRule(
            id = SiteRule.newId(),
            name = meta.name?.takeIf { it.isNotBlank() } ?: hintUrl?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Imported script",
            matches = matches.distinct(),
            excludeMatches = excludes.distinct(),
            js = text,
            css = null,
            world = "ISOLATED",
            runAt = runAtOf(meta.runAt),
            enabled = false,
            source = source(hintUrl),
        )
        return Result(Kind.USER_JS, meta, if (problems.isEmpty()) rule else null, problems, warnings)
    }

    /** `==UserStyle==` header values (Stylus format): `@name`, `@version`, `@description`, ignoring the rest. */
    internal fun userStyleHeader(text: String): Map<String, String> {
        val block = userStyleBlock.find(text)?.groupValues?.get(1) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        block.lines().forEach { line ->
            val m = headerKey.find(line) ?: return@forEach
            val k = m.groupValues[1]
            val v = m.groupValues[2].trim()
            if (v.isNotEmpty() && !out.containsKey(k)) out[k] = v
        }
        return out
    }

    /** Match patterns derived from every `@-moz-document` prelude in [css]; empty when there is none. */
    internal fun mozDocumentMatches(css: String, warnings: MutableList<String>): List<String> {
        val out = LinkedHashSet<String>()
        mozDocument.findAll(css).forEach { block ->
            mozCondition.findAll(block.groupValues[1]).forEach { c ->
                val fn = c.groupValues[1]
                val arg = c.groupValues[3].trim()
                when (fn) {
                    "domain" -> if (arg.isNotEmpty()) { out += "*://$arg/*"; out += "*://*.$arg/*" }
                    "url-prefix" -> if (arg.isNotEmpty()) {
                        val p = if (arg.endsWith("*")) arg else "$arg*"
                        if (RuleValidation.isValidMatchPattern(p)) out += p else warnings += "url-prefix($arg) is not a match pattern; matching all sites"
                    }
                    "url" -> if (RuleValidation.isValidMatchPattern(arg)) out += arg else warnings += "url($arg) is not a match pattern; matching all sites"
                    "regexp" -> warnings += "regexp() conditions cannot be turned into match patterns; the sheet is registered for all sites and @-moz-document still gates it"
                }
            }
        }
        return out.toList()
    }

    private fun fromUserCss(text: String, hintUrl: String?): Result {
        val problems = ArrayList<String>()
        val warnings = ArrayList<String>()
        if (text.isBlank()) problems += "The style sheet is empty"
        val header = userStyleHeader(text)
        if (header.isEmpty()) warnings += "No ==UserStyle== header; importing as a plain user style sheet"
        var matches = mozDocumentMatches(text, warnings)
        if (matches.isEmpty() || warnings.any { it.endsWith("matching all sites") || it.contains("registered for all sites") }) {
            if (matches.isEmpty()) warnings += "No @-moz-document domain()/url-prefix(); the sheet applies to every site"
            matches = listOf("*://*/*")
        }
        val rule = SiteRule(
            id = SiteRule.newId(),
            name = header["name"] ?: hintUrl?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Imported style",
            matches = matches,
            excludeMatches = emptyList(),
            js = null,
            css = text,
            world = "ISOLATED",
            runAt = "document_start",
            enabled = false,
            source = source(hintUrl),
        )
        return Result(Kind.USER_CSS, null, if (problems.isEmpty()) rule else null, problems, warnings)
    }

    /** `https://` only, like add-on installs (SEC-7); the body is capped by [MAX_BYTES]. */
    fun isAllowedUrl(raw: String): Boolean {
        val s = raw.trim()
        if (!s.startsWith("https://", ignoreCase = true)) return false
        val host = s.substring(8).substringBefore('/').substringBefore('?').substringBefore('#')
        return host.isNotEmpty()
    }

    const val MAX_BYTES: Long = 1L shl 20
    const val URL_REJECTED = "Only https:// script URLs are allowed"
}
