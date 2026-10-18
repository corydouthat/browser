package app.hatch.ui.persona

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hatch.Components
import app.hatch.persona.Identity
import app.hatch.persona.Persona
import app.hatch.persona.Presets
import app.hatch.persona.Region
import app.hatch.ui.rules.RulesScaffold
import kotlinx.coroutines.launch

/**
 * Create / edit / delete custom identities and regions (`PersonaManager.saveIdentity/saveRegion/delete*`).
 * Presets are read-only: "Duplicate" copies one into an editable custom entry. Saving an identity or region that
 * is part of the active persona re-applies it live (a time-zone change still needs the restart snackbar, which
 * [onApplyPersona] handles). Custom personas are not covered by the golden tests (`docs/persona-model.md`).
 */
@Composable
fun PersonaEditorScreen(
    components: Components,
    onClose: () -> Unit,
    onApplyPersona: (Persona) -> Unit,
    onOpenPersonaSheet: () -> Unit,
) {
    val pm = components.personaManager
    val identities by pm.identities.collectAsState()
    val regions by pm.regions.collectAsState()
    val current by pm.current.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf<Editing?>(null) }
    var confirmDelete by remember { mutableStateOf<Deletable?>(null) }

    fun existingIds(): Set<String> = (identities.map { it.id } + regions.map { it.id }).toSet()

    fun saveIdentity(id: String?, draft: IdentityDraft): List<String> {
        val problems = PersonaDrafts.identityProblems(draft)
        if (problems.isNotEmpty()) return problems
        val finalId = id ?: PersonaDrafts.uniqueId(draft.name, existingIds())
        val identity = PersonaDrafts.buildIdentity(finalId, draft)
        return runCatching { pm.saveIdentity(identity) }.fold(
            onSuccess = {
                if (current.identity.id == finalId) onApplyPersona(Persona(identity, current.region))
                emptyList()
            },
            onFailure = { listOf(it.message ?: it.toString()) },
        )
    }

    fun saveRegion(id: String?, draft: RegionDraft): List<String> {
        val problems = PersonaDrafts.regionProblems(draft)
        if (problems.isNotEmpty()) return problems
        val finalId = id ?: PersonaDrafts.uniqueId(draft.name, existingIds())
        val region = PersonaDrafts.buildRegion(finalId, draft)
        return runCatching { pm.saveRegion(region) }.fold(
            onSuccess = {
                if (current.region.id == finalId) onApplyPersona(Persona(current.identity, region))
                emptyList()
            },
            onFailure = { listOf(it.message ?: it.toString()) },
        )
    }

    fun delete(d: Deletable) {
        when (d) {
            is Deletable.Id -> {
                if (current.identity.id == d.identity.id) onApplyPersona(Persona(Presets.default().identity, current.region))
                pm.deleteIdentity(d.identity.id)
            }
            is Deletable.Reg -> {
                if (current.region.id == d.region.id) onApplyPersona(Persona(current.identity, Presets.default().region))
                pm.deleteRegion(d.region.id)
            }
        }
        scope.launch { snackbar.showSnackbar("Deleted ${d.name}") }
    }

    when (val e = editing) {
        is Editing.IdentityForm -> IdentityForm(
            title = if (e.id == null) "New identity" else "Edit identity",
            initial = e.draft,
            onCancel = { editing = null },
            onSave = { draft ->
                val problems = saveIdentity(e.id, draft)
                if (problems.isEmpty()) editing = null
                problems
            },
        )
        is Editing.RegionForm -> RegionForm(
            title = if (e.id == null) "New region" else "Edit region",
            initial = e.draft,
            onCancel = { editing = null },
            onSave = { draft ->
                val problems = saveRegion(e.id, draft)
                if (problems.isEmpty()) editing = null
                problems
            },
        )
        null -> RulesScaffold(
            title = "Personas",
            onBack = onClose,
            snackbar = snackbar,
            actions = { TextButton(onClick = onOpenPersonaSheet) { Text("Switch…") } },
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                item {
                    Text(
                        "Active: ${current.identity.name} · ${current.region.name}. Presets are read-only; duplicate one to edit. " +
                            "Custom personas are not covered by the golden tests: check them with Self-check.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SectionHeader("Identities (what machine and browser)") {
                        editing = Editing.IdentityForm(null, IdentityDraft.from(Presets.default().identity, name = ""))
                    }
                }
                items(identities, key = { "i:" + it.id }) { id ->
                    val preset = pm.isPreset(id)
                    EntryRow(
                        title = id.name + if (preset) "" else " (custom)",
                        subtitle = identitySubtitle(id),
                        active = id.id == current.identity.id,
                        preset = preset,
                        onEdit = { editing = Editing.IdentityForm(id.id, IdentityDraft.from(id)) },
                        onDuplicate = { editing = Editing.IdentityForm(null, IdentityDraft.from(id, name = id.name + " (copy)")) },
                        onDelete = { confirmDelete = Deletable.Id(id) },
                    )
                }
                item {
                    SectionHeader("Regions (where and which language)") {
                        editing = Editing.RegionForm(null, RegionDraft(name = "", languages = "en-US, en", locale = "en-US"))
                    }
                }
                items(regions, key = { "r:" + it.id }) { r ->
                    val preset = pm.isPreset(r)
                    EntryRow(
                        title = r.name + if (preset) "" else " (custom)",
                        subtitle = regionSubtitle(r),
                        active = r.id == current.region.id,
                        preset = preset,
                        onEdit = { editing = Editing.RegionForm(r.id, RegionDraft.from(r)) },
                        onDuplicate = { editing = Editing.RegionForm(null, RegionDraft.from(r, name = r.name + " (copy)")) },
                        onDelete = { confirmDelete = Deletable.Reg(r) },
                    )
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }

    confirmDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${d.name}?") },
            text = { Text(if (d.isActive(current)) "It is part of the active persona; the default preset takes its place." else "The custom entry is removed from this device.") },
            confirmButton = { Button(onClick = { delete(d); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

private sealed interface Editing {
    data class IdentityForm(val id: String?, val draft: IdentityDraft) : Editing
    data class RegionForm(val id: String?, val draft: RegionDraft) : Editing
}

private sealed interface Deletable {
    val name: String
    fun isActive(current: Persona): Boolean

    data class Id(val identity: Identity) : Deletable {
        override val name: String get() = identity.name
        override fun isActive(current: Persona): Boolean = current.identity.id == identity.id
    }

    data class Reg(val region: Region) : Deletable {
        override val name: String get() = region.name
        override fun isActive(current: Persona): Boolean = current.region.id == region.id
    }
}

internal fun identitySubtitle(id: Identity): String = buildString {
    append(if (id.desktop) "desktop ${id.layoutWidth}px" else "mobile")
    append(if (id.touch) " · touch" else " · no touch")
    id.platform?.let { append(" · ").append(it) }
    id.screen?.let { append(" · ${it.width}×${it.height}@${it.dpr}") }
    if (id.chromeLike) append(" · chrome-like")
}

internal fun regionSubtitle(r: Region): String = buildString {
    append(r.languages.joinToString(","))
    append(" · ").append(r.timeZone ?: "device tz")
    r.geolocation?.let { append(" · %.2f,%.2f".format(it.latitude, it.longitude)) } ?: append(" · real location")
}

@Composable
private fun SectionHeader(title: String, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onAdd) { Text("New") }
    }
}

@Composable
private fun EntryRow(
    title: String,
    subtitle: String,
    active: Boolean,
    preset: Boolean,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(enabled = !preset, onClick = onEdit)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title + if (active) "  •  active" else "", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (preset) {
                TextButton(onClick = onDuplicate) { Text("Duplicate") }
            } else {
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun Field(
    value: String,
    label: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    singleLine: Boolean = true,
    hint: String? = null,
) {
    val support: (@Composable () -> Unit)? = if (hint == null) null else { { Text(hint) } }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        supportingText = support,
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Problems(problems: List<String>) {
    if (problems.isEmpty()) return
    Column(Modifier.padding(top = 8.dp)) {
        problems.forEach { Text("• $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun IdentityForm(
    title: String,
    initial: IdentityDraft,
    onCancel: () -> Unit,
    onSave: (IdentityDraft) -> List<String>,
) {
    var d by remember(initial) { mutableStateOf(initial) }
    var problems by remember { mutableStateOf<List<String>>(emptyList()) }
    val snackbar = remember { SnackbarHostState() }
    RulesScaffold(
        title = title,
        onBack = onCancel,
        snackbar = snackbar,
        actions = { TextButton(onClick = { problems = onSave(d) }) { Text("Save") } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp)) {
            Field(d.name, "Name", { d = d.copy(name = it) })
            SwitchRow("Desktop", "Desktop UA mode + desktop viewport on every tab", d.desktop) { d = d.copy(desktop = it) }
            SwitchRow("Touch", "Keep touch events and a coarse pointer (only for a real-device style identity)", d.touch) { d = d.copy(touch = it) }
            Field(d.userAgent, "User agent (empty = engine default)", { d = d.copy(userAgent = it) }, singleLine = false)
            Field(d.platform, "navigator.platform", { d = d.copy(platform = it) }, hint = "Win32, MacIntel, Linux x86_64")
            Field(d.oscpu, "navigator.oscpu", { d = d.copy(oscpu = it) }, hint = "Windows NT 10.0; Win64; x64")
            Field(d.appVersion, "navigator.appVersion", { d = d.copy(appVersion = it) }, hint = "5.0 (Windows)")
            Field(d.layoutWidth, "Layout width (CSS px, browser.viewport.desktopWidth)", { d = d.copy(layoutWidth = it) }, numeric = true, hint = "1366 covers Bootstrap lg/Tailwind xl; 0 = engine default")
            Text("Screen (leave all empty for the real screen)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.fillMaxWidth()) {
                Field(d.screenWidth, "Width", { d = d.copy(screenWidth = it) }, Modifier.weight(1f), numeric = true)
                Spacer(Modifier.width(8.dp))
                Field(d.screenHeight, "Height", { d = d.copy(screenHeight = it) }, Modifier.weight(1f), numeric = true)
            }
            Row(Modifier.fillMaxWidth()) {
                Field(d.availWidth, "Avail width", { d = d.copy(availWidth = it) }, Modifier.weight(1f), numeric = true)
                Spacer(Modifier.width(8.dp))
                Field(d.availHeight, "Avail height", { d = d.copy(availHeight = it) }, Modifier.weight(1f), numeric = true)
            }
            Row(Modifier.fillMaxWidth()) {
                Field(d.colorDepth, "Color depth", { d = d.copy(colorDepth = it) }, Modifier.weight(1f), numeric = true)
                Spacer(Modifier.width(8.dp))
                Field(d.dpr, "Device pixel ratio", { d = d.copy(dpr = it) }, Modifier.weight(1f), numeric = true)
            }
            Field(d.hardwareConcurrency, "hardwareConcurrency (empty = engine default)", { d = d.copy(hardwareConcurrency = it) }, numeric = true, hint = "Presets use 8; workers still see the real count")
            Field(d.webglVendor, "WebGL unmasked vendor", { d = d.copy(webglVendor = it) }, hint = "Google Inc. (NVIDIA)")
            Field(d.webglRenderer, "WebGL unmasked renderer", { d = d.copy(webglRenderer = it) }, singleLine = false)
            SwitchRow("Chrome-like", "Adds navigator.userAgentData and Chrome-shaped plugins (UA only; Gecko is still detectable)", d.chromeLike) { d = d.copy(chromeLike = it) }
            if (d.chromeLike && d.uaData == null) {
                Text("No userAgentData brands on this identity: duplicate the Chrome preset to inherit them.", style = MaterialTheme.typography.bodySmall)
            }
            Problems(problems)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { problems = onSave(d) }, modifier = Modifier.weight(1f)) { Text("Save") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun RegionForm(
    title: String,
    initial: RegionDraft,
    onCancel: () -> Unit,
    onSave: (RegionDraft) -> List<String>,
) {
    var d by remember(initial) { mutableStateOf(initial) }
    var problems by remember { mutableStateOf<List<String>>(emptyList()) }
    var zonePicker by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    RulesScaffold(
        title = title,
        onBack = onCancel,
        snackbar = snackbar,
        actions = { TextButton(onClick = { problems = onSave(d) }) { Text("Save") } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp)) {
            Field(d.name, "Name", { d = d.copy(name = it) })
            Field(d.languages, "Languages (Accept-Language order, comma-separated)", { d = d.copy(languages = it) }, hint = "de-DE, de, en")
            Field(d.locale, "Locale (Intl / UI)", { d = d.copy(locale = it) }, hint = "de-DE")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Field(d.timeZone, "Time zone (IANA id; empty = device)", { d = d.copy(timeZone = it) }, Modifier.weight(1f), hint = "Changing it needs an app restart")
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { zonePicker = true }) { Text("Pick") }
            }
            Text("Geolocation (leave empty for the real position; the shim answers pages without a prompt when set)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.fillMaxWidth()) {
                Field(d.latitude, "Latitude", { d = d.copy(latitude = it) }, Modifier.weight(1f), numeric = true)
                Spacer(Modifier.width(8.dp))
                Field(d.longitude, "Longitude", { d = d.copy(longitude = it) }, Modifier.weight(1f), numeric = true)
            }
            Field(d.accuracyMeters, "Accuracy (m)", { d = d.copy(accuracyMeters = it) }, numeric = true)
            Problems(problems)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { problems = onSave(d) }, modifier = Modifier.weight(1f)) { Text("Save") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    if (zonePicker) {
        ZonePickerDialog(onDismiss = { zonePicker = false }, onPick = { d = d.copy(timeZone = it); zonePicker = false })
    }
}

@Composable
private fun ZonePickerDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val zones = remember(query) { PersonaDrafts.zoneSuggestions(query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Time zone") },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search (city or region)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(zones, key = { it }) { z ->
                        Text(z, modifier = Modifier.fillMaxWidth().clickable { onPick(z) }.padding(vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (zones.isEmpty()) item { Text("No zone matches", style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
