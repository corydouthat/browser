package app.hatch.engine

/**
 * RS-03: the one host parser for what Hatch *shows* (the display toolbar's host, open-tab autocomplete, the error
 * page's host). It follows the WHATWG URL parser for special schemes closely enough that a spoofing URL shows its
 * real host: the authority ends at the first `/`, `\`, `?` or `#`; userinfo is everything up to the **last** `@`
 * (`https://accounts.google.com:x@evil.com/` → `evil.com`); a bracketed IPv6 literal keeps its brackets; the port
 * is dropped; the result is lower-case.
 */
object UrlHost {
    fun of(url: String?): String? {
        if (url == null) return null
        val scheme = url.indexOf("://")
        if (scheme <= 0) return null
        val rest = url.substring(scheme + 3)
        val end = rest.indexOfAny(charArrayOf('/', '\\', '?', '#')).let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, end).substringAfterLast('@')
        val host = if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            authority.substring(0, close + 1)
        } else {
            authority.substringBefore(':')
        }
        return host.lowercase().takeIf { it.isNotEmpty() }
    }

    /** Whether [url]'s authority carries userinfo (`user[:pass]@`), with the same authority rules as [of]. */
    fun hasUserInfo(url: String): Boolean {
        val scheme = url.indexOf("://")
        if (scheme <= 0) return false
        val rest = url.substring(scheme + 3)
        val end = rest.indexOfAny(charArrayOf('/', '\\', '?', '#')).let { if (it < 0) rest.length else it }
        return '@' in rest.substring(0, end)
    }
}
