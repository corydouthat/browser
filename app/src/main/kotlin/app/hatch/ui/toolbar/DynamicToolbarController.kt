package app.hatch.ui.toolbar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import mozilla.components.concept.engine.EngineView

/**
 * Pure scroll-to-hide arithmetic for a top toolbar (JVM-tested). `hidden` is how many px of the toolbar are
 * currently off-screen, in `[0, max]`; scroll deltas come from `EngineView.verticalScrollDelta`
 * (`GeckoVerticalScrollListener`: positive while the page scrolls down).
 */
object DynamicToolbarMath {
    fun applyDelta(hidden: Float, delta: Float, max: Float): Float {
        if (max <= 0f || delta.isNaN()) return 0f
        return (hidden + delta).coerceIn(0f, max)
    }

    /** After an idle period the toolbar snaps to whichever edge is closer. */
    fun snapTarget(hidden: Float, max: Float): Float = if (max <= 0f || hidden < max / 2f) 0f else max
}

/**
 * ENG-03: the Gecko-side configuration as a pure function. Two inputs that used to be one:
 *
 * - [featureOn]: Settings → "Hide toolbar when scrolling" is on and the chrome is visible (not fullscreen). Only
 *   this (and [fullScreen]) decides `EngineView.setDynamicToolbarMaxHeight`, which Gecko answers with a viewport
 *   re-layout (`GeckoSession.setDynamicToolbarMaxHeight` → `nsWindow::UpdateDynamicToolbarMaxHeight`); it is set
 *   once per toolbar height, like Fenix's `initializeEngineView`.
 * - `locked`: keyboard, URL edit, find bar, home, crash or loading. A locked toolbar is simply expanded
 *   (`hidden = 0`, clipping 0) and ignores scroll deltas; the max height does not move, so a keyboard cycle costs
 *   the page one resize (the IME inset) instead of three.
 *
 * [toolbarPx] excludes the status-bar inset: the status bar is never scrolled away (the content is placed below
 * it), so Gecko's reservation equals what can actually hide.
 */
object DynamicToolbarPlan {
    data class EngineConfig(val maxHeightPx: Int, val clippingPx: Int)

    fun maxHeight(featureOn: Boolean, fullScreen: Boolean, toolbarPx: Int): Int =
        if (featureOn && !fullScreen && toolbarPx > 0) toolbarPx else 0

    fun clipping(locked: Boolean, hiddenPx: Float): Int = if (locked) 0 else -hiddenPx.toInt()

    fun engineConfig(featureOn: Boolean, locked: Boolean, fullScreen: Boolean, toolbarPx: Int, hiddenPx: Float): EngineConfig {
        val max = maxHeight(featureOn, fullScreen, toolbarPx)
        return EngineConfig(max, if (max == 0) 0 else clipping(locked, hiddenPx))
    }

    /** The toolbar px that can hide: the chrome column minus the status-bar inset it carries. */
    fun hideablePx(chromeHeightPx: Int, statusBarPx: Int): Int = (chromeHeightPx - statusBarPx).coerceAtLeast(0)
}

/**
 * Hatch's scroll-to-hide controller (Compose; no CoordinatorLayout). Mirrors what Fenix 158 does with
 * `EngineViewClippingBehavior` for a top toolbar: the engine view is laid out below the status bar at full height
 * and translated down by `toolbar - hidden`, the toolbar column is translated up by `hidden`, Gecko gets
 * `setDynamicToolbarMaxHeight(toolbar)` once per height while the feature is on and `setVerticalClipping(-hidden)`
 * as it moves, so fixed-bottom page elements stay visible. With the feature off (the default, Settings → "Hide
 * toolbar when scrolling") everything is static and Gecko's dynamic toolbar height is 0.
 */
class DynamicToolbarController internal constructor(initialHidden: Float = 0f) {
    private val _hidden = mutableFloatStateOf(initialHidden)

    /** Px of the toolbar currently hidden above the screen edge. */
    val hidden: State<Float> get() = _hidden

    internal fun set(value: Float) { _hidden.floatValue = value }

    fun expand() { _hidden.floatValue = 0f }
}

@Composable
fun rememberDynamicToolbar(
    engineView: EngineView,
    featureOn: Boolean,
    locked: Boolean,
    fullScreen: Boolean,
    toolbarHeightPx: Int,
): DynamicToolbarController {
    val controller = remember { DynamicToolbarController() }
    val max by rememberUpdatedState(toolbarHeightPx.toFloat())
    val isLocked by rememberUpdatedState(locked)
    val maxHeight = DynamicToolbarPlan.maxHeight(featureOn, fullScreen, toolbarHeightPx)

    // Gecko-side reservation: once per (feature, height); toggling it resets clipping to 0
    // (GeckoDisplay.setDynamicToolbarMaxHeight). Fullscreen is handled by MainActivity.fullScreenChanged too.
    LaunchedEffect(maxHeight) {
        if (maxHeight == 0) controller.expand()
        engineView.setDynamicToolbarMaxHeight(maxHeight)
        engineView.setVerticalClipping(0)
    }

    // A locked toolbar is expanded and stays so; the reservation above is untouched.
    LaunchedEffect(locked, maxHeight) {
        if (locked && maxHeight > 0) {
            controller.expand()
            engineView.setVerticalClipping(0)
        }
    }

    LaunchedEffect(maxHeight) {
        if (maxHeight <= 0) return@LaunchedEffect
        engineView.verticalScrollDelta.collectLatest { delta ->
            if (isLocked) return@collectLatest
            controller.set(DynamicToolbarMath.applyDelta(controller.hidden.value, delta, max))
            engineView.setVerticalClipping(DynamicToolbarPlan.clipping(false, controller.hidden.value))
            // collectLatest: a new delta cancels the pending snap; after ~120 ms of idle we settle on an edge.
            delay(SNAP_IDLE_MS)
            controller.set(DynamicToolbarMath.snapTarget(controller.hidden.value, max))
            engineView.setVerticalClipping(DynamicToolbarPlan.clipping(false, controller.hidden.value))
        }
    }

    return controller
}

private const val SNAP_IDLE_MS = 120L
