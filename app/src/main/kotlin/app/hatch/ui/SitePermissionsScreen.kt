package app.hatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import app.hatch.Components
import app.hatch.sitepermissions.SitePermissionRows
import app.hatch.ui.logins.LoginFormat
import app.hatch.ui.rules.RulesScaffold
import app.hatch.ui.settings.CardDivider
import app.hatch.ui.settings.CardRowFocusShape
import app.hatch.ui.settings.GroupedCard
import app.hatch.ui.settings.InfoBox
import app.hatch.ui.settings.PageNote
import app.hatch.ui.settings.ScreenIcons
import app.hatch.ui.settings.ScreenTopBar
import app.hatch.ui.settings.TonalPill
import app.hatch.ui.settings.TopBarTextAction
import app.hatch.ui.surfaces.PreviewTheme
import app.hatch.ui.surfaces.SurfacePreviewFrame
import app.hatch.ui.surfaces.SurfacePreviewThemes
import app.hatch.ui.theme.HatchTheme
import app.hatch.ui.theme.hatchFocusRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.concept.engine.permission.SitePermissions
import mozilla.components.concept.engine.permission.SitePermissions.Status
import mozilla.components.concept.engine.permission.SitePermissionsStorage.Permission
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.components.support.ktx.kotlin.getOrigin

/**
 * Per-origin site permissions (mockup "Site permissions") from `Components.sitePermissionsStorage` (the same
 * `GeckoSitePermissionsStorage` instance `SitePermissionsFeature` writes): the current site first, expanded, with a
 * row per permission and an Allow / Ask / Block pill that opens a menu; every other stored origin as a summary row
 * ("Location allowed") that expands in place. "Clear" per origin and "Clear all" (error colour) in the top bar.
 * Reads and writes run on IO (`SitePermissionsDao` is blocking); `GeckoSitePermissionsStorage` hops to Main for the
 * Gecko part itself.
 */
@Composable
fun SitePermissionsScreen(components: Components, onClose: () -> Unit) {
    val storage = components.sitePermissionsStorage
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val tab by components.store.observeAsComposableState { it.selectedTab }
    val persona by components.personaManager.current.collectAsState()
    val currentOrigin = remember(tab?.content?.url) { tab?.content?.url?.takeIf { it.startsWith("http") }?.getOrigin() }

    var all by remember { mutableStateOf<List<SitePermissions>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var confirmClearAll by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        loading = true
        runCatching { withContext(Dispatchers.IO) { storage.all() } }
            .onSuccess { all = SitePermissionRows.sorted(it); error = null }
            .onFailure { error = it.message ?: it.toString() }
        loading = false
    }

    fun write(block: suspend () -> Unit) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onFailure { snackbar.showSnackbar("Failed: ${it.message ?: it.javaClass.simpleName}") }
            refresh++
        }
    }

    RulesScaffold(
        title = "Site permissions",
        onBack = onClose,
        snackbar = snackbar,
        actions = { if (all.isNotEmpty()) TopBarTextAction("Clear all", onClick = { confirmClearAll = true }, color = HatchTheme.colors.dg) },
    ) { padding ->
        SitePermissionsContent(
            all = all,
            currentOrigin = currentOrigin,
            loading = loading,
            error = error,
            locationNotice = persona.region.geolocation?.let {
                "Location is answered by the persona (${persona.region.name}): pages get the fake position without a prompt, " +
                    "and a Location decision here has no effect until a region without geolocation is active."
            },
            onSet = { sp, p, s -> write { storage.update(SitePermissionRows.withStatus(sp, p, s), private = false) } },
            onClear = { sp -> write { storage.remove(sp, private = false) } },
            onCreateForCurrent = { origin -> write { storage.save(SitePermissions(origin = origin), request = null, private = false) } },
            modifier = Modifier.padding(padding),
        )
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            containerColor = HatchTheme.colors.sh,
            title = { Text("Clear all site permissions?") },
            text = { Text("Every site will ask again (Gecko's stored content permissions are cleared too).") },
            confirmButton = { TextButton(onClick = { confirmClearAll = false; write { storage.removeAll() } }) { Text("Clear all", color = HatchTheme.colors.dg) } },
            dismissButton = { TextButton(onClick = { confirmClearAll = false }) { Text("Cancel", color = HatchTheme.colors.acT) } },
        )
    }
}

