package app.hatch.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A11Y-06 / A11Y-07: WCAG contrast of the text and boundary pairs the screens draw, in all 12 palette × mode sets. */
class HatchColorsContrastTest {
    private fun each(block: (String, HatchColors) -> Unit) {
        HatchPalette.entries.forEach { p -> listOf(false, true).forEach { d -> block("$p ${if (d) "dark" else "light"}", HatchColors.of(p, d)) } }
    }

    private fun atLeast(min: Double, label: String, fg: Color, bgs: List<Color>) {
        bgs.forEach { bg ->
            val r = Contrast.ratio(fg, bg)
            assertTrue(r >= min, "$label: %.2f:1 < $min:1".format(r))
        }
    }

    /** Source-over composite of [fg] (with alpha) on an opaque [bg]. */
    private fun over(fg: Color, bg: Color) = Color(
        red = fg.red * fg.alpha + bg.red * (1 - fg.alpha),
        green = fg.green * fg.alpha + bg.green * (1 - fg.alpha),
        blue = fg.blue * fg.alpha + bg.blue * (1 - fg.alpha),
    )

    @Test
    fun `text pairs reach 4·5 to 1 everywhere`() = each { name, c ->
        atLeast(4.5, "$name invAc on inv", c.invAc, listOf(c.inv))
        atLeast(4.5, "$name oinv on inv", c.oinv, listOf(c.inv))
        atLeast(4.5, "$name oa on ac", c.oa, listOf(c.ac))
        atLeast(4.5, "$name ti on tb", c.ti, listOf(c.tb))
        atLeast(4.5, "$name ti on pl", c.ti, listOf(c.pl))
        atLeast(4.5, "$name mu", c.mu, listOf(c.bg, c.s1, c.sh, c.pl))
        atLeast(4.5, "$name dg", c.dg, listOf(c.bg, c.sh))
        atLeast(4.5, "$name acT", c.acT, listOf(c.bg, c.s1, c.sh))
        atLeast(4.5, "$name ink", c.ink, listOf(c.bg, c.s1, c.s2, c.sh))
    }

    @Test
    fun `form-field outline reaches 3 to 1 on every surface a field sits on`() = each { name, c ->
        atLeast(3.0, "$name outline", c.outline, listOf(c.bg, c.s1, c.sh))
    }

    @Test
    fun `the field placeholder is drawn in full-alpha mu`() = each { name, c ->
        // ScreenKit.FormField draws placeholders in c.mu (no alpha); a 0.7 alpha mu was 3.2:1 on white.
        atLeast(4.5, "$name placeholder", over(c.mu, c.bg), listOf(c.bg))
    }

    @Test
    fun `invAc keeps the design colour wherever it already passes and only darkens Cobalt and Amber dark`() {
        val changed = HatchPalette.entries.flatMap { p ->
            listOf(false, true).mapNotNull { d ->
                val design = when {
                    d -> if (p == HatchPalette.Graphite) Color(0xFF1B1B1F) else p.light.acc
                    else -> if (p == HatchPalette.Graphite) Color(0xFFFFFFFF) else p.dark.acc
                }
                if (HatchColors.of(p, d).invAc != design) "$p ${if (d) "dark" else "light"}" else null
            }
        }
        assertEquals(listOf("Cobalt dark", "Amber dark"), changed)
    }
}
