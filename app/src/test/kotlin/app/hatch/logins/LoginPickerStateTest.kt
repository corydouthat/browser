package app.hatch.logins

import mozilla.components.concept.storage.Login
import mozilla.components.feature.prompts.concept.SelectablePromptView
import mozilla.components.feature.prompts.concept.ToggleablePrompt
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `AutocompletePrompt<Login>` contract as AC's internal `LoginPicker` drives it (feature-prompts 158). */
class LoginPickerStateTest {
    private val a = Login(guid = "a", username = "cory", password = "p1", origin = "https://example.com")
    private val b = Login(guid = "b", username = "work", password = "p2", origin = "https://example.com")

    private class RecordingListener : SelectablePromptView.Listener<Login> {
        val selected = mutableListOf<Login>()
        var managed = 0
        override fun onOptionSelect(option: Login) { selected += option }
        override fun onManageOptions() { managed++ }
    }

    private class ToggleListener : ToggleablePrompt.Listener {
        val events = mutableListOf<String>()
        override fun onShown() { events += "shown" }
        override fun onHidden() { events += "hidden" }
    }

    @Test
    fun `hidden and empty until PromptFeature shows and populates it`() {
        val state = LoginPickerState()
        assertFalse(state.isPromptDisplayed)
        assertTrue(state.logins.isEmpty())
        assertNull(state.selectablePromptListener)
    }

    @Test
    fun `show then populate is the LoginPicker order and opens expanded`() {
        val state = LoginPickerState()
        val toggles = ToggleListener()
        state.toggleablePromptListener = toggles
        state.expanded = false
        state.showPrompt()
        state.populate(listOf(a, b))
        assertTrue(state.isPromptDisplayed)
        assertTrue(state.expanded, "opens expanded so one tap fills the form")
        assertEquals(listOf(a, b), state.logins)
        assertEquals(listOf("shown"), toggles.events)
    }

    @Test
    fun `hide clears the rows, collapses and notifies`() {
        val state = LoginPickerState()
        val toggles = ToggleListener()
        state.toggleablePromptListener = toggles
        state.showPrompt()
        state.populate(listOf(a))
        state.hidePrompt()
        assertFalse(state.isPromptDisplayed)
        assertFalse(state.expanded)
        assertTrue(state.logins.isEmpty())
        assertEquals(listOf("shown", "hidden"), toggles.events)
    }

    @Test
    fun `select and manage reach the listener PromptFeature installed`() {
        val state = LoginPickerState()
        val listener = RecordingListener()
        state.selectablePromptListener = listener
        state.showPrompt()
        state.populate(listOf(a, b))
        state.select(b)
        state.manage()
        assertEquals(listOf(b), listener.selected)
        assertEquals(1, listener.managed)
    }

    @Test
    fun `select and manage without a listener are no-ops`() {
        val state = LoginPickerState()
        state.populate(listOf(a))
        state.select(a)
        state.manage()
        assertEquals(listOf(a), state.logins)
    }

    @Test
    fun `saved logins overlay back handling`() {
        val ui = LoginsUiState()
        assertFalse(ui.onBack())
        ui.savedLoginsOpen = true
        assertTrue(ui.onBack())
        assertFalse(ui.savedLoginsOpen)
        assertFalse(ui.onBack())
    }
}