/** The list (stateless apart from which origins are expanded; previewable). */
@Composable
internal fun SitePermissionsContent(
    all: List<SitePermissions>,
    currentOrigin: String?,
    loading: Boolean,
    error: String?,
    locationNotice: String?,
    onSet: (SitePermissions, Permission, Status) -> Unit,
    onClear: (SitePermissions) -> Unit,
    onCreateForCurrent: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HatchTheme.colors
    val current = currentOrigin?.let { o -> all.firstOrNull { it.origin == o } }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (locationNotice != null) item(key = "notice") { InfoBox(locationNotice) }
        if (currentOrigin != null) {
            item(key = "current") {
                if (current != null) {
                    OriginSection(current, onSet = { p, s -> onSet(current, p, s) }, onClear = { onClear(current) })
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OriginHeader(currentOrigin, onClear = null)
                        GroupedCard {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("No saved decisions. They appear after you answer a permission prompt, or set them now.", style = MaterialTheme.typography.bodyMedium, color = c.mu)
                                TonalPill("Set permissions for this site", onClick = { onCreateForCurrent(currentOrigin) }, container = c.s2, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
        if (loading && all.isEmpty()) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.ac) }
            }
        }
        error?.let { item(key = "error") { Text("Could not read permissions: $it", color = c.dg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp)) } }
        if (!loading && all.isEmpty() && error == null && currentOrigin == null) {
            item(key = "empty") { PageNote("No site has a saved permission decision yet.", Modifier.padding(top = 8.dp)) }
        }
        val others = all.filter { it.origin != currentOrigin }
        if (others.isNotEmpty()) item(key = "gap") { Box(Modifier.heightIn(min = 0.dp)) }
        items(others, key = { it.origin }) { sp ->
            if (sp.origin in expanded) {
                OriginSection(
                    sp,
                    onSet = { p, s -> onSet(sp, p, s) },
                    onClear = { onClear(sp) },
                    onCollapse = { expanded = expanded - sp.origin },
                )
            } else {
                OriginSummaryRow(sp) { expanded = expanded + sp.origin }
            }
        }
        item(key = "end") { Box(Modifier.heightIn(min = 32.dp)) }
    }
}

/** The 20 dp r5 colour tile standing in for the site icon (same deterministic colour as the login avatar). */
@Composable
private fun SiteTile(origin: String) {
    Box(Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)).background(Color(LoginFormat.avatarColor(origin))))
}

@Composable
private fun OriginHeader(origin: String, onClear: (() -> Unit)?, onCollapse: (() -> Unit)? = null) {
    val c = HatchTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                if (onCollapse != null) {
                    Modifier.hatchFocusRing(RoundedCornerShape(12.dp), inside = true).clickable(role = Role.Button, onClickLabel = "Collapse", onClick = onCollapse)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SiteTile(origin)
        Text(
            SitePermissionRows.displayHost(origin),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = c.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (onClear != null) TopBarTextAction("Clear", onClick = onClear)
        if (onCollapse != null) Icon(ScreenIcons.ChevronUp, contentDescription = null, tint = c.mu, modifier = Modifier.size(20.dp))
    }
}

/** An origin's header ("Clear") and its card with every permission row. */
@Composable
private fun OriginSection(sp: SitePermissions, onSet: (Permission, Status) -> Unit, onClear: () -> Unit, onCollapse: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OriginHeader(sp.origin, onClear, onCollapse)
        GroupedCard {
            SitePermissionRows.rows(sp).forEachIndexed { i, row ->
                if (i > 0) CardDivider()
                PermissionRow(row) { s -> if (s != row.status) onSet(row.permission, s) }
            }
        }
    }
}

@Composable
private fun PermissionRow(row: SitePermissionRows.Row, onSelect: (Status) -> Unit) {
    val c = HatchTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(permissionIcon(row.permission), contentDescription = null, tint = c.mu, modifier = Modifier.size(20.dp))
        Text(row.label, style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f))
        ValuePill(row, onSelect)
    }
}

