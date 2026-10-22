package app.hatch.ui.toolbar

import androidx.compose.ui.text.TextRange
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ported AC 158 field keeps AC's IME rule for accepting the inline suggestion on a cursor move, and its MPL-2.0
 * header (THIRD_PARTY.md).
 */
class InlineAutocompletePortTest {
    @Test
    fun `cursor gestures that accept the suggestion`() {
        // Cursor inside "wiki" moved to its end (no text change since): accept.
        assertTrue(shouldAcceptSuggestionOnSelectionUpdate(4, TextRange(2), 4, 4, 13, hasTextChangedSinceLastSelection = false))
        // Gboard's redundant setSelection(end, end): accept.
        assertTrue(shouldAcceptSuggestionOnSelectionUpdate(4, TextRange(4), 4, 4, 13, hasTextChangedSinceLastSelection = false))
        // HeliBoard moving into the visible suffix: accept.
        assertTrue(shouldAcceptSuggestionOnSelectionUpdate(4, TextRange(4), 9, 9, 13, hasTextChangedSinceLastSelection = false))
    }

    @Test
    fun `selection syncs after typing and ranges do not accept`() {
        assertFalse(shouldAcceptSuggestionOnSelectionUpdate(4, TextRange(4), 4, 4, 13, hasTextChangedSinceLastSelection = true))
        assertFalse(shouldAcceptSuggestionOnSelectionUpdate(4, TextRange(4), 0, 4, 13, hasTextChangedSinceLastSelection = false))
        assertFalse(shouldAcceptSuggestionOnSelectionUpdate(4, TextRange(4), 20, 20, 13, hasTextChangedSinceLastSelection = false))
        assertFalse(shouldAcceptSuggestionOnSelectionUpdate(4, TextRange(4), 9, 9, null, hasTextChangedSinceLastSelection = false))
    }

    @Test
    fun `port keeps the MPL header`() {
        val rel = "src/main/kotlin/app/hatch/ui/toolbar/InlineAutocompleteTextField.kt"
        val f = listOf(File(rel), File("app/$rel")).first { it.isFile }
        assertTrue(f.readText().startsWith("/* This Source Code Form is subject to the terms of the Mozilla Public"))
    }
}
