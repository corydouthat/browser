package app.hatch.logins

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import mozilla.components.concept.storage.Login
import mozilla.components.feature.prompts.concept.AutocompletePrompt
import mozilla.components.feature.prompts.concept.SelectablePromptView
import mozilla.components.feature.prompts.concept.ToggleablePrompt

/**
 * The `LoginDelegate.loginPickerView` contract (feature-prompts 158 `AutocompletePrompt<Login>` =
 * `SelectablePromptView<Login>` + `ToggleablePrompt`) as plain Compose state instead of AC's `LoginSelectBar`
 * view. `PromptFeature` wraps it in its internal `LoginPicker` (`PromptFeature.kt:403-409`), which sets
 * [selectablePromptListener] and drives [showPrompt] / [populate] / [hidePrompt]
 * (`LoginPicker.kt`: `handleSelectLoginRequest`, `onOptionSelect`, `dismissCurrentLoginSelect`).
 * `ui/logins/LoginPickerBar.kt` renders it. Opens expanded so one tap fills the form.
 */
class LoginPickerState : AutocompletePrompt<Login> {
    var logins: List<Login> by mutableStateOf(emptyList())
        private set

    override var isPromptDisplayed: Boolean by mutableStateOf(false)
        private set

    var expanded: Boolean by mutableStateOf(true)

    override var selectablePromptListener: SelectablePromptView.Listener<Login>? = null
    override var toggleablePromptListener: ToggleablePrompt.Listener? = null

    override fun populate(options: List<Login>) {
        logins = options
    }

    override fun showPrompt() {
        expanded = true
        isPromptDisplayed = true
        toggleablePromptListener?.onShown()
    }

    override fun hidePrompt() {
        isPromptDisplayed = false
        expanded = false
        logins = emptyList()
        toggleablePromptListener?.onHidden()
    }

    /** User tapped a row: `PromptFeature` confirms the prompt (GeckoView fills the form) and hides the bar. */
    fun select(login: Login) {
        selectablePromptListener?.onOptionSelect(login)
    }

    /** User tapped "Manage": `PromptFeature` dismisses the prompt and runs `LoginDelegate.onManageLogins`. */
    fun manage() {
        selectablePromptListener?.onManageOptions()
    }
}

/** Overlay state owned by the credentials feature (process-wide, survives Activity recreation). */
class LoginsUiState {
    val picker = LoginPickerState()

    var savedLoginsOpen: Boolean by mutableStateOf(false)

    /** SEC-1: the Saved logins gate (unlock / reveal / forget on ON_STOP) lives here so it is not plain composition state. */
    val lock = SavedLoginsLock()

    /** True when the Saved logins screen consumed the back press. */
    fun onBack(): Boolean {
        if (!savedLoginsOpen) return false
        savedLoginsOpen = false
        return true
    }
}
