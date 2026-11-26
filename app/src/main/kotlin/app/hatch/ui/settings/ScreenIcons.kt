package app.hatch.ui.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The glyphs of Settings, the persona editor, site rules, site permissions, saved logins and the crash screen, from
 * the exact SVG paths of `docs/design/hatch-screens.dc.html` (24-unit viewBox, stroke 2, round caps; the `SI` table
 * for the Settings rows, the `perms` table for permissions). `rect` / `circle` elements are written as the equivalent
 * paths. Glyphs the design does not draw (the rarer permissions) use the same stroke style. `Icon(tint = …)` colours
 * them; the stroke colour here is only a mask. Directional glyphs (Back, ChevronRight) are `autoMirror`ed so they flip
 * with the layout in RTL locales (A11Y-09); symbols (monitor, lock, chevron up/down) never are.
 */
internal object ScreenIcons {
    private const val CIRCLE_SEARCH = "M4 11a7 7 0 1 0 14 0a7 7 0 1 0 -14 0Z M20 20l-4-4"
    private const val COPY =
        "M10 8h8a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-8a2 2 0 0 1 -2 -2v-8a2 2 0 0 1 2 -2Z M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3"
    private const val EYE_OUTLINE = "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z"

    // Navigation and actions.
    val Back: ImageVector = icon("Back", "M19 12H5M11 6l-6 6 6 6", autoMirror = true)
    val Close: ImageVector = icon("Close", "M6 6l12 12M18 6L6 18")
    val Search: ImageVector = icon("Search", CIRCLE_SEARCH)
    val ChevronRight: ImageVector = icon("ChevronRight", "M9 6l6 6-6 6", autoMirror = true)
    val ChevronDown: ImageVector = icon("ChevronDown", "M6 9l6 6 6-6")
    val ChevronDownBold: ImageVector = icon("ChevronDownBold", "M6 9l6 6 6-6", 2.4f)
    val ChevronUp: ImageVector = icon("ChevronUp", "M6 15l6-6 6 6")
    val Plus: ImageVector = icon("Plus", "M12 5v14M5 12h14", 2.2f)
    val Info: ImageVector = icon("Info", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0Z M12 11v5M12 8h.01")
    val Check: ImageVector = icon("Check", "M5 12l5 5 9-10", 2.4f)
    val Copy: ImageVector = icon("Copy", COPY, cap = StrokeCap.Butt, join = StrokeJoin.Miter)
    val Eye: ImageVector = icon("Eye", "$EYE_OUTLINE M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z", cap = StrokeCap.Butt, join = StrokeJoin.Miter)
    val EyeOff: ImageVector = icon("EyeOff", "$EYE_OUTLINE M4 4l16 16")
    val Refresh: ImageVector = icon("Refresh", "M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5")

    // Settings rows (the design's `SI` table).
    val Home: ImageVector = icon("Home", "M4 11l8-7 8 7v9H4z")
    val Palette: ImageVector = icon("Palette", "M12 3a9 9 0 1 0 0 18c1 0 1.5-.8 1.2-1.7-.4-1 .3-2.3 1.6-2.3H17a4 4 0 0 0 4-4c0-5-4-10-9-10z")
    val Toolbar: ImageVector = icon("Toolbar", "M3 4h18v16H3zM3 9h18")
    val Monitor: ImageVector = icon("Monitor", "M3 4h18v12H3zM9 20h6M12 16v4")
    val Swap: ImageVector = icon("Swap", "M4 8h14l-3-3M20 16H6l3 3")
    val Tune: ImageVector = icon("Tune", "M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12")
    val Key: ImageVector = icon("Key", "M8 11a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM11 12l9-9M17 6l3 3")
    val Shield: ImageVector = icon("Shield", "M12 3l8 3v6c0 5-4 8-8 9-4-1-8-4-8-9V6z")
    val Code: ImageVector = icon("Code", "M8 6l-6 6 6 6M16 6l6 6-6 6")
    val Link: ImageVector = icon("Link", "M10 14a4 4 0 0 0 6 0l3-3a4 4 0 0 0-6-6l-1 1M14 10a4 4 0 0 0-6 0l-3 3a4 4 0 0 0 6 6l1-1")
    val Puzzle: ImageVector = icon("Puzzle", "M4 8h4a2 2 0 1 1 4 0h4v4a2 2 0 1 1 0 4v4H4z")
    val Terminal: ImageVector = icon("Terminal", "M3 4h18v16H3zM7 9l3 3-3 3M13 15h4")

    // Site permissions (the design's `perms` table, then the rarer permissions in the same style).
    val Camera: ImageVector = icon("Camera", "M2 7h14v11H2zM16 11l6-3v9l-6-3")
    val Microphone: ImageVector = icon("Microphone", "M9 3h6v11H9zM5 11a7 7 0 0 0 14 0M12 18v3")
    val Location: ImageVector = icon("Location", "M12 21s-7-6-7-12a7 7 0 0 1 14 0c0 6-7 12-7 12z")
    val Notifications: ImageVector = icon("Notifications", "M6 16V11a6 6 0 0 1 12 0v5l2 2H4zM10 21h4")
    val Storage: ImageVector = icon("Storage", "M4 6c0-2 16-2 16 0v12c0 2-16 2-16 0zM4 6c0 2 16 2 16 0M4 12c0 2 16 2 16 0")
    val Autoplay: ImageVector = icon("Autoplay", "M7 5v14l12-7z")
    val AutoplayMuted: ImageVector = icon("AutoplayMuted", "M5 5v14l12-7zM19 5l-4 4M15 5l4 4")
    val Drm: ImageVector = icon("Drm", "M7 11h10v9H7zM9 11V8a3 3 0 0 1 6 0v3")
    val Cookie: ImageVector = icon("Cookie", "M12 3a9 9 0 1 0 9 9 3 3 0 0 1-3-3 3 3 0 0 1-3-3 3 3 0 0 1-3-3zM9 10h.01M14 15h.01M9 15h.01")
    val Device: ImageVector = icon("Device", "M6 6h12v12H6zM9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4")
    val Network: ImageVector = icon("Network", "M2 9a15 15 0 0 1 20 0M5 13a10 10 0 0 1 14 0M8.5 16.5a5 5 0 0 1 7 0M12 20h.01")
    val Bluetooth: ImageVector = icon("Bluetooth", "M7 7l10 10-5 5V2l5 5L7 17")

    // Saved logins.
    val HiddenNote: ImageVector = icon("HiddenNote", "$EYE_OUTLINE M4 4l16 16")

    private fun icon(
        name: String,
        path: String,
        stroke: Float = 2f,
        cap: StrokeCap = StrokeCap.Round,
        join: StrokeJoin = StrokeJoin.Round,
        autoMirror: Boolean = false,
    ): ImageVector =
        ImageVector.Builder(
            name = "HatchScreens.$name",
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
