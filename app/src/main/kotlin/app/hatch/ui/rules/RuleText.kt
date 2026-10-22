package app.hatch.ui.rules

import app.hatch.rules.RuleValidation
import app.hatch.rules.SiteRule

/** The site-rules screens' display strings (pure, JVM-tested); the design's rows read "JS", "CSS", "CSS · Example". */
object RuleText {
    /** What the rule injects, plus "· Example" for the bundled examples. */
    fun kindLine(rule: SiteRule): String {
        val kinds = listOfNotNull(rule.js?.takeIf { it.isNotBlank() }?.let { "JS" }, rule.css?.takeIf { it.isNotBlank() }?.let { "CSS" })
        val base = kinds.joinToString(" + ").ifEmpty { "Empty" }
        return if (RuleValidation.isBundled(rule)) "$base · Example" else base
    }

    /** The match patterns on one line (the row ellipsizes it). */
    fun patternLine(rule: SiteRule): String = rule.matches.joinToString(", ").ifEmpty { "No match pattern" }

    /** `document_start` → `document-start` (the userscript `@run-at` spelling the design shows). */
    fun runAtLabel(runAt: String): String = runAt.replace('_', '-')

    /** MAIN is the page's own realm ("Page"); ISOLATED is the content-script sandbox ("Isolated"). */
    fun worldLabel(world: String): String = if (world == "MAIN") "Page" else "Isolated"

    /** Settings › Site rules summary: "2 of 4 on". */
    fun enabledSummary(rules: List<SiteRule>): String = "${rules.count { it.enabled }} of ${rules.size} on"

    /** [patterns] with [index] replaced by [value] (blank removes it); index == size appends. Trimmed, no blanks. */
    fun editPattern(patterns: List<String>, index: Int, value: String): List<String> {
        val v = value.trim()
        val out = patterns.toMutableList()
        when {
            index >= out.size -> if (v.isNotEmpty()) out += v
            v.isEmpty() -> out.removeAt(index)
            else -> out[index] = v
        }
        return out
    }
}
