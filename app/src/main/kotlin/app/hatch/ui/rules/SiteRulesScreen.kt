package app.hatch.ui.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hatch.Components
import app.hatch.rules.RuleValidation
import app.hatch.rules.SiteRule

/**
 * Site rules list: every rule in `SiteRulesRepository` (the three bundled examples included, tagged "Bundled")
 * with an enable switch, tap to edit, overflow menu (duplicate / test on current site / delete), FAB for a new
 * rule and an "Import" action (.user.js / .user.css from a URL or the clipboard).
 */
@Composable
fun SiteRulesScreen(
    components: Components,
    onClose: () -> Unit,
    onEdit: (ruleId: String) -> Unit,
    onNew: (draft: SiteRule?) -> Unit,
    onTestOnSite: (SiteRule) -> Unit,
) {
    val repo = components.rulesRepository
    val rules by repo.rules.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var importOpen by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SiteRule?>(null) }

    RulesScaffold(
        title = "Site rules",
        onBack = onClose,
        snackbar = snackbar,
        actions = { TextButton(onClick = { importOpen = true }) { Text("Import") } },
        floatingActionButton = {
            FloatingActionButton(onClick = { onNew(null) }) { Icon(Icons.Default.Add, "New rule") }
        },
    ) { padding ->
        if (rules.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(48.dp))
                Text("No rules yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "A rule injects your JavaScript or CSS into matching pages (userscripts / userstyles). Tap + to write one or Import a .user.js file.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                items(rules, key = { it.id }) { rule ->
                    RuleRow(
                        rule = rule,
                        onToggle = { on -> repo.setEnabled(rule.id, on) },
                        onClick = { onEdit(rule.id) },
                        onDuplicate = { onNew(rule.copy(id = SiteRule.newId(), name = rule.name + " (copy)", enabled = false, source = null)) },
                        onTest = { onTestOnSite(rule) },
                        onDelete = { deleting = rule },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    deleting?.let { rule ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${rule.name}\"?") },
            text = { Text(if (RuleValidation.isBundled(rule)) "This bundled example is recreated only if rules.json is deleted." else "The rule and its code are removed from this device.") },
            confirmButton = { Button(onClick = { repo.delete(rule.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    if (importOpen) {
        ImportRuleDialog(
            components = components,
            onDismiss = { importOpen = false },
            onAdd = { rule ->
                repo.upsert(rule)
                importOpen = false
            },
            onEdit = { rule -> importOpen = false; onNew(rule) },
        )
    }
}

/** What the row says under the name: kind, world/run-at, first match patterns. */
internal fun ruleSummary(rule: SiteRule): String = buildString {
    val kinds = listOfNotNull(rule.js?.let { "JS" }, rule.css?.let { "CSS" })
    append(kinds.joinToString("+").ifEmpty { "empty" })
    if (rule.js != null) append(" · ").append(rule.world)
    append(" · ").append(rule.runAt.removePrefix("document_"))
    append(" · ").append(rule.matches.take(2).joinToString(", "))
    if (rule.matches.size > 2) append(" +${rule.matches.size - 2}")
}

@Composable
private fun RuleRow(
    rule: SiteRule,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDuplicate: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rule.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (RuleValidation.isBundled(rule)) {
                        Spacer(Modifier.width(8.dp))
                        AssistChip(onClick = onClick, label = { Text("Bundled", style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(24.dp))
                    }
                }
                Text(ruleSummary(rule), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                rule.source?.takeIf { it != RuleValidation.BUNDLED_SOURCE }?.let {
                    Text("from $it", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Switch(checked = rule.enabled, onCheckedChange = onToggle)
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Test on current site") }, onClick = { menu = false; onTest() })
                DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}
