/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

// Adapted from Fenix 158 (mobile/android/fenix/app/src/main/java/org/mozilla/fenix/compose/SwipeToDismiss.kt,
// tag FIREFOX_158_0_RELEASE): the state factory and the linear fade; the coloured background is not used.
// Listed in THIRD_PARTY.md.

package app.hatch.ui.tabs

import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.abs

object SwipeToDismiss {
    const val FULL_ALPHA = 1f
    const val MIN_ALPHA = 0.1f
}

/** One state per tab id; a new tab at the same grid slot gets a fresh state. */
@Composable
fun rememberSwipeToDismissBoxState(id: String): SwipeToDismissBoxState {
    val positionalThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    return remember(id) {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            positionalThreshold = positionalThreshold,
        )
    }
}

/** Fades the content linearly with the swipe offset (the M3 progress is tied to a fixed threshold). */
fun Modifier.swipeToDismissFade(state: SwipeToDismissBoxState) = graphicsLayer {
    val offset = try {
        if (state.dismissDirection == SwipeToDismissBoxValue.Settled) 0f else state.requireOffset()
    } catch (_: IllegalStateException) {
        0f
    }
    alpha = swipeFadeAlpha(offset = offset, width = size.width)
}

internal fun swipeFadeAlpha(offset: Float, width: Float): Float =
    if (width <= 0f || offset.isNaN()) {
        SwipeToDismiss.FULL_ALPHA
    } else {
        maxOf(SwipeToDismiss.MIN_ALPHA, SwipeToDismiss.FULL_ALPHA - (abs(offset) / width).coerceIn(0f, 1f))
    }
