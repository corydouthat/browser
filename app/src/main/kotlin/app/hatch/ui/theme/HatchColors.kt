package app.hatch.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Every token of the design's `t` object for one palette × mode. Property names match the template variables in
 * `docs/design/hatch-screens.dc.html` (`{{ t.ac }}` → [ac]) so a mockup can be read straight into Compose:
 *
 * | token | role | used for |
 * |---|---|---|
 * | [ac] | primary | persona chip, filled buttons, switches, progress, selection rings |
 * | [oa] | onPrimary | content on [ac] |
 * | [tb] (= [ac2]) | primaryContainer | toolbar + status bar in desktop mode, FAB, selection bar |
 * | [ti] (= [oc2]) | onPrimaryContainer | toolbar icons/text in desktop mode, content on [tb] |
 * | [pl] | — | the address pill in desktop mode (white in light mode) |
 * | [bg] | surface / background | screens, toolbar in phone mode |
 * | [s1] | surfaceContainer | pill in phone mode, grouped cards, chips |
 * | [s2] | surfaceContainerHighest | neutral pills ("Ask"), pressed fills |
 * | [sh] | surfaceContainerHigh | sheets, keyboard-adjacent bars |
 * | [ink] | onSurface | primary text |
 * | [mu] | onSurfaceVariant | secondary text, icons |
 * | [ln] | — | hairline dividers (toolbar bottom border, list separators) |
 * | [ln2] | outline / outlineVariant | chip outlines, stronger dividers (decorative boundaries) |
 * | [outline] | — (M3 outline stays ln2) | the unfocused boundary of form fields: ≥ 3:1 on bg / s1 / sh (WCAG 1.4.11; A11Y-07) |
 * | [dg] / [dgContainer] | error / errorContainer | destructive actions, validation; "Block" pill |
 * | [acT] | — | accent-coloured text (links, "Change"); near-black for Graphite light |
 * | [inv] / [oinv] / [invAc] | inverseSurface / inverseOnSurface / inversePrimary | snackbar and its action |
 * | [sep] | — | the 1 dp separator inside the filled persona chip |
 * | [ok] | — | status green ("Connection is secure", self-check pass) |
 */
@Immutable
data class HatchColors(
    val palette: HatchPalette,
    val isDark: Boolean,
    val bg: Color,
    val s1: Color,
    val s2: Color,
    val sh: Color,
    val ink: Color,
    val mu: Color,
    val ln: Color,
    val ln2: Color,
    val outline: Color,
    val dg: Color,
    val dgContainer: Color,
    val onDg: Color,
    val inv: Color,
    val oinv: Color,
    val invAc: Color,
    val tb: Color,
    val ti: Color,
    val pl: Color,
    val ac: Color,
    val oa: Color,
    val acT: Color,
    val sep: Color,
    val ok: Color,
) {
    /** Alias of [tb] (the design uses both names). */
    val ac2: Color get() = tb

    /** Alias of [ti] (the design uses both names). */
    val oc2: Color get() = ti

    companion object {
        /** Snackbar action text against the inverse surface (14 sp 600 is not large text, so 4.5:1). */
        const val SNACKBAR_ACTION_MIN_CONTRAST = 4.5

        /**
         * The design's `t` derivation: `pl`, `acT`, `invAc` and `sep` depend on the palette and mode. One deviation
         * (A11Y-06, recorded in docs/limitations.md): `invAc` is the design's value passed through
         * [Contrast.atLeast] against `inv`, which darkens Cobalt dark and Amber dark (4.42:1 / 4.16:1 in the design)
         * just enough to reach 4.5:1; every other palette × mode keeps the design's colour exactly.
         */
        fun of(palette: HatchPalette, dark: Boolean): HatchColors {
            val m = palette.tokens(dark)
            val n = Neutrals.of(dark)
            val graphite = palette == HatchPalette.Graphite
            return HatchColors(
                palette = palette,
                isDark = dark,
                bg = n.bg, s1 = n.s1, s2 = n.s2, sh = n.sh, ink = n.ink, mu = n.mu, ln = n.ln, ln2 = n.ln2,
                outline = if (dark) FieldOutlineDark else FieldOutlineLight,
                dg = n.dg, dgContainer = n.dgContainer, onDg = n.onDg, inv = n.inv, oinv = n.oinv,
                invAc = Contrast.atLeast(
                    when {
                        dark -> if (graphite) Color(0xFF1B1B1F) else palette.light.acc
                        else -> if (graphite) Color(0xFFFFFFFF) else palette.dark.acc
                    },
                    n.inv,
                    SNACKBAR_ACTION_MIN_CONTRAST,
                ),
                tb = m.tb,
                ti = m.ink,
                pl = if (dark) checkNotNull(m.pill) { "dark tokens of $palette need a pill" } else Color(0xFFFFFFFF),
                ac = m.acc,
                oa = m.onAcc,
                acT = if (dark || !graphite) m.acc else Color(0xFF1B1B1F),
                sep = if (dark) Color.Black.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.4f),
                ok = n.ok,
            )
        }
    }
}

