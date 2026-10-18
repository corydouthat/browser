package app.hatch.rules

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONArray
import java.io.File
import java.util.UUID

/**
 * One per-site rule (userscript / userstyle) as the extension registers it via `contentScripts.register`.
 * Matches the SiteRule JSON contract exactly (field names are the wire format).
 */
@Serializable
data class SiteRule(
    val id: String,
    val name: String,
    val matches: List<String>,
    val excludeMatches: List<String> = emptyList(),
    val js: String? = null,
    val css: String? = null,
    val world: String = "ISOLATED",
    val runAt: String = "document_idle",
    val enabled: Boolean = false,
    val source: String? = null,
) {
    companion object {
        fun newId(): String = "rule-" + UUID.randomUUID().toString().substring(0, 8)
    }
}

/**
 * `filesDir/rules.json`: the list of [SiteRule]s. Every change is persisted and pushed to the extension
 * through [onChanged] (BridgeExtension.sendRules). Three disabled example rules are created on first run.
 */
class SiteRulesRepository(context: Context) {
    private val file = File(context.filesDir, "rules.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; prettyPrintIndent = "  " }
    private val serializer = ListSerializer(SiteRule.serializer())
    private val _rules = MutableStateFlow(load())

    val rules: StateFlow<List<SiteRule>> get() = _rules

    /** Called after every persisted change with the full list (the extension re-registers everything). */
    var onChanged: ((List<SiteRule>) -> Unit)? = null

    fun upsert(rule: SiteRule) {
        val list = _rules.value.toMutableList()
        val idx = list.indexOfFirst { it.id == rule.id }
        if (idx >= 0) list[idx] = rule else list += rule
        save(list)
    }

    fun delete(id: String) = save(_rules.value.filterNot { it.id == id })

    fun setEnabled(id: String, enabled: Boolean) =
        save(_rules.value.map { if (it.id == id) it.copy(enabled = enabled) else it })

    fun replaceAll(rules: List<SiteRule>) = save(rules)

    /** The rules as the extension wire format (`{"type":"rules","rules":[...]}` payload). */
    fun toJsonArray(rules: List<SiteRule> = _rules.value): JSONArray =
        JSONArray(json.encodeToString(serializer, rules))

    private fun save(list: List<SiteRule>) {
        synchronized(this) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(serializer, list))
            if (!tmp.renameTo(file)) {
                file.writeText(json.encodeToString(serializer, list))
                tmp.delete()
            }
        }
        _rules.value = list
        onChanged?.invoke(list)
    }

    private fun load(): List<SiteRule> {
        if (!file.exists()) {
            val examples = bundledExamples()
            runCatching { file.writeText(json.encodeToString(serializer, examples)) }
            return examples
        }
        return runCatching { json.decodeFromString(serializer, file.readText()) }
            .onFailure { Log.w(TAG, "rules.json unreadable, starting empty", it) }
            .getOrDefault(emptyList())
    }

    private fun bundledExamples(): List<SiteRule> = listOf(
        SiteRule(
            id = "example-hide-cookie-banners",
            name = "Example: hide cookie banners (CSS)",
            matches = listOf("*://*/*"),
            css = """
                |#onetrust-consent-sdk, #onetrust-banner-sdk, .onetrust-pc-dark-filter,
                |#CybotCookiebotDialog, #CybotCookiebotDialogBodyUnderlay,
                |.cc-window, .cc-banner, .cookie-banner, .cookie-consent, #cookie-banner, #cookie-consent,
                |[id*="cookie-banner" i], [class*="cookie-banner" i], [aria-label*="cookie" i][role="dialog"],
                |.qc-cmp2-container, #qc-cmp2-container, .fc-consent-root, #sp_message_container_*, .sp-message-open,
                |div[class^="sp_message_container"] { display: none !important; }
                |html.sp-message-open, body.sp-message-open, body.qc-cmp2-container, body.onetrust-open {
                |  overflow: auto !important; position: static !important;
                |}
            """.trimMargin(),
            world = "ISOLATED",
            runAt = "document_start",
            enabled = false,
            source = "hatch-bundled",
        ),
        SiteRule(
            id = "example-widen-containers",
            name = "Example: widen page containers (CSS)",
            matches = listOf("*://*/*"),
            css = """
                |.container, .container-lg, .container-xl, .container-xxl, .wrapper, .page-wrapper, .content-wrapper,
                |main[class*="container"], div[class*="max-w-"], article[class*="max-w-"] {
                |  max-width: 96vw !important;
                |}
            """.trimMargin(),
            world = "ISOLATED",
            runAt = "document_end",
            enabled = false,
            source = "hatch-bundled",
        ),
        SiteRule(
            id = "example-log-page-info",
            name = "Example: log page info (JS, MAIN world)",
            matches = listOf("*://*/*"),
            js = """
                |// ==UserScript==
                |// @name         Hatch example: log page info
                |// @match        *://*/*
                |// @grant        GM_log
                |// ==/UserScript==
                |(function () {
                |  var info = {
                |    url: location.href,
                |    title: document.title,
                |    ua: navigator.userAgent,
                |    platform: navigator.platform,
                |    clientWidth: document.documentElement.clientWidth,
                |    maxTouchPoints: navigator.maxTouchPoints,
                |    pointerFine: matchMedia('(pointer: fine)').matches,
                |    hover: matchMedia('(hover: hover)').matches,
                |    languages: navigator.languages,
                |    tz: Intl.DateTimeFormat().resolvedOptions().timeZone
                |  };
                |  if (typeof GM_log === 'function') GM_log('[hatch] ' + JSON.stringify(info));
                |  else console.log('[hatch]', info);
                |})();
            """.trimMargin(),
            world = "MAIN",
            runAt = "document_idle",
            enabled = false,
            source = "hatch-bundled",
        ),
    )

    companion object {
        private const val TAG = "SiteRulesRepository"
    }
}
