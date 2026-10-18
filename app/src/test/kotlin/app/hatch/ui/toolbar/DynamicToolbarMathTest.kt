package app.hatch.ui.toolbar

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DynamicToolbarMathTest {
    @Test
    fun `deltas accumulate and clamp to the toolbar height`() {
        assertEquals(30f, DynamicToolbarMath.applyDelta(0f, 30f, 100f))
        assertEquals(100f, DynamicToolbarMath.applyDelta(90f, 30f, 100f))
        assertEquals(0f, DynamicToolbarMath.applyDelta(10f, -30f, 100f))
        assertEquals(0f, DynamicToolbarMath.applyDelta(50f, 10f, 0f))
        assertEquals(0f, DynamicToolbarMath.applyDelta(50f, Float.NaN, 100f))
    }

    @Test
    fun `idle snaps to the nearer edge`() {
        assertEquals(0f, DynamicToolbarMath.snapTarget(49f, 100f))
        assertEquals(100f, DynamicToolbarMath.snapTarget(50f, 100f))
        assertEquals(100f, DynamicToolbarMath.snapTarget(100f, 100f))
        assertEquals(0f, DynamicToolbarMath.snapTarget(30f, 0f))
    }

    /** ENG-03: locking (keyboard, edit, find, home, loading) never changes Gecko's reservation. */
    @Test
    fun `only the feature switch and fullscreen change the max height`() {
        val h = 200
        for (locked in listOf(false, true)) {
            assertEquals(h, DynamicToolbarPlan.maxHeight(featureOn = true, fullScreen = false, toolbarPx = h), "locked=$locked")
            assertEquals(h, DynamicToolbarPlan.engineConfig(featureOn = true, locked = locked, fullScreen = false, toolbarPx = h, hiddenPx = 0f).maxHeightPx)
        }
        assertEquals(0, DynamicToolbarPlan.maxHeight(featureOn = false, fullScreen = false, toolbarPx = h))
        assertEquals(0, DynamicToolbarPlan.maxHeight(featureOn = true, fullScreen = true, toolbarPx = h))
        assertEquals(0, DynamicToolbarPlan.maxHeight(featureOn = true, fullScreen = false, toolbarPx = 0))
        assertEquals(DynamicToolbarPlan.EngineConfig(0, 0), DynamicToolbarPlan.engineConfig(false, false, false, h, 80f))
        assertEquals(DynamicToolbarPlan.EngineConfig(0, 0), DynamicToolbarPlan.engineConfig(true, false, true, h, 80f))
    }

    @Test
    fun `clipping follows the hidden px while unlocked and is 0 while locked`() {
        assertEquals(-80, DynamicToolbarPlan.clipping(locked = false, hiddenPx = 80f))
        assertEquals(0, DynamicToolbarPlan.clipping(locked = true, hiddenPx = 80f))
        assertEquals(DynamicToolbarPlan.EngineConfig(200, -80), DynamicToolbarPlan.engineConfig(true, false, false, 200, 80f))
        assertEquals(DynamicToolbarPlan.EngineConfig(200, 0), DynamicToolbarPlan.engineConfig(true, true, false, 200, 80f))
    }

    /** The status bar never hides: the hideable height excludes it. */
    @Test
    fun `hideable height excludes the status bar inset`() {
        assertEquals(150, DynamicToolbarPlan.hideablePx(chromeHeightPx = 230, statusBarPx = 80))
        assertEquals(0, DynamicToolbarPlan.hideablePx(chromeHeightPx = 50, statusBarPx = 80))
        assertTrue(DynamicToolbarPlan.hideablePx(0, 0) == 0)
    }
}
