package app.hatch.ui.rules

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hatch.Components
import app.hatch.rules.RuleValidation
import app.hatch.rules.SiteRule
import kotlinx.coroutines.launch

/**
 * Create / edit one [SiteRule]: name, match and exclude patterns (one per line), world (ISOLATED / MAIN), run-at,
 * JavaScript and CSS editors (monospace), enabled switch, Save, "Test on current site" (saves enabled and reloads
 * the selected tab) and Delete. Validation is `RuleValidation.problems` and shows inline before anything is saved.
 *
 * @param ruleId existing rule to edit, or null for a new one
 * @param draft pre-filled values for a new rule (import preview, duplicate)
 */
@Composable
fun RuleEditorScreen(
    components: Components,
    ruleId: String?,
    draft: SiteRule?,
    onClose: () -> Unit,
    onTestOnSite: (SiteRule) -> Unit,
) {
    val repo = components.rulesRepository
    val existing = remember(ruleId) { ruleId?.let { id -> repo.rules.value.firstOrNull { it.id == id } } }
    val base = remember(ruleId, draft) { existing ?: draft ?: SiteRule(id = SiteRule.newId(), name = "", matches = listOf("*://*/*"), js = null, css = null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var name by remember(base) { mutableStateOf(base.name) }
    var matches by remember(base) { mutableStateOf(base.matches.joinToString("\n")) }
    var excludes by remember(base) { mutableStateOf(base.excludeMatches.joinToString("\n")) }
    var world by remember(base) { mutableStateOf(if (base.world == "MAIN") "MAIN" else "ISOLATED") }
    var runAt by remember(base) { mutableStateOf(base.runAt.takeIf { it in RuleValidation.RUN_AT } ?: "document_idle") }
    var js by remember(base) { mutableStateOf(base.js.orEmpty()) }
    var css by remember(base) { mutableStateOf(base.css.orEmpty()) }
    var enabled by remember(base) { mutableStateOf(base.enabled) }
    var problems by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun current(): SiteRule = RuleValidation.normalize(
        base.copy(
            name = name,
            matches = RuleValidation.splitPatterns(matches),
            excludeMatches = RuleValidation.splitPatterns(excludes),
            js = js,
            css = css,
            world = world,
            runAt = runAt,
            enabled = enabled,
        ),
    )

    /** Validates; returns the rule to save or null after showing the problems. */
    fun validated(): SiteRule? {
        val rule = current()
        val p = RuleValidation.problems(rule)
        problems = p
        if (p.isNotEmpty()) scope.launch { snackbar.showSnackbar(p.first()) }
        return rule.takeIf { p.isEmpty() }
    }

    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

    RulesScaffold(
        title = if (existing != null) "Edit rule" else "New rule",
        onBack = onClose,
        snackbar = snackbar,
        actions = {
            if (existing != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete rule") }
            TextButton(onClick = { validated()?.let { repo.upsert(it); onClose() } }) { Text("Save") }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            if (RuleValidation.isBundled(base) && existing != null) {
                Text("Bundled example — edits are kept in rules.json like any other rule.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
            }
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(
                value = matches,
                onValueChange = { matches = it },
                label = { Text("Match patterns (one per line)") },
                supportingText = { Text("e.g. *://*.example.com/*, https://example.com/path/*, <all_urls>") },
                textStyle = mono,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = excludes,
                onValueChange = { excludes = it },
                label = { Text("Exclude patterns (optional)") },
                textStyle = mono,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            Text("JavaScript world", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                RuleValidation.WORLDS.forEachIndexed { i, w ->
                    SegmentedButton(selected = world == w, onClick = { world = w }, shape = SegmentedButtonDefaults.itemShape(i, RuleValidation.WORLDS.size)) { Text(w) }
                }
            }
            Text(
                if (world == "MAIN") "MAIN: runs in the page's own JS realm (can patch page globals); GM values live in page localStorage (page-readable)."
                else "ISOLATED: content-script sandbox with unsafeWindow; GM values are page-invisible (extension storage).",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )

            Text("Run at", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                RuleValidation.RUN_AT.forEachIndexed { i, r ->
                    SegmentedButton(selected = runAt == r, onClick = { runAt = r }, shape = SegmentedButtonDefaults.itemShape(i, RuleValidation.RUN_AT.size)) {
                        Text(r.removePrefix("document_"))
                    }
                }
            }

            OutlinedTextField(
                value = js,
                onValueChange = { js = it },
                label = { Text("JavaScript (userscript; GM subset available)") },
                textStyle = mono,
                minLines = 6,
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).padding(top = 16.dp),
            )
            OutlinedTextField(
                value = css,
                onValueChange = { css = it },
                label = { Text("CSS (user stylesheet)") },
                textStyle = mono,
                minLines = 4,
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(top = 12.dp),
            )

            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Enabled", style = MaterialTheme.typography.bodyLarge)
                    Text("Disabled rules are kept but never registered.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            if (problems.isNotEmpty()) {
                Column(Modifier.padding(top = 8.dp)) {
                    problems.forEach { Text("• $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { validated()?.let(onTestOnSite) }, modifier = Modifier.weight(1f)) { Text("Test on current site") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { validated()?.let { repo.upsert(it); onClose() } }, modifier = Modifier.weight(1f)) { Text("Save") }
            }
            Text(
                "\"Test on current site\" saves the rule enabled and reloads the selected tab.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${existing.name}\"?") },
            confirmButton = { Button(onClick = { repo.delete(existing.id); confirmDelete = false; onClose() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
