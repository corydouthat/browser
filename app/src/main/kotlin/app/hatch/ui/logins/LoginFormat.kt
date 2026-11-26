package app.hatch.ui.logins

import app.hatch.logins.LoginDisplay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Display helpers for Saved logins and Site permissions (pure, JVM-tested): the site title, the letter avatar and
 * its colour (derived deterministically from the host, so a site keeps its colour across launches), and the
 * "Last used 3 days ago · saved Aug 2026" line of the login detail.
 */
object LoginFormat {
    /**
     * Avatar / site tile colours (ARGB). All are dark enough for white 15–20 sp bold letters (WCAG AA ≥ 4.5:1 on
     * white text), and none equals a palette accent, so a tile never reads as a selected control.
     */
    val AVATAR_COLORS: List<Long> = listOf(
        0xFF1A4C8C, 0xFF232A33, 0xFF003B95, 0xFF24292F, 0xFF1C2E6E, 0xFF00695C,
        0xFF2E6B30, 0xFF8A3A0E, 0xFF7A1F4A, 0xFF4A3A8C, 0xFF5D4037, 0xFF37474F,
    )

    /** The host without a leading `www.` ("https://www.github.com" → "github.com"). */
    fun siteTitle(origin: String): String = LoginDisplay.hostLabel(origin).removePrefix("www.")

    /**
     * The host to ask the public-suffix list about: [siteTitle] without a port, lower-case; null for IP literals and
     * single-label hosts (the list's `*` prevailing rule would turn "192.168.1.1" into "1.1").
     */
    fun lookupHost(origin: String): String? {
        val host = siteTitle(origin).lowercase(Locale.ROOT)
        if (host.startsWith("[")) return null
        val bare = host.substringBefore(':')
        if (!bare.contains('.') || bare.all { it.isDigit() || it == '.' }) return null
        return bare
    }

    /**
     * DF-11: the title of a login, the registrable domain ("https://secure.chase.com" → "chase.com"), given the
     * public-suffix list's answer [registrable] for [lookupHost]. Falls back to [siteTitle] while the lookup is
     * pending, when it failed, or when its answer is not the host or a parent of it.
     */
    fun title(origin: String, registrable: String?): String {
        val host = lookupHost(origin) ?: return siteTitle(origin)
        val r = registrable?.trim()?.lowercase(Locale.ROOT)?.removePrefix("www.")
        return if (!r.isNullOrEmpty() && (host == r || host.endsWith(".$r"))) r else siteTitle(origin)
    }

    /** The avatar letter: the first letter or digit of [title], upper-case; "?" when there is none. */
    fun avatarLetter(origin: String, registrable: String? = null): String =
        title(origin, registrable).firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

    /** A stable index into [AVATAR_COLORS] for [origin]'s host (String.hashCode is specified, so it never changes). */
    fun avatarColorIndex(origin: String): Int = Math.floorMod(siteTitle(origin).lowercase(Locale.ROOT).hashCode(), AVATAR_COLORS.size)

    fun avatarColor(origin: String): Long = AVATAR_COLORS[avatarColorIndex(origin)]

    /**
     * "Last used 3 days ago · saved Aug 2026"; either half is left out when its timestamp is 0 (not recorded), and the
     * result is null when neither is known. Times are epoch milliseconds (`Login.timeLastUsed` / `timeCreated`).
     */
    fun datesLine(timeLastUsed: Long, timeCreated: Long, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? {
        val used = if (timeLastUsed > 0) "Last used ${relativeDay(timeLastUsed, now, zone)}" else null
        val saved = if (timeCreated > 0) monthYear(timeCreated, zone, locale) else null
        return when {
            used != null && saved != null -> "$used · saved $saved"
            used != null -> used
            saved != null -> "Saved $saved"
            else -> null
        }
    }

    /** "today", "yesterday", "N days ago" (under a week), "N weeks ago" (under ~2 months), else "in Aug 2026". */
    fun relativeDay(time: Long, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val day = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(day, today)
        return when {
            days <= 0 -> "today"
            days == 1L -> "yesterday"
            days < 7 -> "$days days ago"
            days < 60 -> "${days / 7} week${if (days / 7 == 1L) "" else "s"} ago"
            else -> "in ${monthYear(day, locale)}"
        }
    }

    private fun monthYear(time: Long, zone: ZoneId, locale: Locale): String = monthYear(Instant.ofEpochMilli(time).atZone(zone).toLocalDate(), locale)

    private fun monthYear(day: LocalDate, locale: Locale): String = day.format(DateTimeFormatter.ofPattern("MMM yyyy", locale))
}
