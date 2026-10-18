package app.hatch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.hatch.persona.Identity
import app.hatch.persona.Persona
import app.hatch.persona.PersonaManager
import app.hatch.persona.Presets
import app.hatch.persona.Region

/**
 * Identity list + region list + "Force desktop" switch (toggles between the last desktop identity and
 * real-device). "Apply" switches live; the caller shows the restart snackbar when the time zone changed.
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
    var identity by remember(current) { mutableStateOf(current.identity) }
    var region by remember(current) { mutableStateOf(current.region) }
    val forceDesktop = identity.id != Presets.REAL_DEVICE_ID

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text("Persona", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Force desktop", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (forceDesktop) "Desktop identity, touch events off" else "Real device: engine defaults, no shim",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = forceDesktop,
                    onCheckedChange = { on ->
                        identity = if (on) {
                            identities.firstOrNull { it.id == personaManager.lastDesktopIdentityId && it.desktop }
                                ?: identities.first { it.desktop }
                        } else {
                            Presets.realDevice()
                        }
                    },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("Identity", style = MaterialTheme.typography.titleMedium)
            identities.forEach { id ->
                IdentityRow(id, selected = id.id == identity.id, preset = personaManager.isPreset(id)) { identity = id }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("Region", style = MaterialTheme.typography.titleMedium)
            regions.forEach { r ->
                RegionRow(r, selected = r.id == region.id, preset = personaManager.isPreset(r)) { region = r }
            }
            val needsRestart = personaManager.needsRestart(Persona(identity, region))
            if (needsRestart) {
                Text(
                    "Time zone changes need a restart (TZ is read once at engine start).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.padding(4.dp))
                Button(onClick = { onApply(Persona(identity, region)) }) {
                    Text(if (needsRestart) "Apply (restart)" else "Apply")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun IdentityRow(id: Identity, selected: Boolean, preset: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(id.name + if (preset) "" else " (custom)", style = MaterialTheme.typography.bodyLarge)
            val detail = buildString {
                append(if (id.desktop) "desktop ${id.layoutWidth}px" else "mobile")
                append(if (id.touch) " · touch" else " · no touch")
                id.platform?.let { append(" · ").append(it) }
                if (id.chromeLike) append(" · chrome-like")
            }
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RegionRow(r: Region, selected: Boolean, preset: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(r.name + if (preset) "" else " (custom)", style = MaterialTheme.typography.bodyLarge)
            val detail = buildString {
                append(r.languages.joinToString(","))
                append(" · ").append(r.timeZone ?: "device tz")
                r.geolocation?.let { append(" · %.2f,%.2f".format(it.latitude, it.longitude)) }
            }
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
