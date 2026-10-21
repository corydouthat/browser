package app.hatch.ui.toolbar

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The toolbar's glyphs, drawn from the exact SVG paths of `docs/design/hatch-screens.dc.html` (24-unit viewBox,
 * stroked, no fill). `rect`/`circle` elements are written as the equivalent paths. They are tinted by `Icon(tint=)`,
 * so the stroke colour below is only a mask. Stroke widths follow the mockup: 2.2 for the 14 dp persona-chip
 * glyphs, 2 for the 16–22 dp ones, 3 for the overflow dots.
 */
internal object ToolbarIcons {
    private const val LOCK = "M7 11H17A2 2 0 0 1 19 13V18A2 2 0 0 1 17 20H7A2 2 0 0 1 5 18V13A2 2 0 0 1 7 11Z M8 11V8a4 4 0 0 1 8 0v3"
    private const val GLOBE = "M3 12A9 9 0 1 0 21 12A9 9 0 1 0 3 12Z M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"
    private const val WARNING = "M12 3l10 18H2z M12 10v5M12 18h.01"
    private const val MONITOR = "M5 4H19A2 2 0 0 1 21 6V14A2 2 0 0 1 19 16H5A2 2 0 0 1 3 14V6A2 2 0 0 1 5 4Z M9 20h6M12 16v4"
    private const val PHONE = "M9 3H15A2 2 0 0 1 17 5V19A2 2 0 0 1 15 21H9A2 2 0 0 1 7 19V5A2 2 0 0 1 9 3Z"
    private const val SEARCH = "M4 11A7 7 0 1 0 18 11A7 7 0 1 0 4 11Z M20 20l-4-4"
    private const val CHEVRON_UP = "M6 15l6-6 6 6"
    private const val CHEVRON_DOWN = "M6 9l6 6 6-6"
    private const val CLOSE = "M6 6l12 12M18 6L6 18"
    private const val OVERFLOW = "M12 5h.01M12 12h.01M12 19h.01"

    val Lock: ImageVector = icon("Lock", LOCK, CHIP_STROKE)
    val Globe: ImageVector = icon("Globe", GLOBE, CHIP_STROKE)
    val Warning: ImageVector = icon("Warning", WARNING, CHIP_STROKE)
    val Monitor: ImageVector = icon("Monitor", MONITOR, CHIP_STROKE)
    val Phone: ImageVector = icon("Phone", PHONE, CHIP_STROKE)
    val Search: ImageVector = icon("Search", SEARCH, 2f)
    val ChevronUp: ImageVector = icon("ChevronUp", CHEVRON_UP, 2f)
    val ChevronDown: ImageVector = icon("ChevronDown", CHEVRON_DOWN, 2f)
    val Close: ImageVector = icon("Close", CLOSE, 2f)
    val Overflow: ImageVector = icon("Overflow", OVERFLOW, 3f)

    /** The chip's security glyph for [security] (lock / globe / warning). */
    fun security(security: ToolbarState.ChipSecurity): ImageVector = when (security) {
        ToolbarState.ChipSecurity.Lock -> Lock
        ToolbarState.ChipSecurity.Globe -> Globe
        ToolbarState.ChipSecurity.Warning -> Warning
    }

    private const val CHIP_STROKE = 2.2f

    private fun icon(name: String, path: String, stroke: Float): ImageVector =
        ImageVector.Builder(
            name = "Hatch.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(path),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = stroke,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
}
