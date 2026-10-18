package app.hatch.persona

/**
 * Reproduces how Gecko 158 turns the `intl.accept_languages` pref into the `Accept-Language` header.
 *
 * Source of truth: `netwerk/base/rust-helper/src/lib.rs` `rust_prepare_accept_languages` (called from
 * `nsHttpHandler::PrepareAcceptLanguages`, tag FIREFOX_158_0_RELEASE):
 *  - split on `,`; trim HTTP LWS (space/tab) and anything from the first `;` (old q-values); drop empties;
 *  - canonicalize each tag: lowercase everything, then uppercase 2-letter subtags, title-case 4-letter
 *    subtags, stop at a singleton subtag (`x`, `i`), first subtag untouched;
 *  - q-values decrease by 0.1 per position (Chrome emulation), floor 0.1; the first entry has no q.
 *
 * Examples: `"en, ja"` -> `"en,ja;q=0.9"`, `"en-us, en"` -> `"en-US,en;q=0.9"`.
 */
object GeckoAcceptLanguage {
    private const val Q_MAX = 10

    /** The header value Gecko emits for a pref value. Empty pref -> empty header (Gecko then sends none). */
    fun header(prefValue: String): String {
        val tokens = tokens(prefValue)
        val out = StringBuilder()
        tokens.forEachIndexed { index, token ->
            if (index != 0) out.append(',')
            out.append(canonicalize(token))
            val step = minOf(10, index)
            val q = maxOf(Q_MAX - step, 1)
            if (index > 0 && q < Q_MAX) out.append(";q=0.").append(q)
        }
        return out.toString()
    }

    /** What `navigator.languages` reports for a pref value (same split/trim/canonicalize, no q-values). */
    fun navigatorLanguages(prefValue: String): List<String> = tokens(prefValue).map(::canonicalize)

    /** The pref value the app writes for a region's language list (Gecko's own format is `"en-US, en"`). */
    fun prefValue(languages: List<String>): String = languages.joinToString(", ")

    private fun tokens(value: String): List<String> =
        value.split(',').map(::trimToken).filter { it.isNotEmpty() }

    private fun trimToken(token: String): String {
        var start = 0
        while (start < token.length && (token[start] == ' ' || token[start] == '\t')) start++
        var end = start
        while (end < token.length && token[end] != ';' && token[end] != ' ' && token[end] != '\t') end++
        return token.substring(start, end)
    }

    private fun canonicalize(token: String): String {
        // Byte-wise ASCII case mapping, like Rust's to_ascii_lowercase/uppercase (non-ASCII untouched).
        val parts = asciiLower(token).split('-').toMutableList()
        for (i in 1 until parts.size) {
            val sub = parts[i]
            when (sub.length) {
                1 -> break
                2 -> parts[i] = asciiUpper(sub)
                4 -> parts[i] = asciiUpper(sub.substring(0, 1)) + sub.substring(1)
                else -> {}
            }
        }
        return parts.joinToString("-")
    }

    private fun asciiLower(s: String): String =
        buildString(s.length) { for (c in s) append(if (c in 'A'..'Z') c + ('a' - 'A') else c) }

    private fun asciiUpper(s: String): String =
        buildString(s.length) { for (c in s) append(if (c in 'a'..'z') c - ('a' - 'A') else c) }
}
