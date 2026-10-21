package app.hatch.ui.persona

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hatch.Components
import app.hatch.persona.Identity
import app.hatch.persona.Persona
import app.hatch.persona.Presets
import app.hatch.persona.Region
import app.hatch.ui.rules.RulesScaffold
import app.hatch.ui.settings.CardDivider
import app.hatch.ui.settings.CardRowFocusShape
import app.hatch.ui.settings.FabClearance
import app.hatch.ui.settings.FormField
import app.hatch.ui.settings.GroupTitle
import app.hatch.ui.settings.GroupedCard
import app.hatch.ui.settings.HatchExtendedFab
import app.hatch.ui.settings.NavIcon
import app.hatch.ui.settings.OutlinedPill
import app.hatch.ui.settings.PageNote
import app.hatch.ui.settings.ScreenIcons
import app.hatch.ui.settings.ScreenTopBar
import app.hatch.ui.settings.SegmentedTabs
import app.hatch.ui.settings.SwitchCardRow
import app.hatch.ui.settings.TextPill
import app.hatch.ui.settings.TopBarPrimaryButton
import app.hatch.ui.settings.TopBarTextAction
import app.hatch.ui.surfaces.PersonaLabels
import app.hatch.ui.surfaces.PreviewTheme
import app.hatch.ui.surfaces.SurfacePreviewFrame
import app.hatch.ui.surfaces.SurfacePreviewThemes
import app.hatch.ui.theme.HatchTheme
import app.hatch.ui.theme.HatchType
import app.hatch.ui.theme.hatchFocusRing
import kotlinx.coroutines.launch

/**
 * Create / edit / delete custom identities and regions (`PersonaManager.saveIdentity/saveRegion/delete*`), per the
 * mockups "Persona editor" and "Persona editor · Custom form". A segmented control switches between identities and
 * regions; presets are read-only ("Duplicate" copies one into an editable custom entry), custom entries open the
 * form. Saving an identity or region that is part of the active persona re-applies it live (a time-zone change
 * still needs the restart snackbar, which [onApplyPersona] handles). Custom personas are not covered by the golden
 * tests (`docs/persona-model.md`).
 */
@Composable
fun PersonaEditorScreen(
    components: Components,
    onClose: () -> Unit,
    onApplyPersona: (Persona) -> Unit,
) {
    val pm = components.personaManager
    val identities by pm.identities.collectAsState()
    val regions by pm.regions.collectAsState()
    val current by pm.current.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var tab by rememberSaveable { mutableStateOf(0) }
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
        editing = null
        scope.launch { snackbar.showSnackbar("Deleted ${d.name}") }
    }

    when (val e = editing) {
        is Editing.IdentityForm -> RulesScaffold(
            title = if (e.id == null) "New identity" else "Edit identity",
            onBack = { editing = null },
            snackbar = snackbar,
            nav = NavIcon.Close,
            actions = {
                TopBarPrimaryButton("Save", enabled = e.valid, onClick = {
                    val problems = saveIdentity(e.id, e.draft)
                    editing = if (problems.isEmpty()) null else e.copy(saveError = problems.first())
                })
            },
        ) { padding ->
            IdentityFormContent(
                initial = e.initial,
                draft = e.draft,
                saveError = e.saveError,
                onChange = { editing = e.copy(draft = it, saveError = null) },
                onDelete = e.id?.let { id -> identities.firstOrNull { it.id == id }?.let { found -> { confirmDelete = Deletable.Id(found) } } },
                modifier = Modifier.padding(padding),
            )
        }
        is Editing.RegionForm -> RulesScaffold(
            title = if (e.id == null) "New region" else "Edit region",
            onBack = { editing = null },
            snackbar = snackbar,
            nav = NavIcon.Close,
            actions = {
                TopBarPrimaryButton("Save", enabled = e.valid, onClick = {
                    val problems = saveRegion(e.id, e.draft)
                    editing = if (problems.isEmpty()) null else e.copy(saveError = problems.first())
                })
            },
        ) { padding ->
            RegionFormContent(
                initial = e.initial,
                draft = e.draft,
                saveError = e.saveError,
                onChange = { editing = e.copy(draft = it, saveError = null) },
                onDelete = e.id?.let { id -> regions.firstOrNull { it.id == id }?.let { found -> { confirmDelete = Deletable.Reg(found) } } },
                modifier = Modifier.padding(padding),
            )
        }
        null -> RulesScaffold(
            title = "Personas",
            onBack = onClose,
            snackbar = snackbar,
            floatingActionButton = {
                HatchExtendedFab(if (tab == 0) "New identity" else "New region", onClick = {
                    editing = if (tab == 0) {
                        Editing.IdentityForm(null, IdentityDraft.from(Presets.default().identity, name = ""))
                    } else {
                        Editing.RegionForm(null, RegionDraft(name = "", languages = "en-US, en", locale = "en-US"))
                    }
                })
            },
        ) { padding ->
            PersonaListContent(
                tab = tab,
                onTab = { tab = it },
                identities = identities,
                regions = regions,
                isPresetIdentity = pm::isPreset,
                isPresetRegion = pm::isPreset,
                onDuplicateIdentity = { id -> editing = Editing.IdentityForm(null, IdentityDraft.from(id, name = id.name + " (copy)")) },
                onEditIdentity = { id -> editing = Editing.IdentityForm(id.id, IdentityDraft.from(id)) },
                onDuplicateRegion = { r -> editing = Editing.RegionForm(null, RegionDraft.from(r, name = r.name + " (copy)")) },
                onEditRegion = { r -> editing = Editing.RegionForm(r.id, RegionDraft.from(r)) },
                modifier = Modifier.padding(padding),
            )
        }
    }

    confirmDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${d.name}?") },
            text = { Text(if (d.isActive(current)) "It is part of the active persona; the default preset takes its place." else "The custom entry is removed from this device.") },
            confirmButton = { TextButton(onClick = { delete(d); confirmDelete = null }) { Text("Delete", color = HatchTheme.colors.dg) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel", color = HatchTheme.colors.acT) } },
            containerColor = HatchTheme.colors.sh,
        )
    }
}

