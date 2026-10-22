package app.hatch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle

/**
 * Hatch's theme: one of the fixed [HatchPalette]s (no dynamic colour) in the resolved [ThemeMode], with the Geist
 * type scale and the design's shapes. Provides [MaterialTheme] (colour roles per the design's table, see
 * [hatchColorScheme]), [LocalHatchColors] (every design token) and [LocalIsDark].
 *
 * [ThemeMode.System] reads `isSystemInDarkTheme()`; MainActivity also sets `AppCompatDelegate`'s default night mode
 * from the same setting, so the configuration's uiMode (and with it AC's XML dialogs and the system bars) agrees.
 */
@Composable
fun HatchTheme(
    palette: HatchPalette = HatchPalette.DEFAULT,
    mode: ThemeMode = ThemeMode.DEFAULT,
    content: @Composable () -> Unit,
) {
    val dark = mode.isDark(isSystemInDarkTheme())
    val colors = remember(palette, dark) { HatchColors.of(palette, dark) }
    val scheme = remember(colors) { hatchColorScheme(colors) }
    CompositionLocalProvider(LocalHatchColors provides colors, LocalIsDark provides dark) {
        MaterialTheme(colorScheme = scheme, typography = HatchTypography, shapes = HatchMaterialShapes, content = content)
    }
}

/**
 * The crash-recovery theme: Graphite, following the system light/dark flag, reading no settings, no storage and no
 * Gecko state (CrashRecoveryActivity runs when the previous start crashed, possibly inside the settings code).
 */
@Composable
fun HatchStaticTheme(content: @Composable () -> Unit) {
    HatchTheme(palette = HatchPalette.Graphite, mode = ThemeMode.System, content = content)
}

/** Accessors in the style of `MaterialTheme.colorScheme`: `HatchTheme.colors.tb`, `HatchTheme.isDark`. */
object HatchTheme {
    val colors: HatchColors
        @Composable @ReadOnlyComposable
        get() = LocalHatchColors.current

    val isDark: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalIsDark.current

    val palette: HatchPalette
        @Composable @ReadOnlyComposable
        get() = LocalHatchColors.current.palette

    /** [HatchType.mono]: Geist Mono 12/16 500, for machine values only. */
    val mono: TextStyle
        get() = HatchType.mono
}
