package app.hatch.ui

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hatch.persona.Identity
import app.hatch.persona.Persona
import app.hatch.persona.PersonaManager
import app.hatch.persona.PersonaToPrefs
import app.hatch.persona.Presets
import app.hatch.persona.Region
import app.hatch.ui.persona.PersonaText
import app.hatch.ui.settings.InfoBox
import app.hatch.ui.settings.PrimaryPill
import app.hatch.ui.settings.TextPill
import app.hatch.ui.settings.hatchSwitchColors
import app.hatch.ui.surfaces.PersonaLabels
import app.hatch.ui.surfaces.PreviewTheme
import app.hatch.ui.surfaces.SurfacePreviewFrame
import app.hatch.ui.surfaces.SurfacePreviewThemes
import app.hatch.ui.theme.HatchDimens
import app.hatch.ui.theme.HatchShapes
import app.hatch.ui.theme.HatchTheme
import app.hatch.ui.theme.hatchFocusRing

/**
 * The persona sheet (mockup "Persona sheet"): "Force desktop" switch (toggles between the last desktop identity and
 * real-device), Identity and Region chip groups, the time-zone notice when the chosen region changes `TZ`, and
 * Cancel / "Apply" ("Apply (restart)" when the time zone changes). "Apply" switches live; the caller shows the
 * restart snackbar when the time zone changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaSheet(
    personaManager: PersonaManager,
    onDismiss: () -> Unit,
    onApply: (Persona) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val current by personaManager.current.collectAsState()
    val identities by personaManager.identities.collectAsState()
    val regions by personaManager.regions.collectAsState()
    val c = HatchTheme.colors

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = HatchShapes.Sheet,
        containerColor = c.sh,
        contentColor = c.ink,
        scrimColor = Color.Black.copy(alpha = HatchDimens.SHEET_SCRIM_ALPHA),
        dragHandle = { SheetHandle() },
    ) {
        PersonaSheetContent(
            current = current,
            identities = identities,
            regions = regions,
            lastDesktopIdentityId = personaManager.lastDesktopIdentityId,
            needsRestart = personaManager::needsRestart,
            onDismiss = onDismiss,
            onApply = onApply,
        )
    }
}

/** The sheet's body, separated from the [PersonaManager] so previews can render it with preset data. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PersonaSheetContent(
    current: Persona,
    identities: List<Identity>,
    regions: List<Region>,
    lastDesktopIdentityId: String,
    needsRestart: (Persona) -> Boolean,
    onDismiss: () -> Unit,
    onApply: (Persona) -> Unit,
    initial: Persona = current,
) {
    val c = HatchTheme.colors
    var identity by remember(current) { mutableStateOf(initial.identity) }
    var region by remember(current) { mutableStateOf(initial.region) }
    val forceDesktop = identity.id != Presets.REAL_DEVICE_ID
    val chosen = Persona(identity, region)
    val restart = needsRestart(chosen)

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Persona", style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.semantics { heading() })
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .hatchFocusRing(RoundedCornerShape(12.dp))
                // A11Y-01: toggleable (On/Off), not selectable ("Selected").
                .toggleable(value = forceDesktop, role = Role.Switch) {
                    identity = if (!forceDesktop) {
                        identities.firstOrNull { it.id == lastDesktopIdentityId && it.desktop } ?: identities.first { it.desktop }
                    } else {
                        identities.firstOrNull { it.id == Presets.REAL_DEVICE_ID } ?: Presets.realDevice()
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Force desktop", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = c.ink)
                Text(
                    if (forceDesktop) "Desktop identity, touch events off" else "Real device: engine defaults, no shim",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.mu,
                )
            }
            Switch(checked = forceDesktop, onCheckedChange = null, colors = hatchSwitchColors())
        }
        ChipGroup("Identity") {
            identities.forEach { id ->
                SelectChip(PersonaLabels.short(id.name), selected = id.id == identity.id) { identity = id }
            }
        }
        ChipGroup("Region") {
            regions.forEach { r ->
                SelectChip(PersonaLabels.short(r.name), selected = r.id == region.id) { region = r }
            }
        }
        PersonaText.timeZoneNotice(restart, region)?.let { InfoBox(it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            TextPill("Cancel", onClick = onDismiss)
            PrimaryPill(if (restart) "Apply (restart)" else "Apply", onClick = { onApply(chosen) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroup(title: String, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = HatchTheme.colors.mu, modifier = Modifier.semantics { heading() })
        // Chips are 36 dp; minimumInteractiveComponentSize grows each touch target to 48 dp, so wrapped rows sit 12 dp
        // apart (48 dp pitch) instead of the mockup's 6 dp: the 48 dp targets win (DF-04, docs/limitations.md).
        FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { chips() }
    }
}

/** A 36 dp r10 choice chip: selected = primary fill, onPrimary 600; otherwise a 1 dp `ln2` outline, ink 400. */
@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = HatchTheme.colors
    val shape = HatchShapes.Chip
    Box(Modifier.minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .heightIn(min = 36.dp)
                .hatchFocusRing(shape)
                .clip(shape)
                .background(if (selected) c.ac else Color.Transparent)
                .border(1.dp, if (selected) Color.Transparent else c.ln2, shape)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
                color = if (selected) c.oa else c.ink,
            )
        }
    }
}

/** The persona sheet's mockup spacing (`gap:18px;padding:10px 20px 32px`, the handle being the column's first child). */
object PersonaSheetDimens {
    val HandleTop = 10.dp

    /** From the handle to the "Persona" title: the column's 18 dp gap (DF-04). */
    val HandleToTitle = 18.dp
}

/** The design's 32×4 dp drag handle in `ln2`, 10 dp from the sheet top and 18 dp above the title. */
@Composable
internal fun SheetHandle() {
    Box(Modifier.fillMaxWidth().padding(top = PersonaSheetDimens.HandleTop, bottom = PersonaSheetDimens.HandleToTitle), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(HatchTheme.colors.ln2))
    }
}

/** The mockup state: the default persona with the UK region chosen (time zone changes, "Apply (restart)"). */
@Preview(name = "Persona sheet · region changed", widthDp = 360)
@Composable
internal fun PersonaSheetPreview(@PreviewParameter(SurfacePreviewThemes::class) theme: PreviewTheme) {
    val presets = Presets.default()
    val uk = Presets.regions().firstOrNull { it.timeZone == "Europe/London" } ?: presets.region
    SurfacePreviewFrame(theme) {
        Column(Modifier.background(HatchTheme.colors.sh)) {
            SheetHandle()
            PersonaSheetContent(
                current = presets,
                identities = Presets.identities(),
                regions = Presets.regions(),
                lastDesktopIdentityId = presets.identity.id,
                needsRestart = { PersonaToPrefs.needsRestart(presets, it) },
                onDismiss = {},
                onApply = {},
                initial = Persona(presets.identity, uk),
            )
        }
    }
}
