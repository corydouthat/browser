package app.hatch.ui.theme

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the theme to docs/design/hatch-screens.dc.html: the `P` table (palettes), the `N` table (neutrals), the
 * `t` derivations and the colour-role table. The expected values are typed from the design file independently of
 * HatchPalette.kt.
 */
class HatchColorsTest {
    private fun hex(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)

    // name -> light (tb, ink, acc, onAcc), dark (tb, pill, ink, acc, onAcc)
    private val p = mapOf(
        HatchPalette.Graphite to (listOf("#E3E3E7", "#1F1F23", "#2B2B30", "#FFFFFF") to listOf("#313136", "#1A1A1E", "#ECECEF", "#D9D9DE", "#1A1A1E")),
        HatchPalette.Phosphor to (listOf("#D8EEE5", "#123A33", "#0F6E57", "#FFFFFF") to listOf("#1F3D3A", "#11211E", "#E2F1EA", "#5DF27A", "#0D1A12")),
        HatchPalette.Indigo to (listOf("#E5E5FB", "#25226B", "#4F46E5", "#FFFFFF") to listOf("#26245A", "#16152E", "#ECEBFA", "#A5A8FF", "#16152E")),
        HatchPalette.Cobalt to (listOf("#DCEAFE", "#0F2E5C", "#1A63D8", "#FFFFFF") to listOf("#14305A", "#0B1930", "#E6EEFB", "#8AB8FF", "#0B1930")),
        HatchPalette.Amber to (listOf("#FDE6D8", "#5A2510", "#C2410C", "#FFFFFF") to listOf("#45240F", "#231309", "#FBECE2", "#FFA25E", "#231309")),
        HatchPalette.Raspberry to (listOf("#FAE0EB", "#5C0F33", "#B0175A", "#FFFFFF") to listOf("#481330", "#230818", "#FBE6EF", "#FF8DBE", "#230818")),
    )

    @Test
    fun `six palettes in the design order, Graphite first and default`() {
        assertEquals(listOf("Graphite", "Phosphor", "Indigo", "Cobalt", "Amber", "Raspberry"), HatchPalette.entries.map { it.label })
        assertEquals(HatchPalette.Graphite, HatchPalette.DEFAULT)
        assertEquals(p.keys.toList(), HatchPalette.entries)
    }

    @Test
    fun `palette tokens match the P table`() {
        p.forEach { (palette, v) ->
            val (l, d) = v
            val lc = HatchColors.of(palette, dark = false)
            assertEquals(l, listOf(hex(lc.tb), hex(lc.ti), hex(lc.ac), hex(lc.oa)), "$palette light")
            assertEquals("#FFFFFF", hex(lc.pl), "$palette light pill is white")
            val dc = HatchColors.of(palette, dark = true)
            assertEquals(d, listOf(hex(dc.tb), hex(dc.pl), hex(dc.ti), hex(dc.ac), hex(dc.oa)), "$palette dark")
            assertEquals(dc.tb, dc.ac2)
            assertEquals(dc.ti, dc.oc2)
        }
    }

    @Test
    fun `neutrals match the N table in every palette`() {
        HatchPalette.entries.forEach { palette ->
            val l = HatchColors.of(palette, dark = false)
            assertEquals(
                listOf("#FFFFFF", "#F1F1F4", "#E4E4E9", "#1B1B1F", "#5F5F69", "#E6E6EA", "#C9C9D1", "#F7F7F9", "#B3261E", "#2F2F34", "#F2F2F5", "#1E7A3E"),
                listOf(l.bg, l.s1, l.s2, l.ink, l.mu, l.ln, l.ln2, l.sh, l.dg, l.inv, l.oinv, l.ok).map(::hex),
            )
            val d = HatchColors.of(palette, dark = true)
            assertEquals(
                listOf("#121214", "#1F1F23", "#2A2A2F", "#ECECEF", "#A2A2AD", "#2C2C32", "#45454D", "#1B1B1F", "#F2B8B5", "#E6E6EA", "#1B1B1F", "#7FD69A"),
                listOf(d.bg, d.s1, d.s2, d.ink, d.mu, d.ln, d.ln2, d.sh, d.dg, d.inv, d.oinv, d.ok).map(::hex),
            )
            assertEquals("#FBE3E1", hex(l.dgContainer))
            assertEquals("#4A1F1D", hex(d.dgContainer))
        }
    }

