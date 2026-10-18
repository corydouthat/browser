package app.hatch.rules

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RulesUiStateTest {
    @Test
    fun `open pushes without duplicates, back pops, back on empty is not consumed`() {
        val s = RulesUiState()
        assertFalse(s.onBack(), "nothing open: back is not consumed")
        s.show(RulesUiState.Screen.SiteRules)
        assertEquals(RulesUiState.Screen.SiteRules, s.top)
        s.open(RulesUiState.Screen.RuleEditor("r1"))
        s.open(RulesUiState.Screen.RuleEditor("r1"))
        assertEquals(2, s.stack.size, "no duplicate push")
        assertTrue(s.onBack())
        assertEquals(RulesUiState.Screen.SiteRules, s.top)
        assertTrue(s.onBack())
        assertFalse(s.onBack())
        assertNull(s.top)
        assertFalse(s.isOpen)
    }

    @Test
    fun `show replaces the stack`() {
        val s = RulesUiState()
        s.open(RulesUiState.Screen.SiteRules)
        s.open(RulesUiState.Screen.RuleEditor(null))
        s.show(RulesUiState.Screen.SitePermissions)
        assertEquals(listOf<RulesUiState.Screen>(RulesUiState.Screen.SitePermissions), s.stack.toList())
        s.show(RulesUiState.Screen.PersonaEditor)
        assertEquals(RulesUiState.Screen.PersonaEditor, s.top)
        assertEquals(1, s.stack.size)
        s.closeAll()
        assertFalse(s.isOpen)
    }
}
