package app.hatch.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hatch.Components
import app.hatch.rules.RuleValidation
import app.hatch.rules.SiteRule
import app.hatch.ui.settings.FormField
import app.hatch.ui.settings.NavIcon
import app.hatch.ui.settings.ScreenTopBar
import app.hatch.ui.settings.OutlinedPill
import app.hatch.ui.settings.PageNote
import app.hatch.ui.settings.SegmentedTabs
import app.hatch.ui.settings.SelectField
import app.hatch.ui.settings.TonalPill
import app.hatch.ui.settings.TopBarPrimaryButton
import app.hatch.ui.surfaces.PreviewTheme
import app.hatch.ui.surfaces.SurfacePreviewFrame
import app.hatch.ui.surfaces.SurfacePreviewThemes
import app.hatch.ui.theme.HatchTheme
import app.hatch.ui.theme.HatchType
import app.hatch.ui.theme.hatchFocusRing

/**
 * Create / edit one [SiteRule] (mockup "Site rule editor"): Name, Match (and Exclude) pattern chips with "+ Add", a
 * JavaScript / CSS segmented control over a dark code area, World and Run at, "Test on current site" (saves the rule
 * enabled and reloads the selected tab) and Delete. Validation is `RuleValidation.problems`: Save stays disabled while
 * there is a problem, and the problems list shows once the rule was edited.
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
    var rule by remember(base) { mutableStateOf(RuleValidation.normalize(base).copy(world = if (base.world == "MAIN") "MAIN" else "ISOLATED", runAt = base.runAt.takeIf { it in RuleValidation.RUN_AT } ?: "document_idle")) }
    var js by remember(base) { mutableStateOf(base.js.orEmpty()) }
    var css by remember(base) { mutableStateOf(base.css.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }

    val current = RuleValidation.normalize(rule.copy(js = js, css = css))
    val problems = RuleValidation.problems(current)
    val dirty = current != RuleValidation.normalize(base)

    RulesScaffold(
        title = if (existing != null) "Edit rule" else "New rule",
        onBack = onClose,
        snackbar = snackbar,
        nav = NavIcon.Close,
        actions = { TopBarPrimaryButton("Save", enabled = problems.isEmpty(), onClick = { repo.upsert(current); onClose() }) },
    ) { padding ->
        RuleEditorContent(
            rule = rule,
            js = js,
            css = css,
            problems = if (dirty || existing != null) problems else emptyList(),
            bundled = RuleValidation.isBundled(base) && existing != null,
            onRule = { rule = it },
            onJs = { js = it },
            onCss = { css = it },
            onTest = { if (problems.isEmpty()) onTestOnSite(current) },
            testEnabled = problems.isEmpty(),
            onDelete = if (existing != null) ({ confirmDelete = true }) else null,
            modifier = Modifier.padding(padding),
        )
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = HatchTheme.colors.sh,
            title = { Text("Delete \"${existing.name}\"?") },
            confirmButton = { TextButton(onClick = { repo.delete(existing.id); confirmDelete = false; onClose() }) { Text("Delete", color = HatchTheme.colors.dg) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = HatchTheme.colors.acT) } },
        )
    }
}

/** The editor body (stateless, previewable). [rule] carries name / patterns / world / run-at; the code is separate. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RuleEditorContent(
    rule: SiteRule,
    js: String,
    css: String,
    problems: List<String>,
    bundled: Boolean,
    onRule: (SiteRule) -> Unit,
    onJs: (String) -> Unit,
    onCss: (String) -> Unit,
    onTest: () -> Unit,
    testEnabled: Boolean,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val c = HatchTheme.colors
    var codeTab by remember { mutableStateOf(if (js.isBlank() && css.isNotBlank()) 1 else 0) }
    var patternEdit by remember { mutableStateOf<PatternEdit?>(null) }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (bundled) PageNote("Bundled example: edits are kept in rules.json like any other rule.")
        rule.source?.takeIf { it != RuleValidation.BUNDLED_SOURCE }?.let { PageNote("Imported from $it") }
        FormField("Name", rule.name, { onRule(rule.copy(name = it)) })
        PatternChips("Match", rule.matches) { i -> patternEdit = PatternEdit(exclude = false, index = i, value = rule.matches.getOrElse(i) { "" }) }
        PatternChips("Exclude", rule.excludeMatches) { i -> patternEdit = PatternEdit(exclude = true, index = i, value = rule.excludeMatches.getOrElse(i) { "" }) }
        SegmentedTabs(listOf("JavaScript", "CSS"), codeTab, { codeTab = it }, trackRadius = 12.dp, segmentRadius = 9.dp, segmentHeight = 36.dp, inset = 3.dp)
        CodeArea(
            value = if (codeTab == 0) js else css,
            onValueChange = if (codeTab == 0) onJs else onCss,
            label = if (codeTab == 0) "JavaScript" else "CSS",
            placeholder = if (codeTab == 0) "// userscript; GM_addStyle, GM_getValue/setValue, GM_log, GM_info" else "/* user stylesheet */",
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SelectField(
                "World", rule.world, RuleValidation.WORLDS.map { it to RuleText.worldLabel(it) }, { onRule(rule.copy(world = it)) },
                Modifier.weight(1f),
            )
            SelectField(
                "Run at", rule.runAt, RuleValidation.RUN_AT.map { it to RuleText.runAtLabel(it) }, { onRule(rule.copy(runAt = it)) },
                Modifier.weight(1f), valueStyle = HatchType.monoMedium,
            )
        }
        if (rule.world == "MAIN") {
            PageNote("Page world: runs in the page's own JS realm; GM values live in page localStorage (page-readable).")
        }
        if (problems.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                problems.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = c.dg) }
            }
        }
        TonalPill("Test on current site", onClick = onTest, enabled = testEnabled, modifier = Modifier.fillMaxWidth())
        if (onDelete != null) OutlinedPill("Delete rule", onClick = onDelete, modifier = Modifier.fillMaxWidth())
        Box(Modifier.heightIn(min = 16.dp))
    }

    patternEdit?.let { edit ->
        PatternDialog(
            edit = edit,
            onDismiss = { patternEdit = null },
            onDone = { value ->
                patternEdit = null
                onRule(
                    if (edit.exclude) rule.copy(excludeMatches = RuleText.editPattern(rule.excludeMatches, edit.index, value))
                    else rule.copy(matches = RuleText.editPattern(rule.matches, edit.index, value)),
                )
            },
        )
    }
}

