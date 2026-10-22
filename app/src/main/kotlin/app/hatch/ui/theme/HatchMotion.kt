package app.hatch.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color

/**
 * The design's only decorative motion: the toolbar tint crossfades over 200 ms with M3 emphasized-decelerate
 * (cubic-bezier(0.05, 0.7, 0.1, 1.0)) when the persona flips. Sheets and predictive back keep M3 defaults.
 */
object HatchMotion {
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

    /** Pass to `animateColorAsState(target, animationSpec = HatchMotion.tintCrossfade())`. */
    fun tintCrossfade(): FiniteAnimationSpec<Color> = tween(HatchDimens.TINT_CROSSFADE_MS, easing = EmphasizedDecelerate)
}
