package app.hatch.rules

import androidx.compose.runtime.mutableStateListOf

/**
 * Overlay stack for the rules module's screens (site rules list → rule editor, site permissions, persona editor).
 * Lives in `Components.rulesUi`; rendered by `ui/rules/RulesOverlays.kt` above the chrome's overlays. `HatchApp`
 * calls [show] for `UiEvent.OpenSiteRules` / `OpenSitePermissions` / `OpenPersonaEditor` (and Settings calls it
 * directly); the Activity back callback and Esc call [onBack] first because these overlays are topmost.
 */
class RulesUiState {
    sealed interface Screen {
        data object SiteRules : Screen

        /** `ruleId == null` creates a new rule; [draft] pre-fills the editor (import preview, duplicate). */
        data class RuleEditor(val ruleId: String?, val draft: SiteRule? = null) : Screen
        data object SitePermissions : Screen
        data object PersonaEditor : Screen
    }

    val stack = mutableStateListOf<Screen>()

    val top: Screen? get() = stack.lastOrNull()
    val isOpen: Boolean get() = stack.isNotEmpty()

    fun open(screen: Screen) {
        if (stack.lastOrNull() == screen) return
        stack.add(screen)
    }

    /** Replaces the whole stack with [screen] (menu / settings entry points). */
    fun show(screen: Screen) {
        stack.clear()
        stack.add(screen)
    }

    /** True when a screen consumed the back press. */
    fun onBack(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.size - 1)
        return true
    }

    fun closeAll() = stack.clear()
}