/** Allow = primaryContainer, Ask = surfaceContainerHighest, Block = the error-container tint; tap opens the choices. */
@Composable
private fun ValuePill(row: SitePermissionRows.Row, onSelect: (Status) -> Unit) {
    val c = HatchTheme.colors
    var open by remember { mutableStateOf(false) }
    val (bg, fg) = when (row.status) {
        Status.ALLOWED -> c.tb to c.ti
        Status.BLOCKED -> c.dgContainer to c.dg
        Status.NO_DECISION -> c.s2 to c.ink
    }
    val label = SitePermissionRows.pillLabel(row.status)
    val pill = RoundedCornerShape(16.dp)
    val interaction = remember { MutableInteractionSource() }
    Box {
        // A11Y-08: the clickable (and its semantics bounds) is the 48 dp box; the 32 dp pill only draws, and shows the
        // ripple through the shared interaction source.
        Box(
            Modifier
                .minimumInteractiveComponentSize()
                .hatchFocusRing(pill)
                .clickable(interactionSource = interaction, indication = null, role = Role.DropdownList, onClickLabel = "Change") { open = true }
                .semantics {
                    contentDescription = row.label
                    stateDescription = label
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .heightIn(min = 32.dp)
                    .clip(pill)
                    .background(bg)
                    .indication(interaction, ripple())
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
                Icon(ScreenIcons.ChevronDownBold, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SitePermissionRows.options(row).forEach { s ->
                DropdownMenuItem(
                    text = { Text(SitePermissionRows.pillLabel(s)) },
                    onClick = { open = false; onSelect(s) },
                    trailingIcon = if (s == row.status) { { Icon(ScreenIcons.Check, contentDescription = "Selected", modifier = Modifier.size(18.dp)) } } else null,
                )
            }
        }
    }
}

/** A collapsed origin: tile, host, summary ("Location allowed"), chevron; tap expands it in place. */
@Composable
private fun OriginSummaryRow(sp: SitePermissions, onExpand: () -> Unit) {
    val c = HatchTheme.colors
    GroupedCard {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .hatchFocusRing(CardRowFocusShape, inside = true)
                .clickable(role = Role.Button, onClickLabel = "Show permissions", onClick = onExpand)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SiteTile(sp.origin)
            Column(Modifier.weight(1f)) {
                Text(SitePermissionRows.displayHost(sp.origin), style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(SitePermissionRows.summary(sp), style = MaterialTheme.typography.bodyMedium, color = c.mu)
            }
            Icon(ScreenIcons.ChevronDown, contentDescription = null, tint = c.mu, modifier = Modifier.size(20.dp))
        }
    }
}

private fun permissionIcon(p: Permission): ImageVector = when (p) {
    Permission.LOCATION -> ScreenIcons.Location
    Permission.NOTIFICATION -> ScreenIcons.Notifications
    Permission.CAMERA -> ScreenIcons.Camera
    Permission.MICROPHONE -> ScreenIcons.Microphone
    Permission.AUTOPLAY_AUDIBLE -> ScreenIcons.Autoplay
    Permission.AUTOPLAY_INAUDIBLE -> ScreenIcons.AutoplayMuted
    Permission.LOCAL_STORAGE -> ScreenIcons.Storage
    Permission.MEDIA_KEY_SYSTEM_ACCESS -> ScreenIcons.Drm
    Permission.STORAGE_ACCESS -> ScreenIcons.Cookie
    Permission.LOCAL_DEVICE_ACCESS -> ScreenIcons.Device
    Permission.LOCAL_NETWORK_ACCESS -> ScreenIcons.Network
    Permission.BLUETOOTH -> ScreenIcons.Bluetooth
}

@Preview(name = "Site permissions", widthDp = 360, heightDp = 1100)
@Composable
internal fun SitePermissionsPreview(@PreviewParameter(SurfacePreviewThemes::class) theme: PreviewTheme) {
    val meet = SitePermissions(
        origin = "https://meet.google.com:443", camera = Status.ALLOWED, microphone = Status.ALLOWED,
        notification = Status.BLOCKED, localStorage = Status.ALLOWED, savedAt = 1L,
    )
    val maps = SitePermissions(origin = "https://maps.google.com:443", location = Status.ALLOWED, savedAt = 1L)
    val chase = SitePermissions(origin = "https://secure.chase.com:443", notification = Status.BLOCKED, savedAt = 1L)
    SurfacePreviewFrame(theme) {
        Column {
            ScreenTopBar("Site permissions", onNav = {}, actions = { TopBarTextAction("Clear all", onClick = {}, color = HatchTheme.colors.dg) })
            SitePermissionsContent(listOf(maps, meet, chase), meet.origin, false, null, null, { _, _, _ -> }, {}, {})
        }
    }
}
