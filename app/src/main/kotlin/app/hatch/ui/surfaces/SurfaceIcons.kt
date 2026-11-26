package app.hatch.ui.surfaces

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The glyphs of the browser surfaces (home, tab tray, menu, site info, login picker, crashed tab), drawn from the
 * exact SVG paths of `docs/design/hatch-screens.dc.html` (24-unit viewBox, stroked, no fill; `rect`/`circle`
 * elements written as the equivalent paths). `Icon(tint = …)` colours them, so the stroke colour is only a mask.
 * Stroke widths follow each mockup element (2 for list and quick-action glyphs, 2.2 for chip-sized ones, 2.4 / 3
 * for the check marks and the overflow dots). Back, Forward and ChevronRight are `autoMirror`ed (RTL, A11Y-09).
 */
internal object SurfaceIcons {
    // Shapes shared by several glyphs.
    private const val MONITOR = "M5 4H19A2 2 0 0 1 21 6V14A2 2 0 0 1 19 16H5A2 2 0 0 1 3 14V6A2 2 0 0 1 5 4Z M9 20h6M12 16v4"
    private const val PHONE = "M9 3H15A2 2 0 0 1 17 5V19A2 2 0 0 1 15 21H9A2 2 0 0 1 7 19V5A2 2 0 0 1 9 3Z"
    private const val LOCK = "M7 11H17A2 2 0 0 1 19 13V18A2 2 0 0 1 17 20H7A2 2 0 0 1 5 18V13A2 2 0 0 1 7 11Z M8 11V8a4 4 0 0 1 8 0v3"
    private const val CHECK = "M5 12l5 5 9-10"
    private const val CLOSE = "M6 6l12 12M18 6L6 18"

    // Home ("New tab").
    val Monitor: ImageVector = icon("Monitor", MONITOR, 2f, StrokeCap.Butt)
    val Phone: ImageVector = icon("Phone", PHONE, 2f, StrokeCap.Butt)
    val MonitorSmall: ImageVector = icon("MonitorSmall", MONITOR, 2.2f, StrokeCap.Butt)
    val PhoneSmall: ImageVector = icon("PhoneSmall", PHONE, 2.2f, StrokeCap.Butt)
    val Check: ImageVector = icon("Check", CHECK, 2.4f)
    val CheckBold: ImageVector = icon("CheckBold", CHECK, 3f)
    val Home: ImageVector = icon("Home", "M4 11l8-7 8 7v9H4z", 2f, StrokeCap.Butt)

    // Site info.
    val Lock: ImageVector = icon("Lock", LOCK, 2f, StrokeCap.Butt)
    val Warning: ImageVector = icon("Warning", "M12 3l10 18H2z M12 10v5M12 18h.01", 2f)
    val Shield: ImageVector = icon("Shield", "M12 3l8 3v6c0 5-4 8-8 9-4-1-8-4-8-9V6z", 2f)
    val ChevronRight: ImageVector = icon("ChevronRight", "M9 6l6 6-6 6", 2f, autoMirror = true)

    // Login picker.
    val Key: ImageVector = icon("Key", "M4 15A4 4 0 1 0 12 15A4 4 0 1 0 4 15Z M11 12l9-9M17 6l3 3", 2f)

    // Tab tray.
    val Close: ImageVector = icon("Close", CLOSE, 2f)
    val CloseSmall: ImageVector = icon("CloseSmall", CLOSE, 2.4f)
    val Overflow: ImageVector = icon("Overflow", "M12 5h.01M12 12h.01M12 19h.01", 3f)
    val Back: ImageVector = icon("Back", "M19 12H5M11 6l-6 6 6 6", 2f, autoMirror = true)
    val Share: ImageVector = icon("Share", "M12 3v13M7 8l5-5 5 5M5 14v5h14v-5", 2f)
    val Delete: ImageVector = icon("Delete", "M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13", 2f)
    val Plus: ImageVector = icon("Plus", "M12 5v14M5 12h14", 2.2f)

    // Main menu quick actions.
    val Forward: ImageVector = icon("Forward", "M5 12h14M13 6l6 6-6 6", 2f, autoMirror = true)
    val Reload: ImageVector = icon("Reload", "M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5", 2f)
    val Stop: ImageVector = icon("Stop", CLOSE, 2f)

    // Main menu list (the `D` table of the design script).
    val MenuPlus: ImageVector = icon("MenuPlus", "M12 5v14M5 12h14", 2f)
    val MenuFind: ImageVector = icon("MenuFind", "M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-4-4", 2f)
    val MenuExternal: ImageVector = icon("MenuExternal", "M14 4h6v6M20 4l-9 9M18 14v6H4V6h6", 2f)
    val MenuCode: ImageVector = icon("MenuCode", "M8 6l-6 6 6 6M16 6l6 6-6 6", 2f)
    val MenuShield: ImageVector = icon("MenuShield", "M12 3l8 3v6c0 5-4 8-8 9-4-1-8-4-8-9V6z", 2f)
    val MenuKey: ImageVector = icon("MenuKey", "M8 11a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM11 12l9-9M17 6l3 3", 2f)
    val MenuPuzzle: ImageVector = icon("MenuPuzzle", "M4 8h4a2 2 0 1 1 4 0h4v4a2 2 0 1 1 0 4v4H4z", 2f)
    val MenuGear: ImageVector = icon(
        "MenuGear",
        "M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M5 19l2-2M17 7l2-2",
        2f,
    )

    /** Settings › Developer's terminal glyph (`SI.term`), used for the opt-in "Eruda devtools" row. */
    val MenuTerminal: ImageVector = icon("MenuTerminal", "M3 4h18v16H3zM7 9l3 3-3 3M13 15h4", 2f)

    private fun icon(
        name: String,
        path: String,
        stroke: Float,
        cap: StrokeCap = StrokeCap.Round,
        join: StrokeJoin = StrokeJoin.Round,
        autoMirror: Boolean = false,
    ): ImageVector =
        ImageVector.Builder(
            name = "Hatch.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = autoMirror,
        ).addPath(
            pathData = addPathNodes(path),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = stroke,
            strokeLineCap = cap,
            strokeLineJoin = join,
        ).build()
}
