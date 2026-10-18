package app.hatch.ui

import app.hatch.ui.find.findCounter
import mozilla.components.browser.state.state.content.FindResultState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FindCounterTest {
    @Test
    fun `counter reads the last find result, 1-based`() {
        assertEquals("", findCounter("", listOf(FindResultState(2, 17, true))))
        assertEquals("", findCounter("abc", emptyList()))
        assertEquals("3/17", findCounter("abc", listOf(FindResultState(0, 5, true), FindResultState(2, 17, true))))
        assertEquals("0/0", findCounter("zzz", listOf(FindResultState(0, 0, true))))
        assertEquals("1/1", findCounter("a", listOf(FindResultState(0, 1, true))))
    }
}