    @Test
    fun `text accent is near-black only for Graphite light`() {
        assertEquals("#1B1B1F", hex(HatchColors.of(HatchPalette.Graphite, false).acT))
        assertEquals("#D9D9DE", hex(HatchColors.of(HatchPalette.Graphite, true).acT))
        HatchPalette.entries.filter { it != HatchPalette.Graphite }.forEach {
            assertEquals(HatchColors.of(it, false).ac, HatchColors.of(it, false).acT)
            assertEquals(HatchColors.of(it, true).ac, HatchColors.of(it, true).acT)
        }
    }

    @Test
    fun `snackbar action uses the other mode's accent, Graphite uses the inverse ink`() {
        assertEquals("#FFFFFF", hex(HatchColors.of(HatchPalette.Graphite, false).invAc))
        assertEquals("#1B1B1F", hex(HatchColors.of(HatchPalette.Graphite, true).invAc))
        assertEquals("#5DF27A", hex(HatchColors.of(HatchPalette.Phosphor, false).invAc))
        assertEquals("#0F6E57", hex(HatchColors.of(HatchPalette.Phosphor, true).invAc))
        assertEquals("#FF8DBE", hex(HatchColors.of(HatchPalette.Raspberry, false).invAc))
        assertEquals("#B0175A", hex(HatchColors.of(HatchPalette.Raspberry, true).invAc))
    }

    @Test
    fun `persona chip separator is translucent white on light, translucent black on dark`() {
        val l = HatchColors.of(HatchPalette.Indigo, false).sep
        val d = HatchColors.of(HatchPalette.Indigo, true).sep
        assertEquals(Color.White.copy(alpha = 0.4f), l)
        assertEquals(Color.Black.copy(alpha = 0.3f), d)
    }

    @Test
    fun `colour scheme follows the design role table`() {
        listOf(false, true).forEach { dark ->
            HatchPalette.entries.forEach { palette ->
                val c = HatchColors.of(palette, dark)
                val s = hatchColorScheme(c)
                assertEquals(c.ac, s.primary)
                assertEquals(c.oa, s.onPrimary)
                assertEquals(c.tb, s.primaryContainer)
                assertEquals(c.ti, s.onPrimaryContainer)
                assertEquals(c.bg, s.surface)
                assertEquals(c.bg, s.background)
                assertEquals(c.s1, s.surfaceContainer)
                assertEquals(c.sh, s.surfaceContainerHigh)
                assertEquals(c.s2, s.surfaceContainerHighest)
                assertEquals(c.ink, s.onSurface)
                assertEquals(c.mu, s.onSurfaceVariant)
                assertEquals(c.ln2, s.outline)
                assertEquals(c.ln2, s.outlineVariant)
                assertEquals(c.dg, s.error)
                assertEquals(c.inv, s.inverseSurface)
                assertEquals(c.oinv, s.inverseOnSurface)
                assertEquals(c.invAc, s.inversePrimary)
                assertEquals(c.bg, s.surfaceTint, "no tonal tint")
            }
        }
    }

