package app.hatch.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.hatch.ui.surfaces.PreviewTheme
import app.hatch.ui.surfaces.SurfaceIcons
import app.hatch.ui.surfaces.SurfacePreviewFrame
import app.hatch.ui.surfaces.SurfacePreviewThemes
import app.hatch.ui.theme.HatchTheme
import app.hatch.ui.theme.hatchFocusRing

/** Geometry shared by the "Content crashed" state and (in CSS) the Hatch error page: icon tile, 48 dp pills. */
internal object StatePageDimens {
    val Tile = 56.dp
    val TileRadius = 16.dp
    val TileIcon = 26.dp
    val PillHeight = 48.dp
    val PillRadius = 24.dp
    val TopPadding = 96.dp
    val SidePadding = 28.dp
}

/**
 * `tab.engineState.crashed` ("Browser · Content crashed" mockup): SessionFeature released the view; Restore
 * re-creates the session from its saved state (`CrashAction.RestoreCrashedSessionAction`), Close tab goes through
 * the app's one close path (undo snackbar, UXP-04).
 */
@Composable
fun ContentCrashedState(onRestore: () -> Unit, onCloseTab: () -> Unit, modifier: Modifier = Modifier) {
    val c = HatchTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(rememberScrollState())
            .padding(
                start = StatePageDimens.SidePadding,
                end = StatePageDimens.SidePadding,
                top = StatePageDimens.TopPadding,
                bottom = 24.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StateIconTile(SurfaceIcons.Warning)
        Text(
            "This tab stopped working",
            style = StatePageTitle,
            color = c.ink,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "The page's content process crashed. Your other tabs are fine.",
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.5.sp),
            color = c.mu,
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatePill("Restore", primary = true, onClick = onRestore, modifier = Modifier.weight(1f))
            StatePill("Close tab", primary = false, onClick = onCloseTab, modifier = Modifier.weight(1f))
        }
    }
}

/** 24 sp 600, −0.01em (the crashed / error state titles). */
internal val StatePageTitle
    @Composable get() = MaterialTheme.typography.headlineSmall.copy(letterSpacing = (-0.01).em)

/** The 56 dp r16 `s1` tile with a 26 dp `mu` glyph. */
@Composable
internal fun StateIconTile(icon: ImageVector) {
    val c = HatchTheme.colors
    Box(
        Modifier.size(StatePageDimens.Tile).clip(RoundedCornerShape(StatePageDimens.TileRadius)).background(c.s1),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = c.mu, modifier = Modifier.size(StatePageDimens.TileIcon))
    }
}

/** 48 dp r24 pill: primary = `ac` / `oa` 15 sp 600, secondary = `s1` / `ink` 15 sp 500. */
@Composable
internal fun StatePill(label: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = HatchTheme.colors
    val shape = RoundedCornerShape(StatePageDimens.PillRadius)
    val fg: Color = if (primary) c.oa else c.ink
    Box(
        modifier
            .hatchFocusRing(shape)
            .heightIn(min = StatePageDimens.PillHeight)
            .clip(shape)
            .background(if (primary) c.ac else c.s1)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium),
            color = fg,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(widthDp = 360, heightDp = 640)
@Composable
internal fun ContentCrashedPreview(@PreviewParameter(SurfacePreviewThemes::class) theme: PreviewTheme) {
    SurfacePreviewFrame(theme) { ContentCrashedState(onRestore = {}, onCloseTab = {}) }
}
