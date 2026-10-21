package app.hatch.ui.theme

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.graphics.Color

/**
 * The per-palette tokens of the "Signal" design (`docs/design/hatch-screens.dc.html`, the `P` table in its
 * `<script data-dc-script>`): `tb` (toolbar tint in desktop mode = primaryContainer), `ink` (content on it =
 * onPrimaryContainer), `acc` (primary) and `onAcc` (onPrimary). [pill] is only defined for dark mode; in light
 * mode the desktop-mode pill is white.
 */
data class PaletteTokens(
    val tb: Color,
    val ink: Color,
    val acc: Color,
    val onAcc: Color,
    val pill: Color? = null,
)

/**
 * The six fixed brand palettes (no dynamic colour). [key] is the stored value in [app.hatch.settings.AppSettings];
 * [label] is what the Appearance setting shows. Values copied verbatim from the design's `P` table.
 */
enum class HatchPalette(val key: String, val label: String, val light: PaletteTokens, val dark: PaletteTokens) {
    Graphite(
        "graphite", "Graphite",
        light = PaletteTokens(tb = Color(0xFFE3E3E7), ink = Color(0xFF1F1F23), acc = Color(0xFF2B2B30), onAcc = Color(0xFFFFFFFF)),
        dark = PaletteTokens(
            tb = Color(0xFF313136), pill = Color(0xFF1A1A1E), ink = Color(0xFFECECEF), acc = Color(0xFFD9D9DE), onAcc = Color(0xFF1A1A1E),
        ),
    ),
    Phosphor(
        "phosphor", "Phosphor",
        light = PaletteTokens(tb = Color(0xFFD8EEE5), ink = Color(0xFF123A33), acc = Color(0xFF0F6E57), onAcc = Color(0xFFFFFFFF)),
        dark = PaletteTokens(
            tb = Color(0xFF1F3D3A), pill = Color(0xFF11211E), ink = Color(0xFFE2F1EA), acc = Color(0xFF5DF27A), onAcc = Color(0xFF0D1A12),
        ),
    ),
    Indigo(
        "indigo", "Indigo",
        light = PaletteTokens(tb = Color(0xFFE5E5FB), ink = Color(0xFF25226B), acc = Color(0xFF4F46E5), onAcc = Color(0xFFFFFFFF)),
        dark = PaletteTokens(
            tb = Color(0xFF26245A), pill = Color(0xFF16152E), ink = Color(0xFFECEBFA), acc = Color(0xFFA5A8FF), onAcc = Color(0xFF16152E),
        ),
    ),
    Cobalt(
        "cobalt", "Cobalt",
        light = PaletteTokens(tb = Color(0xFFDCEAFE), ink = Color(0xFF0F2E5C), acc = Color(0xFF1A63D8), onAcc = Color(0xFFFFFFFF)),
        dark = PaletteTokens(
            tb = Color(0xFF14305A), pill = Color(0xFF0B1930), ink = Color(0xFFE6EEFB), acc = Color(0xFF8AB8FF), onAcc = Color(0xFF0B1930),
        ),
    ),
    Amber(
        "amber", "Amber",
        light = PaletteTokens(tb = Color(0xFFFDE6D8), ink = Color(0xFF5A2510), acc = Color(0xFFC2410C), onAcc = Color(0xFFFFFFFF)),
        dark = PaletteTokens(
            tb = Color(0xFF45240F), pill = Color(0xFF231309), ink = Color(0xFFFBECE2), acc = Color(0xFFFFA25E), onAcc = Color(0xFF231309),
        ),
    ),
    Raspberry(
        "raspberry", "Raspberry",
        light = PaletteTokens(tb = Color(0xFFFAE0EB), ink = Color(0xFF5C0F33), acc = Color(0xFFB0175A), onAcc = Color(0xFFFFFFFF)),
        dark = PaletteTokens(
            tb = Color(0xFF481330), pill = Color(0xFF230818), ink = Color(0xFFFBE6EF), acc = Color(0xFFFF8DBE), onAcc = Color(0xFF230818),
        ),
    ),
    ;

    fun tokens(dark: Boolean): PaletteTokens = if (dark) this.dark else light

    companion object {
        val DEFAULT = Graphite

        /** Stored key → palette; `null` or an unknown key (older/newer build) falls back to [DEFAULT]. */
        fun fromKey(key: String?): HatchPalette = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** App theme mode (Settings › Appearance): follow the system, or force light / dark. */
enum class ThemeMode(val key: String, val label: String) {
    System("system", "System"),
    Light("light", "Light"),
    Dark("dark", "Dark"),
    ;

    /** The matching `AppCompatDelegate` night mode, so Android Components' XML dialogs follow the app theme. */
    val nightMode: Int
        get() = when (this) {
            System -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            Light -> AppCompatDelegate.MODE_NIGHT_NO
            Dark -> AppCompatDelegate.MODE_NIGHT_YES
        }

    /** Whether this mode renders dark, given the system's current dark flag. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        System -> systemDark
        Light -> false
        Dark -> true
    }

    companion object {
        val DEFAULT = System

        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * The shared neutral tokens (the design's `N` table), identical for every palette. `dgContainer` is the
 * design's "Block" permission-pill background (`#FBE3E1` / `#4A1F1D`), used as M3 errorContainer.
 */
data class Neutrals(
    val bg: Color,
    val s1: Color,
    val s2: Color,
    val ink: Color,
    val mu: Color,
    val ln: Color,
    val ln2: Color,
    val sh: Color,
    val dg: Color,
    val inv: Color,
    val oinv: Color,
    val ok: Color,
    val dgContainer: Color,
    val onDg: Color,
) {
    companion object {
        val Light = Neutrals(
            bg = Color(0xFFFFFFFF), s1 = Color(0xFFF1F1F4), s2 = Color(0xFFE4E4E9), ink = Color(0xFF1B1B1F),
            mu = Color(0xFF5F5F69), ln = Color(0xFFE6E6EA), ln2 = Color(0xFFC9C9D1), sh = Color(0xFFF7F7F9),
            dg = Color(0xFFB3261E), inv = Color(0xFF2F2F34), oinv = Color(0xFFF2F2F5), ok = Color(0xFF1E7A3E),
            dgContainer = Color(0xFFFBE3E1), onDg = Color(0xFFFFFFFF),
        )
        val Dark = Neutrals(
            bg = Color(0xFF121214), s1 = Color(0xFF1F1F23), s2 = Color(0xFF2A2A2F), ink = Color(0xFFECECEF),
            mu = Color(0xFFA2A2AD), ln = Color(0xFF2C2C32), ln2 = Color(0xFF45454D), sh = Color(0xFF1B1B1F),
            dg = Color(0xFFF2B8B5), inv = Color(0xFFE6E6EA), oinv = Color(0xFF1B1B1F), ok = Color(0xFF7FD69A),
            dgContainer = Color(0xFF4A1F1D), onDg = Color(0xFF601410),
        )

        fun of(dark: Boolean): Neutrals = if (dark) Dark else Light
    }
}
