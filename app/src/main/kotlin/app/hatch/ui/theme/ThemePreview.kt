package app.hatch.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.hatch.R

/**
 * A reference sheet of the theme: the colour roles of every palette in the current mode, the type scale and a few
 * stock M3 components (to prove none falls back to the baseline purple). Rendered by the previews below; the
 * integrator renders them to PNGs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemePreview(dark: Boolean, modifier: Modifier = Modifier) {
    HatchTheme(mode = if (dark) ThemeMode.Dark else ThemeMode.Light) {
        Surface(modifier) {
            Column(Modifier.padding(HatchDimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(HatchDimens.GapLarge)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HatchDimens.GapMedium)) {
                    Image(painterResource(R.drawable.hatch_mark), contentDescription = "Hatch", modifier = Modifier.size(44.dp))
                    Column {
                        Text("Hatch", style = MaterialTheme.typography.headlineSmall)
                        Text("Desktop web, on the road.", style = MaterialTheme.typography.bodyMedium, color = HatchTheme.colors.mu)
                    }
                }
                HatchPalette.entries.forEach { PaletteSwatches(HatchColors.of(it, dark)) }
                TypeScale()
                HatchTheme(palette = HatchPalette.Phosphor, mode = if (dark) ThemeMode.Dark else ThemeMode.Light) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(HatchDimens.GapSmall), verticalArrangement = Arrangement.spacedBy(HatchDimens.GapSmall)) {
                        Button(onClick = {}) { Text("Apply") }
                        FilterChip(selected = true, onClick = {}, label = { Text("Windows · Firefox") })
                        FilterChip(selected = false, onClick = {}, label = { Text("Real device") })
                        Switch(checked = true, onCheckedChange = {})
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaletteSwatches(c: HatchColors) {
    val roles = listOf(
        "primary" to c.ac, "onPrimary" to c.oa, "primaryContainer" to c.tb, "onPrimaryContainer" to c.ti,
        "pill" to c.pl, "surface" to c.bg, "surfaceContainer" to c.s1, "surfaceContainerHigh" to c.sh,
        "surfaceContainerHighest" to c.s2, "onSurface" to c.ink, "onSurfaceVariant" to c.mu, "outlineVariant" to c.ln2,
        "error" to c.dg, "textAccent" to c.acT, "inverseSurface" to c.inv, "inversePrimary" to c.invAc, "ok" to c.ok,
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(c.palette.label, style = MaterialTheme.typography.titleMedium)
        // The toolbar sample: tint, desktop pill, filled persona chip.
        Row(
            Modifier.fillMaxWidth().heightIn(min = HatchDimens.Toolbar).background(c.tb).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).heightIn(min = HatchDimens.Pill).clip(HatchShapes.Pill).background(c.pl).padding(start = 4.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(HatchDimens.GapSmall),
            ) {
                Box(Modifier.heightIn(min = HatchDimens.PersonaChip).clip(HatchShapes.PersonaChip).background(c.ac).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Text("Desktop", style = MaterialTheme.typography.labelLarge, color = c.oa)
                }
                Text("console.aws.amazon.com", style = MaterialTheme.typography.bodyLarge, color = c.ti, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            roles.forEach { (name, color) -> Swatch(name, color) }
        }
    }
}

@Composable
private fun Swatch(name: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.width(150.dp)) {
        Box(Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)).background(color).border(1.dp, Color.Black.copy(alpha = 0.1f), RoundedCornerShape(8.dp)))
        Column {
            Text(name, style = HatchType.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("#%06X".format(color.toArgb() and 0xFFFFFF), style = HatchType.mono, color = HatchTheme.colors.mu)
        }
    }
}

@Composable
private fun TypeScale() {
    val t = MaterialTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("titleLarge 22/28 · 500 — Settings", style = t.titleLarge)
        Text("titleMedium 17/24 · 600 — Persona", style = t.titleMedium)
        Text("bodyLarge 15/22 · 400 — console.aws.amazon.com", style = t.bodyLarge)
        Text("bodyMedium 13/18 · 400 — Desktop identity, touch events off", style = t.bodyMedium, color = HatchTheme.colors.mu)
        Text("labelLarge 14/20 · 600 — Apply (restart)", style = t.labelLarge)
        Text("mono 12/16 · 500 — Europe/London · 1920×1080", style = HatchType.mono)
    }
}

@Preview(name = "Theme · light", widthDp = 360, heightDp = 2400, showBackground = true)
@Composable
internal fun ThemeLightPreview() = ThemePreview(dark = false)

@Preview(name = "Theme · dark", widthDp = 360, heightDp = 2400, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun ThemeDarkPreview() = ThemePreview(dark = true)
