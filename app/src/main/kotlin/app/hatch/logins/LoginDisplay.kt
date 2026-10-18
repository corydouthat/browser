package app.hatch.logins

import mozilla.components.concept.storage.Login

/** Pure helpers for the Saved logins screen (sorted list, search filter, host label). JVM-tested. */
object LoginDisplay {
    /** `https://accounts.google.com:8443` → `accounts.google.com:8443`; non-URL origins are shown as-is. */
    fun hostLabel(origin: String): String {
        val i = origin.indexOf("://")
        val rest = if (i >= 0) origin.substring(i + 3) else origin
        return rest.takeWhile { it != '/' && it != '?' && it != '#' }.ifEmpty { origin }
    }

    /** Stable order for the list: host, then username (case-insensitive), then guid. */
    fun sorted(logins: List<Login>): List<Login> =
        logins.sortedWith(
            compareBy<Login, String>(String.CASE_INSENSITIVE_ORDER) { hostLabel(it.origin) }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.username }
                .thenBy { it.guid },
        )

    /** Case-insensitive substring match on origin or username; blank query keeps everything. */
    fun filter(logins: List<Login>, query: String): List<Login> {
        val q = query.trim()
        if (q.isEmpty()) return logins
        return logins.filter { it.origin.contains(q, ignoreCase = true) || it.username.contains(q, ignoreCase = true) }
    }

    /** Origin accepted by the "Add login" dialog: scheme + host, normalised like the exception list. */
    fun originForNewLogin(input: String): String? {
        val normalized = LoginExceptionCodec.normalizeOrigin(input)
        if (normalized.isEmpty()) return null
        val host = hostLabel(normalized).substringBefore(':')
        if (!host.contains('.') && host != "localhost") return null
        return normalized
    }
}
