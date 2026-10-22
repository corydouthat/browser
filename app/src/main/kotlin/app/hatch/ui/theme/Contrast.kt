package app.hatch.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * WCAG 2.x contrast arithmetic on sRGB colours (JVM-pure, so the palette tests can loop over every palette × mode).
 * [ratio] is the WCAG contrast ratio; [atLeast] nudges a colour towards black (on a light background) or white (on a
 * dark one) in 2 % steps until it reaches the ratio, leaving colours that already pass untouched.
 */
object Contrast {
    /** WCAG relative luminance of [c] (alpha ignored). */
    fun luminance(c: Color): Double {
        fun lin(v: Float): Double = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    /** WCAG contrast ratio of [a] against [b], 1.0–21.0. */
    fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** [c] itself when it already reaches [min] against [background], else the nearest darker / lighter mix that does. */
    fun atLeast(c: Color, background: Color, min: Double): Color {
        if (ratio(c, background) >= min) return c
        val target = if (luminance(background) > 0.18) Color.Black else Color.White
        var t = 0f
        var out = c
        while (ratio(out, background) < min && t < 1f) {
            t = (t + 0.02f).coerceAtMost(1f)
            out = Color(
                red = c.red + (target.red - c.red) * t,
                green = c.green + (target.green - c.green) * t,
                blue = c.blue + (target.blue - c.blue) * t,
                alpha = c.alpha,
            )
        }
        return out
    }
}
