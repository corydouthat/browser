package app.hatch.ui.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.Density

/**
 * The design's focus ring: a 2 dp [HatchColors.ac] stroke drawn 2 dp outside the element's bounds in [shape],
 * shown only while the element has focus **and** the window is in keyboard input mode (hardware keyboard / D-pad),
 * never for touch.
 *
 * Place it **before** the focus target it decorates (`clickable`, `focusable`, `toggleable`, a text field), e.g.
 * `Modifier.hatchFocusRing(HatchShapes.Pill).clickable { … }`: `onFocusChanged` reports the first focus target
 * below it in the chain. The ring draws outside the bounds, so the parent must not clip that 4 dp margin.
 *
 * [inside] draws the same ring 2 dp *inside* the bounds instead (corners shrink concentrically): for rows of a
 * grouped card, which clips to its r20 corners and would cut an outside ring off.
 */
fun Modifier.hatchFocusRing(shape: Shape, color: Color = Color.Unspecified, inside: Boolean = false): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val inputModeManager = LocalInputModeManager.current
    val ring = color.takeOrElse { LocalHatchColors.current.ac }
    this
        .onFocusChanged { focused = it.isFocused }
        .drawWithContent {
            drawContent()
            // inputMode is snapshot state: the ring appears/disappears when the user switches to/from the keyboard.
            if (focused && inputModeManager.inputMode == InputMode.Keyboard) {
                val stroke = HatchDimens.FocusRing.toPx()
                val inset = (HatchDimens.FocusRingOffset.toPx() + stroke / 2) * if (inside) -1 else 1
                val ringSize = Size(size.width + inset * 2, size.height + inset * 2)
                val outline = concentric(shape, size, inset, this).createOutline(ringSize, layoutDirection, this)
                translate(-inset, -inset) {
                    drawOutline(outline, ring, style = Stroke(stroke))
                }
            }
        }
}

/**
 * A rounded shape grown by [inset] (negative: shrunk) keeps its corners concentric with the element (radius + inset); other shapes are
 * drawn at the grown size as they are.
 */
private fun concentric(shape: Shape, size: Size, inset: Float, density: Density): Shape {
    if (shape !is RoundedCornerShape) return shape
    fun grow(c: CornerSize) = CornerSize((c.toPx(size, density) + inset).coerceAtLeast(0f))
    return RoundedCornerShape(grow(shape.topStart), grow(shape.topEnd), grow(shape.bottomEnd), grow(shape.bottomStart))
}

/** For tests and callers that position the ring themselves: the ring's outer extent beyond the bounds, in dp. */
val FocusRingExtent = HatchDimens.FocusRing + HatchDimens.FocusRingOffset