private data class PatternEdit(val exclude: Boolean, val index: Int, val value: String)

/** Mono r8 surfaceContainer chips (an invalid pattern gets the error ring) and an outlined "+ Add". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatternChips(label: String, patterns: List<String>, onEdit: (index: Int) -> Unit) {
    val c = HatchTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.mu, modifier = Modifier.padding(horizontal = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            patterns.forEachIndexed { i, p ->
                val valid = RuleValidation.isValidMatchPattern(p)
                ChipBox(
                    Modifier
                        .hatchFocusRing(shape)
                        .clip(shape)
                        .background(c.s1)
                        .then(if (valid) Modifier else Modifier.border(2.dp, c.dg, shape))
                        .clickable(role = Role.Button, onClickLabel = "Edit pattern") { onEdit(i) },
                ) { Text(p, style = HatchType.mono, color = c.ink) }
            }
            ChipBox(
                Modifier
                    .hatchFocusRing(shape)
                    .clip(shape)
                    .border(1.dp, c.ln2, shape)
                    .clickable(role = Role.Button) { onEdit(patterns.size) }
                    .semantics { contentDescription = "Add $label pattern" },
            ) { Text("+ Add", style = MaterialTheme.typography.bodyMedium, color = c.mu) }
        }
    }
}

@Composable
private fun ChipBox(modifier: Modifier, content: @Composable () -> Unit) {
    Box(Modifier.minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
        Box(modifier.heightIn(min = 32.dp).padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun PatternDialog(edit: PatternEdit, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var value by remember(edit) { mutableStateOf(edit.value) }
    val valid = value.isBlank() || RuleValidation.isValidMatchPattern(value)
    val isNew = edit.value.isEmpty()
    val c = HatchTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.sh,
        title = { Text(if (edit.exclude) "Exclude pattern" else "Match pattern") },
        text = {
            FormField(
                "Pattern", value, { value = it }, valueStyle = HatchType.monoLarge,
                error = if (valid) null else "Not a match pattern, e.g. *://*.example.com/*", placeholder = "*://*.example.com/*",
            )
        },
        confirmButton = {
            TextButton(onClick = { onDone(value) }, enabled = valid && (value.isNotBlank() || !isNew)) {
                Text(if (value.isBlank() && !isNew) "Remove" else "Done", color = c.acT)
            }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = { onDone("") }) { Text("Remove", color = c.dg) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = c.acT) }
            }
        },
    )
}

/** The design keeps the code area dark in both themes (#16171A, text #D6D8DC, mono 11.5/1.65, r14, min 180 dp). */
private val CodeBackground = Color(0xFF16171A)
private val CodeText = Color(0xFFD6D8DC)
private val CodeTextMuted = Color(0xFF8B8E94)
private val CodeStyle = HatchType.mono.copy(fontSize = 11.5.sp, lineHeight = 19.sp, color = CodeText)

@Composable
private fun CodeArea(value: String, onValueChange: (String) -> Unit, label: String, placeholder: String) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = CodeStyle,
        cursorBrush = SolidColor(CodeText),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp)
            .semantics { contentDescription = label },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CodeBackground)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (value.isEmpty()) Text(placeholder, style = CodeStyle.copy(color = CodeTextMuted))
                inner()
            }
        },
    )
}

@Preview(name = "Rule editor", widthDp = 360, heightDp = 860)
@Composable
internal fun RuleEditorPreview(@PreviewParameter(SurfacePreviewThemes::class) theme: PreviewTheme) {
    val rule = SiteRule("r1", "Docs: desktop editor", listOf("*://docs.google.com/*"), world = "MAIN", runAt = "document_start", enabled = true)
    val js = "// run-at document-start\nObject.defineProperty(navigator,\n  'maxTouchPoints', { get: () => 0 });\ndocument.documentElement\n  .classList.add('desktop');"
    SurfacePreviewFrame(theme) {
        Column {
            ScreenTopBar("Edit rule", onNav = {}, nav = NavIcon.Close, actions = { TopBarPrimaryButton("Save", onClick = {}) })
            RuleEditorContent(rule, js, "", emptyList(), false, {}, {}, {}, {}, true, {})
        }
    }
}
