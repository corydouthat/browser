package app.hatch.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hatch.Components
import app.hatch.sitepermissions.SitePermissionRows
import app.hatch.ui.rules.RulesScaffold
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
 * Per-origin site permissions from `Components.sitePermissionsStorage` (the same `GeckoSitePermissionsStorage`
 * instance `SitePermissionsFeature` writes): the current site first, then every stored origin, each permission
 * as Ask / Allow / Block, "Clear" per origin and "Clear all". Reads and writes run on IO (`SitePermissionsDao` is
 * blocking); `GeckoSitePermissionsStorage` hops to Main for the Gecko part itself.
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

    val current = currentOrigin?.let { o -> all.firstOrNull { it.origin == o } }

    RulesScaffold(
        title = "Site permissions",
        onBack = onClose,
        snackbar = snackbar,
        actions = { if (all.isNotEmpty()) TextButton(onClick = { confirmClearAll = true }) { Text("Clear all") } },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            if (persona.region.geolocation != null) {
                item {
                    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Text(
                            "Location is answered by the persona (${persona.region.name}): pages get the fake position without a prompt, " +
                                "and a Location decision here has no effect until a region without geolocation is active.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
            if (currentOrigin != null) {
                item {
                    Text("Current site", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 6.dp))
                    if (current != null) {
                        OriginCard(
                            sp = current,
                            expanded = true,
                            onSet = { p, s -> write { storage.update(SitePermissionRows.withStatus(current, p, s), private = false) } },
                            onClear = { write { storage.remove(current, private = false) } },
                        )
                    } else {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(SitePermissionRows.displayOrigin(currentOrigin), style = MaterialTheme.typography.bodyLarge)
                                Text("No saved decisions. They appear after you answer a permission prompt, or set them now.", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(
                                    onClick = { write { storage.save(SitePermissions(origin = currentOrigin), request = null, private = false) } },
                                    modifier = Modifier.padding(top = 6.dp),
                                ) { Text("Set permissions for this site") }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("All sites (${all.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
                    if (loading) CircularProgressIndicator(Modifier.height(20.dp))
                }
                error?.let { Text("Could not read permissions: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (!loading && all.isEmpty() && error == null) {
                    Text("No site has a saved permission decision yet.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(all.filter { it.origin != currentOrigin }, key = { it.origin }) { sp ->
                OriginCard(
                    sp = sp,
                    expanded = false,
                    onSet = { p, s -> write { storage.update(SitePermissionRows.withStatus(sp, p, s), private = false) } },
                    onClear = { write { storage.remove(sp, private = false) } },
                )
                Spacer(Modifier.height(8.dp))
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Clear all site permissions?") },
            text = { Text("Every site will ask again (Gecko's stored content permissions are cleared too).") },
            confirmButton = { Button(onClick = { confirmClearAll = false; write { storage.removeAll() } }) { Text("Clear all") } },
            dismissButton = { TextButton(onClick = { confirmClearAll = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun OriginCard(
    sp: SitePermissions,
    expanded: Boolean,
    onSet: (Permission, Status) -> Unit,
    onClear: () -> Unit,
) {
    var open by remember(sp.origin) { mutableStateOf(expanded) }
    val rows = if (open) SitePermissionRows.rows(sp) else SitePermissionRows.decided(sp)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    SitePermissionRows.displayOrigin(sp.origin),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { open = !open }) { Text(if (open) "Less" else "All") }
                TextButton(onClick = onClear) { Text("Clear") }
            }
            if (rows.isEmpty()) {
                Text("Every permission set to Ask.", style = MaterialTheme.typography.bodySmall)
            }
            rows.forEach { row ->
                Text(row.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                val options = if (row.autoplay) listOf(Status.ALLOWED, Status.BLOCKED) else listOf(Status.NO_DECISION, Status.ALLOWED, Status.BLOCKED)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { i, s ->
                        SegmentedButton(
                            selected = row.status == s,
                            onClick = { if (row.status != s) onSet(row.permission, s) },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) { Text(SitePermissionRows.statusLabel(s)) }
                    }
                }
            }
        }
    }
}
