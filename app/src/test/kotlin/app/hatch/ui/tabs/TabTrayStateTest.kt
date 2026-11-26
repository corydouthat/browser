package app.hatch.ui.tabs

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TabTrayStateTest {
    @Test
    fun `long press enters select mode and toggles, an empty selection stays in select mode`() {
        var s = TabTrayState()
        assertFalse(s.selectMode)
        s = s.toggle("a")
        assertTrue(s.selectMode)
        assertEquals(setOf("a"), s.selectedIds)
        s = s.toggle("b")
        assertEquals(2, s.selectedCount)
        s = s.toggle("a")
        assertEquals(setOf("b"), s.selectedIds)
        s = s.toggle("b")
        assertTrue(s.selectMode, "UXP-07: un-toggling the last tab keeps select mode (Fenix Mode.Select(emptySet()))")
        assertEquals(0, s.selectedCount)
        assertFalse(s.exitSelect().selectMode)
    }

    /** UXP-07: "Select tabs" enters select mode with nothing selected. */
    @Test
    fun `select tabs starts empty`() {
        val s = TabTrayState().enterSelect()
        assertTrue(s.selectMode)
        assertEquals(0, s.selectedCount)
        val toggled = s.toggle("a").toggle("a")
        assertTrue(toggled.selectMode)
        assertEquals(0, toggled.selectedCount)
        assertEquals(setOf("x"), TabTrayState().enterSelect("x").selectedIds)
    }

    @Test
    fun `retain drops closed tabs and keeps the mode`() {
        val s = TabTrayState().enterSelect("a").toggle("b").toggle("c")
        assertEquals(setOf("a", "c"), s.retain(listOf("a", "c", "d")).selectedIds)
        val emptied = s.retain(listOf("x"))
        assertTrue(emptied.selectMode)
        assertEquals(0, emptied.selectedCount)
        assertEquals(TabTrayState(), TabTrayState().retain(listOf("a")))
        assertEquals(TabTrayState(), s.exitSelect())
    }

    /** PERF-08: the thumbnail request follows the card width, not a fixed 240 dp. */
    @Test
    fun `thumbnail size follows the grid geometry`() {
        // 360 dp phone, 2 columns, 16 dp padding ("Tab tray" mockup): (360 - 32 - 12) / 2 = 158 dp
        assertEquals(158f, TabTrayState.cardWidthDp(360f, 2))
        assertEquals(435, TabTrayState.thumbnailSizePx(360f, 2, 2.75f))
        assertEquals(415, TabTrayState.thumbnailSizePx(360f, 2, 2.625f))
        // 608 dp, 3 columns: (608 - 32 - 24) / 3 = 184 dp; 840 dp, 4 columns: (840 - 32 - 36) / 4 = 193 dp
        assertEquals(184f, TabTrayState.cardWidthDp(608f, 3))
        assertEquals(193f, TabTrayState.cardWidthDp(840f, 4))
        assertEquals(1, TabTrayState.thumbnailSizePx(10f, 2, 1f), "never below 1 px")
        assertEquals(0f, TabTrayState.cardWidthDp(360f, 0))
    }

    @Test
    fun `grid columns by width and snackbar copy`() {
        assertEquals(2, TabTrayState.columnsFor(360))
        assertEquals(2, TabTrayState.columnsFor(599))
        assertEquals(3, TabTrayState.columnsFor(600))
        assertEquals(3, TabTrayState.columnsFor(800))
        assertEquals(4, TabTrayState.columnsFor(840))
        assertEquals("Tab closed", TabTrayState.closedMessage(1))
        assertEquals("3 tabs closed", TabTrayState.closedMessage(3))
    }

    @Test
    fun `swipe fade is linear in the offset and never fully transparent`() {
        assertEquals(1f, swipeFadeAlpha(0f, 300f))
        assertEquals(0.5f, swipeFadeAlpha(150f, 300f))
        assertEquals(0.5f, swipeFadeAlpha(-150f, 300f))
        assertEquals(SwipeToDismiss.MIN_ALPHA, swipeFadeAlpha(400f, 300f))
        assertEquals(1f, swipeFadeAlpha(100f, 0f))
        assertEquals(1f, swipeFadeAlpha(Float.NaN, 300f))
    }
}
