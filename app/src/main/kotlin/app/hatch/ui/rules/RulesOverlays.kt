package app.hatch.ui.rules

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import app.hatch.Components
import app.hatch.persona.Persona
import app.hatch.rules.RuleValidation
import app.hatch.rules.RulesUiState
import app.hatch.rules.SiteRule
import app.hatch.ui.SitePermissionsScreen
import app.hatch.ui.persona.PersonaEditorScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Renders the top of `Components.rulesUi`'s stack above the chrome's overlays: site rules list, rule editor,
 * site permissions, persona editor. Inserted into `HatchApp` by the rules module.
 *
 * @param onShowPage closes the chrome's overlays (Settings) so a reloaded page is visible
 */
@Composable
fun RulesOverlays(
    components: Components,
    snackbar: SnackbarHostState,
    onApplyPersona: (Persona) -> Unit,
    onShowPage: () -> Unit,
) {
    val ui = components.rulesUi
    val top = ui.top ?: return

    /** Saves [rule] enabled, closes every overlay and reloads the selected tab once the extension had time to register it. */
    fun testOnCurrentSite(rule: SiteRule) {
        val saved = RuleValidation.normalize(rule).copy(enabled = true)
        components.rulesRepository.upsert(saved)
        ui.closeAll()
        onShowPage()
        components.applicationScope.launch {
            // `rules` goes through the extension's serialised chain (contentScripts.register per rule); a reload
            // right away would race it. Half a second is comfortably above what the background needs.
            delay(RELOAD_DELAY_MS)
            components.sessionUseCases.reload()
            snackbar.showSnackbar("Rule \"${saved.name}\" enabled, page reloaded")
        }
    }

    when (top) {
        RulesUiState.Screen.SiteRules -> SiteRulesScreen(
            components = components,
            onClose = { ui.onBack() },
            onEdit = { id -> ui.open(RulesUiState.Screen.RuleEditor(id)) },
            onNew = { draft -> ui.open(RulesUiState.Screen.RuleEditor(null, draft)) },
            onTestOnSite = ::testOnCurrentSite,
        )
        is RulesUiState.Screen.RuleEditor -> RuleEditorScreen(
            components = components,
            ruleId = top.ruleId,
            draft = top.draft,
            onClose = { ui.onBack() },
            onTestOnSite = ::testOnCurrentSite,
        )
        RulesUiState.Screen.SitePermissions -> SitePermissionsScreen(
            components = components,
            onClose = { ui.onBack() },
        )
        RulesUiState.Screen.PersonaEditor -> PersonaEditorScreen(
            components = components,
            onClose = { ui.onBack() },
            onApplyPersona = onApplyPersona,
        )
    }
}

private const val RELOAD_DELAY_MS = 500L
