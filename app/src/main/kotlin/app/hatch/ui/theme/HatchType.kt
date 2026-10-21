package app.hatch.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.hatch.R

/** Geist (vercel/geist-font v1.8.1, OFL-1.1; static TTFs in res/font, see THIRD_PARTY.md). */
val GeistFamily = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)

/** Geist Mono, same release. Only for machine values: UAs, patterns, time zones, codes, counters. */
val GeistMonoFamily = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
    Font(R.font.geist_mono_semibold, FontWeight.SemiBold),
)

private fun geist(size: Int, line: Int, weight: FontWeight, tracking: TextUnit = 0.em, family: FontFamily = GeistFamily) =
    TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = tracking,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )

/**
 * The design's type scale (sp). Given by the spec: titleLarge 22/28 500, titleMedium 17/24 600, bodyLarge 15/22 400,
 * bodyMedium 13/18 400, labelLarge 14/20 600. The rest is derived from what the mockups use: labelMedium 13/18 600
 * (permission pills, chips), labelSmall 12/16 500, bodySmall 12/16 (row secondary lines), titleSmall 15/22 600
 * (card titles), headlineSmall 24/32 600 −0.02em (the "Hatch" wordmark), larger headings 600 with −0.01em.
 * Letter spacing is 0 elsewhere: Geist carries its own spacing and M3's baseline tracking is tuned for Roboto.
 */
val HatchTypography = Typography(
    displayLarge = geist(57, 64, FontWeight.SemiBold, (-0.02).em),
    displayMedium = geist(45, 52, FontWeight.SemiBold, (-0.02).em),
    displaySmall = geist(36, 44, FontWeight.SemiBold, (-0.02).em),
    headlineLarge = geist(32, 40, FontWeight.SemiBold, (-0.01).em),
    headlineMedium = geist(28, 36, FontWeight.SemiBold, (-0.01).em),
    headlineSmall = geist(24, 32, FontWeight.SemiBold, (-0.02).em),
    titleLarge = geist(22, 28, FontWeight.Medium),
    titleMedium = geist(17, 24, FontWeight.SemiBold),
    titleSmall = geist(15, 22, FontWeight.SemiBold),
    bodyLarge = geist(15, 22, FontWeight.Normal),
    bodyMedium = geist(13, 18, FontWeight.Normal),
    bodySmall = geist(12, 16, FontWeight.Normal),
    labelLarge = geist(14, 20, FontWeight.SemiBold),
    labelMedium = geist(13, 18, FontWeight.SemiBold),
    labelSmall = geist(12, 16, FontWeight.Medium),
)

/**
 * Hatch-only text styles outside the M3 scale. [mono] is the spec's "mono 12/16 · 500"; the larger mono sizes are
 * the ones the mockups use for form values (persona editor: 14/20) and info rows (13/18).
 */
object HatchType {
    val mono: TextStyle = geist(12, 16, FontWeight.Medium, family = GeistMonoFamily)
    val monoMedium: TextStyle = geist(13, 18, FontWeight.Medium, family = GeistMonoFamily)
    val monoLarge: TextStyle = geist(14, 20, FontWeight.Medium, family = GeistMonoFamily)
}