private sealed interface Editing {
    data class IdentityForm(val id: String?, val initial: IdentityDraft, val draft: IdentityDraft = initial, val saveError: String? = null) : Editing {
        val valid: Boolean get() = PersonaDrafts.identityFieldProblems(draft).isEmpty()
    }

    data class RegionForm(val id: String?, val initial: RegionDraft, val draft: RegionDraft = initial, val saveError: String? = null) : Editing {
        val valid: Boolean get() = PersonaDrafts.regionFieldProblems(draft).isEmpty()
    }
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

/** The list page: segmented Identities / Regions, the Presets card (Duplicate), the Custom card (chevron → form). */
@Composable
internal fun PersonaListContent(
    tab: Int,
    onTab: (Int) -> Unit,
    identities: List<Identity>,
    regions: List<Region>,
    isPresetIdentity: (Identity) -> Boolean,
    isPresetRegion: (Region) -> Boolean,
    onDuplicateIdentity: (Identity) -> Unit,
    onEditIdentity: (Identity) -> Unit,
    onDuplicateRegion: (Region) -> Unit,
    onEditRegion: (Region) -> Unit,
    modifier: Modifier = Modifier,
) {
    // DF-06: one-line titles (PersonaLabels.short, as on the sheet chips); the parenthetical moves under the subtitle.
    data class Entry(val key: String, val name: String, val sub: String, val note: String?, val preset: Boolean, val duplicate: () -> Unit, val edit: () -> Unit)
    val entries = if (tab == 0) {
        identities.map {
            Entry("i:" + it.id, PersonaLabels.short(it.name), PersonaText.identitySubtitle(it), PersonaLabels.note(it.name), isPresetIdentity(it), { onDuplicateIdentity(it) }, { onEditIdentity(it) })
        }
    } else {
        regions.map {
            Entry("r:" + it.id, PersonaLabels.short(it.name), PersonaText.regionSubtitle(it), PersonaLabels.note(it.name), isPresetRegion(it), { onDuplicateRegion(it) }, { onEditRegion(it) })
        }
    }
    val presets = entries.filter { it.preset }
    val custom = entries.filterNot { it.preset }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp)) {
        item(key = "tabs") {
            SegmentedTabs(listOf("Identities", "Regions"), tab, onTab, Modifier.padding(bottom = 12.dp))
        }
        item(key = "presets-title") { GroupTitle("Presets", padding = PaddingValues(8.dp)) }
        item(key = "presets") {
            GroupedCard {
                presets.forEachIndexed { i, e ->
                    if (i > 0) CardDivider()
                    EntryRow(e.name, e.sub, e.note) {
                        TextPill("Duplicate", onClick = e.duplicate, height = 36.dp)
                    }
                }
            }
        }
        item(key = "custom-title") { GroupTitle("Custom", padding = PaddingValues(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 8.dp)) }
        item(key = "custom") {
            if (custom.isEmpty()) {
                PageNote(if (tab == 0) "No custom identities yet. Duplicate a preset or tap New identity." else "No custom regions yet. Duplicate a preset or tap New region.")
            } else {
                GroupedCard {
                    custom.forEachIndexed { i, e ->
                        if (i > 0) CardDivider()
                        EntryRow(e.name, e.sub, e.note, onClick = e.edit) {
                            Icon(ScreenIcons.ChevronRight, contentDescription = null, tint = HatchTheme.colors.mu, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
        item(key = "note") {
            PageNote(
                "Custom personas are not covered by the golden tests: check them with Self-check.",
                Modifier.padding(top = 16.dp),
            )
        }
        item(key = "clearance") { FabClearance() }
    }
}

@Composable
private fun EntryRow(name: String, sub: String, note: String?, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit) {
    val c = HatchTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .then(
                if (onClick != null) {
                    Modifier.hatchFocusRing(CardRowFocusShape, inside = true).clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(start = 16.dp, end = if (onClick != null) 16.dp else 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            Text(sub, style = HatchType.mono, color = c.mu, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(note.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall, color = c.mu)
        }
        trailing()
    }
}

/** Mono value styles of the form (design: UA 12/1.5, grid values 14, WebGL 13). */
private val UaStyle = HatchType.mono.copy(lineHeight = 18.sp)

/**
 * The identity form ("Edit identity"): Name, User agent, a 2-column grid (Platform, Screen, Pixel ratio, CPU cores),
 * WebGL vendor / renderer, then the advanced fields and switches. Every field shows its own problem inline once the
 * draft differs from [initial] (a fresh form starts quiet; Save stays disabled until it is valid).
 */
@Composable
internal fun IdentityFormContent(
    initial: IdentityDraft,
    draft: IdentityDraft,
    saveError: String?,
    onChange: (IdentityDraft) -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val d = draft
    val dirty = d != initial
    val problems = PersonaDrafts.byField(PersonaDrafts.identityFieldProblems(d))
    fun err(f: IdentityField): String? = if (dirty) problems[f]?.hint else null
    var screenText by remember(initial) { mutableStateOf(PersonaDrafts.screenText(initial.screenWidth, initial.screenHeight)) }
    var availText by remember(initial) { mutableStateOf(PersonaDrafts.screenText(initial.availWidth, initial.availHeight)) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FormField("Name", d.name, { onChange(d.copy(name = it)) }, error = err(IdentityField.Name))
        FormField("User agent", d.userAgent, { onChange(d.copy(userAgent = it)) }, valueStyle = UaStyle, singleLine = false, placeholder = "Engine default")
        Grid(
            { FormField("Platform", d.platform, { onChange(d.copy(platform = it)) }, it, valueStyle = HatchType.monoLarge, placeholder = "Default") },
            {
                FormField(
                    "Screen", screenText,
                    { t -> screenText = t; val (w, h) = PersonaDrafts.splitScreenText(t); onChange(d.copy(screenWidth = w, screenHeight = h)) },
                    it, valueStyle = HatchType.monoLarge, error = err(IdentityField.Screen), placeholder = "Real",
                )
            },
        )
        Grid(
            { FormField("Pixel ratio", d.dpr, { onChange(d.copy(dpr = it)) }, it, valueStyle = HatchType.monoLarge, error = err(IdentityField.Dpr), keyboardType = KeyboardType.Decimal) },
            {
                FormField(
                    "CPU cores", d.hardwareConcurrency, { onChange(d.copy(hardwareConcurrency = it)) }, it,
                    valueStyle = HatchType.monoLarge, error = err(IdentityField.Cores), placeholder = "Default", keyboardType = KeyboardType.Number,
                )
            },
        )
        FormField("WebGL vendor", d.webglVendor, { onChange(d.copy(webglVendor = it)) }, valueStyle = HatchType.monoMedium, error = err(IdentityField.WebglVendor), placeholder = "Not reported")
        FormField("WebGL renderer", d.webglRenderer, { onChange(d.copy(webglRenderer = it)) }, valueStyle = HatchType.monoMedium, error = err(IdentityField.WebglRenderer), placeholder = "Not reported")

        GroupTitle("Advanced", padding = PaddingValues(start = 4.dp, top = 6.dp))
        FormField(
            "Layout width (CSS px)", d.layoutWidth, { onChange(d.copy(layoutWidth = it)) }, valueStyle = HatchType.monoLarge,
            error = err(IdentityField.LayoutWidth), keyboardType = KeyboardType.Number,
        )
        Grid(
            {
                FormField(
                    "Available size", availText,
                    { t -> availText = t; val (w, h) = PersonaDrafts.splitScreenText(t); onChange(d.copy(availWidth = w, availHeight = h)) },
                    it, valueStyle = HatchType.monoLarge, error = err(IdentityField.AvailSize), placeholder = "Real",
                )
            },
            { FormField("Color depth", d.colorDepth, { onChange(d.copy(colorDepth = it)) }, it, valueStyle = HatchType.monoLarge, error = err(IdentityField.ColorDepth), keyboardType = KeyboardType.Number) },
        )
        FormField("navigator.oscpu", d.oscpu, { onChange(d.copy(oscpu = it)) }, valueStyle = HatchType.monoMedium, placeholder = "Default")
        FormField("navigator.appVersion", d.appVersion, { onChange(d.copy(appVersion = it)) }, valueStyle = HatchType.monoMedium, placeholder = "Default")
        GroupedCard {
            SwitchCardRow("Desktop", "Desktop UA mode and desktop viewport on every tab", d.desktop, { onChange(d.copy(desktop = it)) })
            CardDivider()
            SwitchCardRow("Touch", "Keep touch events and a coarse pointer (real-device style identities only)", d.touch, { onChange(d.copy(touch = it)) })
            CardDivider()
            SwitchCardRow("Chrome-like", "Adds navigator.userAgentData and Chrome-shaped plugins (UA only; Gecko stays detectable)", d.chromeLike, { onChange(d.copy(chromeLike = it)) })
        }
        err(IdentityField.Touch)?.let { ErrorText(it) }
        if (d.chromeLike && d.uaData == null) {
            PageNote("No userAgentData brands on this identity: duplicate the Chrome preset to inherit them.")
        }
        saveError?.let { ErrorText(it) }
        if (onDelete != null) OutlinedPill("Delete identity", onClick = onDelete, modifier = Modifier.fillMaxWidth())
        Box(Modifier.heightIn(min = 24.dp))
    }
}

/** The region form: Name, Languages, Locale, Time zone (with Pick), Latitude / Longitude, Accuracy. */
@Composable
internal fun RegionFormContent(
    initial: RegionDraft,
    draft: RegionDraft,
    saveError: String?,
    onChange: (RegionDraft) -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val d = draft
    val dirty = d != initial
    val problems = PersonaDrafts.byField(PersonaDrafts.regionFieldProblems(d))
    fun err(f: RegionField): String? = if (dirty) problems[f]?.hint else null
    var zonePicker by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FormField("Name", d.name, { onChange(d.copy(name = it)) }, error = err(RegionField.Name))
        FormField("Languages (Accept-Language order)", d.languages, { onChange(d.copy(languages = it)) }, valueStyle = HatchType.monoLarge, error = err(RegionField.Languages), placeholder = "de-DE, de, en")
        FormField("Locale", d.locale, { onChange(d.copy(locale = it)) }, valueStyle = HatchType.monoLarge, error = err(RegionField.Locale), placeholder = "de-DE")
        FormField(
            "Time zone (restart to apply)", d.timeZone, { onChange(d.copy(timeZone = it)) }, valueStyle = HatchType.monoLarge,
            error = err(RegionField.TimeZone), placeholder = "Device time zone",
            trailing = { TopBarTextAction("Pick", onClick = { zonePicker = true }) },
        )
        Grid(
            { FormField("Latitude", d.latitude, { onChange(d.copy(latitude = it)) }, it, valueStyle = HatchType.monoLarge, error = err(RegionField.Latitude), placeholder = "Real", keyboardType = KeyboardType.Decimal) },
            { FormField("Longitude", d.longitude, { onChange(d.copy(longitude = it)) }, it, valueStyle = HatchType.monoLarge, error = err(RegionField.Longitude), placeholder = "Real", keyboardType = KeyboardType.Decimal) },
        )
        FormField("Accuracy (m)", d.accuracyMeters, { onChange(d.copy(accuracyMeters = it)) }, valueStyle = HatchType.monoLarge, error = err(RegionField.Accuracy), keyboardType = KeyboardType.Decimal)
        PageNote("With a position set, the shim answers pages without a prompt; leave it empty for the real location.")
        saveError?.let { ErrorText(it) }
        if (onDelete != null) OutlinedPill("Delete region", onClick = onDelete, modifier = Modifier.fillMaxWidth())
        Box(Modifier.heightIn(min = 24.dp))
    }
    if (zonePicker) {
        ZonePickerDialog(onDismiss = { zonePicker = false }, onPick = { onChange(d.copy(timeZone = it)); zonePicker = false })
    }
}

/** Two fields side by side (10 dp gap); each column gets the modifier that weights it. */
@Composable
private fun Grid(start: @Composable (Modifier) -> Unit, end: @Composable (Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        start(Modifier.weight(1f))
        end(Modifier.weight(1f))
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = HatchTheme.colors.dg, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun ZonePickerDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val zones = remember(query) { PersonaDrafts.zoneSuggestions(query) }
    val c = HatchTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.sh,
        title = { Text("Time zone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormField("Search (city or region)", query, { query = it })
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(zones, key = { it }) { z ->
                        Text(
                            z,
                            style = HatchType.monoMedium,
                            color = c.ink,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .hatchFocusRing(RoundedCornerShape(8.dp), inside = true)
                                .clickable(role = Role.Button) { onPick(z) }
                                .padding(vertical = 14.dp, horizontal = 4.dp),
                        )
                    }
                    if (zones.isEmpty()) item { Text("No zone matches", style = MaterialTheme.typography.bodyMedium, color = c.mu) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = c.acT) } },
    )
}

@Preview(name = "Persona editor · identities", widthDp = 360, heightDp = 760)
@Composable
internal fun PersonaEditorListPreview(@PreviewParameter(SurfacePreviewThemes::class) theme: PreviewTheme) {
    val base = Presets.identity("win-firefox") ?: Presets.default().identity
    val custom = base.copy(id = "win-edge-like", name = "Windows · Edge-like", screen = base.screen?.copy(dpr = 1.25))
    SurfacePreviewFrame(theme) {
        Column {
            ScreenTopBar("Personas", onNav = {})
            Box(Modifier.fillMaxSize()) {
                PersonaListContent(
                    tab = 0, onTab = {},
                    identities = Presets.identities() + custom, regions = Presets.regions(),
                    isPresetIdentity = { Presets.identity(it.id) != null }, isPresetRegion = { Presets.region(it.id) != null },
                    onDuplicateIdentity = {}, onEditIdentity = {}, onDuplicateRegion = {}, onEditRegion = {},
                )
                HatchExtendedFab("New identity", onClick = {}, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
            }
        }
    }
}

@Preview(name = "Persona editor · custom form, invalid cores", widthDp = 360, heightDp = 900)
@Composable
internal fun IdentityFormPreview(@PreviewParameter(SurfacePreviewThemes::class) theme: PreviewTheme) {
    val initial = IdentityDraft.from(Presets.identity("win-firefox") ?: Presets.default().identity, name = "Windows · Edge-like")
    SurfacePreviewFrame(theme) {
        Column {
            // The mockup's bar: X, "Edit identity", Save disabled (40 %) while the cores field is invalid.
            ScreenTopBar("Edit identity", onNav = {}, nav = NavIcon.Close, actions = { TopBarPrimaryButton("Save", onClick = {}, enabled = false) })
            IdentityFormContent(initial = initial, draft = initial.copy(hardwareConcurrency = "128", dpr = "1.25"), saveError = null, onChange = {}, onDelete = {})
        }
    }
}

@Preview(name = "Persona editor · region form", widthDp = 360, heightDp = 760)
@Composable
internal fun RegionFormPreview(@PreviewParameter(SurfacePreviewThemes::class) theme: PreviewTheme) {
    val initial = RegionDraft.from(Presets.regions().last())
    SurfacePreviewFrame(theme) {
        RegionFormContent(initial = initial, draft = initial, saveError = null, onChange = {}, onDelete = null)
    }
}
