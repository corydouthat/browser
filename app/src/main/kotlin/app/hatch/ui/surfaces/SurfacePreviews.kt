package app.hatch.ui.surfaces

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import app.hatch.ui.theme.HatchPalette
import app.hatch.ui.theme.HatchTheme
import app.hatch.ui.theme.ThemeMode

/** One preview variant: a palette in light or dark mode. */
data class PreviewTheme(val palette: HatchPalette, val dark: Boolean) {
    override fun toString(): String = "${palette.label} · ${if (dark) "dark" else "light"}"
}

/**
 * The variants every redesigned surface is previewed in: Graphite (the default) and Phosphor, each light and dark.
 * Use with `@PreviewParameter(SurfacePreviewThemes::class)`.
 */
class SurfacePreviewThemes : PreviewParameterProvider<PreviewTheme> {
    override val values: Sequence<PreviewTheme> = sequenceOf(
        PreviewTheme(HatchPalette.Graphite, dark = false),
        PreviewTheme(HatchPalette.Graphite, dark = true),
        PreviewTheme(HatchPalette.Phosphor, dark = false),
        PreviewTheme(HatchPalette.Phosphor, dark = true),
    )
}

/** [HatchTheme] in [theme] with the surface colour behind [content] (previews have no window background). */
@Composable
fun SurfacePreviewFrame(theme: PreviewTheme, content: @Composable () -> Unit) {
    HatchTheme(palette = theme.palette, mode = if (theme.dark) ThemeMode.Dark else ThemeMode.Light) {
        Box(Modifier.background(HatchTheme.colors.bg)) { content() }
    }
}
