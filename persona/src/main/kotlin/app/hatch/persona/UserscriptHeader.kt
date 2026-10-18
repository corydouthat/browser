package app.hatch.persona

import kotlinx.serialization.Serializable

/** Metadata parsed from a Tampermonkey/Violentmonkey `==UserScript==` block. */
@Serializable
data class UserscriptMeta(
    val name: String?,
    val version: String?,
    val matches: List<String>,
    val includes: List<String>,
    val excludes: List<String>,
    val runAt: String?,
    val grants: List<String>,
    val requires: List<String>,
    val description: String?,
)

/**
 * Parses the `// ==UserScript== ... // ==/UserScript==` header of a `.user.js` file. Tolerates CRLF line
 * endings, leading whitespace before `//`, tabs/multiple spaces after the key, and localized keys
 * (`@name:en` is used only when no plain `@name` exists). Returns null when there is no complete block.
 */
object UserscriptHeader {
    private val keyLine = Regex("^\\s*//\\s*@([A-Za-z0-9_:\\-]+)(?:[ \\t]+(.*?))?\\s*$")
    private val blockStart = Regex("^\\s*//\\s*==UserScript==\\s*$")
    private val blockEnd = Regex("^\\s*//\\s*==/UserScript==\\s*$")

    fun parse(source: String): UserscriptMeta? {
        val lines = source.split('\n').map { it.trimEnd('\r') }
        val start = lines.indexOfFirst { blockStart.matches(it) }
        if (start < 0) return null
        val end = lines.drop(start + 1).indexOfFirst { blockEnd.matches(it) }
        if (end < 0) return null
        val body = lines.subList(start + 1, start + 1 + end)

        val values = LinkedHashMap<String, MutableList<String>>()
        for (line in body) {
            val m = keyLine.find(line) ?: continue
            val key = m.groupValues[1]
            val value = m.groupValues[2].trim()
            values.getOrPut(key) { ArrayList() }.add(value)
        }

        fun single(key: String): String? =
            values[key]?.firstOrNull { it.isNotEmpty() }
                ?: values.entries.firstOrNull { it.key.startsWith("$key:") }?.value?.firstOrNull { it.isNotEmpty() }

        fun multi(key: String): List<String> = values[key]?.filter { it.isNotEmpty() } ?: emptyList()

        return UserscriptMeta(
            name = single("name"),
            version = single("version"),
            matches = multi("match"),
            includes = multi("include"),
            excludes = multi("exclude") + multi("exclude-match"),
            runAt = single("run-at"),
            grants = multi("grant"),
            requires = multi("require"),
            description = single("description"),
        )
    }
}