/** Form-field outline (A11Y-07): the design's `ln2` is 1.5–2:1, too faint to mark an empty input. 3.2–3.7:1. */
internal val FieldOutlineLight = Color(0xFF85858F)
internal val FieldOutlineDark = Color(0xFF6E6E78)

/**
 * The Material 3 scheme for [c], per the design's colour-role table. Roles the table does not name are filled from
 * the same tokens so stock components never fall back to the baseline purple:
 * - secondary / secondaryContainer = [HatchColors.ac] / [HatchColors.oa]: M3 draws selected FilterChips, segmented
 *   buttons and navigation indicators with secondaryContainer, and the design fills every selected control with
 *   the accent.
 * - tertiary = accent, tertiaryContainer = toolbar tint; fixed roles = the toolbar tint and its ink.
 * - surfaceTint = surface, so tonal elevation adds no tint (the design is flat).
 */
fun hatchColorScheme(c: HatchColors): ColorScheme =
    // Every role is passed, so light vs dark only differs in the values (no baseline defaults are used).
    lightColorScheme(
        primary = c.ac,
        onPrimary = c.oa,
        primaryContainer = c.tb,
        onPrimaryContainer = c.ti,
        inversePrimary = c.invAc,
        secondary = c.ac,
        onSecondary = c.oa,
        secondaryContainer = c.ac,
        onSecondaryContainer = c.oa,
        tertiary = c.ac,
        onTertiary = c.oa,
        tertiaryContainer = c.tb,
        onTertiaryContainer = c.ti,
        background = c.bg,
        onBackground = c.ink,
        surface = c.bg,
        onSurface = c.ink,
        surfaceVariant = c.s1,
        onSurfaceVariant = c.mu,
        surfaceTint = c.bg,
        inverseSurface = c.inv,
        inverseOnSurface = c.oinv,
        error = c.dg,
        onError = c.onDg,
        errorContainer = c.dgContainer,
        onErrorContainer = c.dg,
        outline = c.ln2,
        outlineVariant = c.ln2,
        scrim = Color.Black,
        surfaceBright = if (c.isDark) c.s2 else c.bg,
        surfaceDim = if (c.isDark) c.bg else c.s2,
        surfaceContainer = c.s1,
        surfaceContainerHigh = c.sh,
        surfaceContainerHighest = c.s2,
        surfaceContainerLow = c.sh,
        surfaceContainerLowest = c.bg,
        primaryFixed = c.tb,
        primaryFixedDim = c.tb,
        onPrimaryFixed = c.ti,
        onPrimaryFixedVariant = c.ti,
        secondaryFixed = c.tb,
        secondaryFixedDim = c.tb,
        onSecondaryFixed = c.ti,
        onSecondaryFixedVariant = c.ti,
        tertiaryFixed = c.tb,
        tertiaryFixedDim = c.tb,
        onTertiaryFixed = c.ti,
        onTertiaryFixedVariant = c.ti,
    )

/** The current [HatchColors]; provided by [HatchTheme]. The default (Graphite light) only serves stray previews. */
val LocalHatchColors = staticCompositionLocalOf { HatchColors.of(HatchPalette.DEFAULT, dark = false) }

/** Whether the current Hatch theme is dark (the resolved [ThemeMode], not the system flag). */
val LocalIsDark = staticCompositionLocalOf { false }
