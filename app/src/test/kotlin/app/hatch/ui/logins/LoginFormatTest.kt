package app.hatch.ui.logins

import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoginFormatTest {
    private val utc = ZoneOffset.UTC
    private fun at(y: Int, m: Int, d: Int): Long = LocalDate.of(y, m, d).atStartOfDay(utc).toInstant().toEpochMilli() + 3_600_000

    @Test
    fun `site title, avatar letter and a stable colour per host`() {
        assertEquals("github.com", LoginFormat.siteTitle("https://www.github.com"))
        assertEquals("secure.chase.com", LoginFormat.siteTitle("https://secure.chase.com"))
        assertEquals("G", LoginFormat.avatarLetter("https://www.github.com"))
        assertEquals("1", LoginFormat.avatarLetter("https://1password.com"))
        assertEquals("?", LoginFormat.avatarLetter(""))
        val i = LoginFormat.avatarColorIndex("https://github.com")
        assertEquals(i, LoginFormat.avatarColorIndex("https://www.GitHub.com/login"), "same host, same colour")
        assertTrue(i in LoginFormat.AVATAR_COLORS.indices)
        val used = listOf("chase.com", "aws.amazon.com", "booking.com", "github.com", "united.com", "maps.google.com", "meet.google.com")
            .map { LoginFormat.avatarColorIndex("https://$it") }.toSet()
        assertTrue(used.size >= 4, "hosts spread over the colour list: $used")
    }

    @Test
    fun `avatar colours keep white letters readable`() {
        fun channel(v: Long): Double { val c = v / 255.0; return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4) }
        LoginFormat.AVATAR_COLORS.forEach { argb ->
            val l = 0.2126 * channel((argb shr 16) and 0xFF) + 0.7152 * channel((argb shr 8) and 0xFF) + 0.0722 * channel(argb and 0xFF)
            val contrast = 1.05 / (l + 0.05)
            assertTrue(contrast >= 4.5, "white on %08X is %.2f:1".format(argb, contrast))
        }
    }

    @Test
    fun `dates line`() {
        val now = at(2026, 10, 21)
        assertEquals("Last used 3 days ago · saved Aug 2026", LoginFormat.datesLine(at(2026, 10, 18), at(2026, 8, 2), now, utc, Locale.US))
        assertEquals("Last used today", LoginFormat.datesLine(now - 60_000, 0, now, utc, Locale.US))
        assertEquals("Saved Jan 2025", LoginFormat.datesLine(0, at(2025, 1, 9), now, utc, Locale.US))
        assertNull(LoginFormat.datesLine(0, 0, now, utc, Locale.US))
        assertEquals("yesterday", LoginFormat.relativeDay(at(2026, 10, 20), now, utc, Locale.US))
        assertEquals("1 week ago", LoginFormat.relativeDay(at(2026, 10, 12), now, utc, Locale.US))
        assertEquals("5 weeks ago", LoginFormat.relativeDay(at(2026, 9, 14), now, utc, Locale.US))
        assertEquals("in Mar 2026", LoginFormat.relativeDay(at(2026, 3, 1), now, utc, Locale.US))
    }
}
