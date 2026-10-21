package app.hatch.ui.rules

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import app.hatch.Components
import app.hatch.rules.RuleValidation
import app.hatch.rules.SiteRule
import app.hatch.ui.settings.CardDivider
import app.hatch.ui.settings.CardRowFocusShape
import app.hatch.ui.settings.FabClearance
import app.hatch.ui.settings.GroupedCard
import app.hatch.ui.settings.HatchExtendedFab
import app.hatch.ui.settings.PageNote
import app.hatch.ui.settings.ScreenTopBar
import app.hatch.ui.settings.TopBarTextAction
import app.hatch.ui.settings.hatchSwitchColors
import app.hatch.ui.surfaces.PreviewTheme
import app.hatch.ui.surfaces.SurfacePreviewFrame
import app.hatch.ui.surfaces.SurfacePreviewThemes
import app.hatch.ui.theme.HatchTheme
import app.hatch.ui.theme.HatchType
import app.hatch.ui.theme.hatchFocusRing

/**
 * Site rules list (mockup "Site rules"): every rule in `SiteRulesRepository` (the bundled examples included, tagged
 * "Example") in one grouped card, each row with name, mono match patterns, kind and an enable switch. Tap edits;
 * long-press (or the TalkBack actions) offers Test on current site / Duplicate / Delete. "Import" in the top bar
 * (.user.js / .user.css from a URL or the clipboard), FAB "New rule".
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
        actions = { TopBarTextAction("Import", onClick = { importOpen = true }) },
        floatingActionButton = { HatchExtendedFab("New rule", onClick = { onNew(null) }) },
    ) { padding ->
        SiteRulesContent(
            rules = rules,
            onToggle = { rule, on -> repo.setEnabled(rule.id, on) },
            onEdit = { onEdit(it.id) },
            onDuplicate = { rule -> onNew(rule.copy(id = SiteRule.newId(), name = rule.name + " (copy)", enabled = false, source = null)) },
            onTest = onTestOnSite,
            onDelete = { deleting = it },
            modifier = Modifier.padding(padding),
        )
    }

    deleting?.let { rule ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = HatchTheme.colors.sh,
            title = { Text("Delete \"${rule.name}\"?") },
            text = { Text(if (RuleValidation.isBundled(rule)) "This bundled example is recreated only if rules.json is deleted." else "The rule and its code are removed from this device.") },
            confirmButton = { TextButton(onClick = { repo.delete(rule.id); deleting = null }) { Text("Delete", color = HatchTheme.colors.dg) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = HatchTheme.colors.acT) } },
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

@Composable
internal fun SiteRulesContent(
    rules: List<SiteRule>,
    onToggle: (SiteRule, Boolean) -> Unit,
    onEdit: (SiteRule) -> Unit,
    onDuplicate: (SiteRule) -> Unit,
    onTest: (SiteRule) -> Unit,
    onDelete: (SiteRule) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rules.isEmpty()) {
        Column(Modifier.fillMaxSize().then(modifier).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No rules yet", style = MaterialTheme.typography.titleMedium, color = HatchTheme.colors.ink)
            PageNote("A rule injects your JavaScript or CSS into matching pages (userscripts / userstyles). Tap New rule to write one or Import a .user.js file.")
        }
        return
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp)) {
        item(key = "rules") {
            GroupedCard {
                rules.forEachIndexed { i, rule ->
                    if (i > 0) CardDivider()
                    RuleRow(rule, { onToggle(rule, it) }, { onEdit(rule) }, { onDuplicate(rule) }, { onTest(rule) }, { onDelete(rule) })
                }
            }
        }
        item(key = "clearance") { FabClearance() }
    }
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
    val c = HatchTheme.colors
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .hatchFocusRing(CardRowFocusShape, inside = true)
                .combinedClickable(onClick = onClick, onLongClick = { menu = true }, onLongClickLabel = "More actions")
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Test on current site") { onTest(); true },
                        CustomAccessibilityAction("Duplicate") { onDuplicate(); true },
                        CustomAccessibilityAction("Delete") { onDelete(); true },
                    )
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(rule.name, style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(RuleText.patternLine(rule), style = HatchType.mono, color = c.mu, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(RuleText.kindLine(rule), style = MaterialTheme.typography.bodySmall, color = c.mu)
            }
            Switch(
                checked = rule.enabled,
                onCheckedChange = onToggle,
                colors = hatchSwitchColors(),
                modifier = Modifier.semantics { contentDescription = "Enable ${rule.name}" },
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Test on current site") }, onClick = { menu = false; onTest() })
            DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
            DropdownMenuItem(text = { Text("Delete", color = c.dg) }, onClick = { menu = false; onDelete() })
        }
    }
}

@Preview(name = "Site rules", widthDp = 360, heightDp = 760)
@Composable
internal fun SiteRulesPreview(@PreviewParameter(SurfacePreviewThemes::class) theme: PreviewTheme) {
    val rules = listOf(
        SiteRule("r1", "Docs: desktop editor", listOf("*://docs.google.com/*"), js = "void 0", enabled = true),
        SiteRule("r2", "Hide app-install banners", listOf("*://*/*"), css = "a{}", enabled = true),
        SiteRule("r3", "Maps: keep satellite toggle", listOf("*://maps.google.com/*"), js = "void 0"),
        SiteRule("r4", "Example: dark reader lite", listOf("*://*.wikipedia.org/*"), css = "a{}", source = RuleValidation.BUNDLED_SOURCE),
    )
    SurfacePreviewFrame(theme) {
        Column {
            ScreenTopBar("Site rules", onNav = {}, actions = { TopBarTextAction("Import", onClick = {}) })
            Box {
                SiteRulesContent(rules, { _, _ -> }, {}, {}, {}, {})
                HatchExtendedFab("New rule", onClick = {}, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
            }
        }
    }
}