    @Test
    fun `no role falls back to the M3 baseline purples`() {
        // Baseline light/dark primary, secondary, tertiary and their containers (material3 tokens).
        val baseline = setOf(
            "#6750A4", "#D0BCFF", "#EADDFF", "#21005D", "#4F378B", "#625B71", "#CCC2DC", "#E8DEF8", "#4A4458",
            "#7D5260", "#EFB8C8", "#FFD8E4", "#633B48", "#FFFBFE", "#1C1B1F", "#E7E0EC", "#49454F", "#79747E",
        )
        listOf(false, true).forEach { dark ->
            HatchPalette.entries.forEach { palette ->
                val s = hatchColorScheme(HatchColors.of(palette, dark))
                val all = listOf(
                    s.primary, s.onPrimary, s.primaryContainer, s.onPrimaryContainer, s.inversePrimary, s.secondary,
                    s.onSecondary, s.secondaryContainer, s.onSecondaryContainer, s.tertiary, s.onTertiary,
                    s.tertiaryContainer, s.onTertiaryContainer, s.background, s.onBackground, s.surface, s.onSurface,
                    s.surfaceVariant, s.onSurfaceVariant, s.surfaceTint, s.inverseSurface, s.inverseOnSurface, s.error,
                    s.onError, s.errorContainer, s.onErrorContainer, s.outline, s.outlineVariant, s.surfaceBright,
                    s.surfaceDim, s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest,
                    s.surfaceContainerLow, s.surfaceContainerLowest, s.primaryFixed, s.primaryFixedDim, s.onPrimaryFixed,
                    s.onPrimaryFixedVariant, s.secondaryFixed, s.secondaryFixedDim, s.onSecondaryFixed,
                    s.onSecondaryFixedVariant, s.tertiaryFixed, s.tertiaryFixedDim, s.onTertiaryFixed,
                    s.onTertiaryFixedVariant,
                ).map(::hex)
                val leaked = all.filter { it in baseline }
                assertTrue(leaked.isEmpty(), "$palette dark=$dark leaks baseline colours $leaked")
            }
        }
    }

    @Test
    fun `stored keys round-trip and unknown keys fall back to the defaults`() {
        HatchPalette.entries.forEach { assertEquals(it, HatchPalette.fromKey(it.key)) }
        assertEquals(HatchPalette.Graphite, HatchPalette.fromKey(null))
        assertEquals(HatchPalette.Graphite, HatchPalette.fromKey("Sepia"))
        assertEquals(6, HatchPalette.entries.map { it.key }.distinct().size)
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.fromKey(it.key)) }
        assertEquals(ThemeMode.System, ThemeMode.fromKey(null))
        assertEquals(ThemeMode.System, ThemeMode.fromKey("auto"))
    }

    @Test
    fun `theme modes map to AppCompat night modes and resolve dark`() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, ThemeMode.System.nightMode)
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, ThemeMode.Light.nightMode)
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, ThemeMode.Dark.nightMode)
        assertTrue(ThemeMode.System.isDark(systemDark = true))
        assertFalse(ThemeMode.System.isDark(systemDark = false))
        assertFalse(ThemeMode.Light.isDark(systemDark = true))
        assertTrue(ThemeMode.Dark.isDark(systemDark = false))
    }

    @Test
    fun `XML theme colours equal Graphite`() {
        // values/colors_theme.xml and values-night/colors_theme.xml feed AC's XML dialogs; they must not drift.
        fun xml(path: String): Map<String, String> {
            val f = listOf(java.io.File(path), java.io.File("app/$path")).firstOrNull { it.isFile } ?: error("missing $path")
            return Regex("""<color name="(\w+)">(#[0-9A-F]{6})</color>""").findAll(f.readText()).associate { it.groupValues[1] to it.groupValues[2] }
        }
        listOf(false to "src/main/res/values/colors_theme.xml", true to "src/main/res/values-night/colors_theme.xml").forEach { (dark, path) ->
            val c = HatchColors.of(HatchPalette.Graphite, dark)
            val x = xml(path)
            assertEquals(hex(c.ac), x["hatch_primary"])
            assertEquals(hex(c.oa), x["hatch_on_primary"])
            assertEquals(hex(c.tb), x["hatch_primary_container"])
            assertEquals(hex(c.ti), x["hatch_on_primary_container"])
            assertEquals(hex(c.bg), x["hatch_surface"])
            assertEquals(hex(c.ink), x["hatch_on_surface"])
            assertEquals(hex(c.mu), x["hatch_on_surface_variant"])
            assertEquals(hex(c.s1), x["hatch_surface_container"])
            assertEquals(hex(c.sh), x["hatch_surface_container_high"])
            assertEquals(hex(c.s2), x["hatch_surface_container_highest"])
            assertEquals(hex(c.ln2), x["hatch_outline"])
            assertEquals(hex(c.dg), x["hatch_error"])
            assertEquals(hex(c.onDg), x["hatch_on_error"])
        }
    }
}
