package app.hatch.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * M3 shapes per the design: extraSmall 8, small 10 (chips), medium 20 (grouped cards), large 28 (sheets),
 * extraLarge 28 (dialogs).
 */
val HatchMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** The design's named shapes ("Size, shape, motion" spec card). Use these instead of literal radii. */
object HatchShapes {
    /** Address / find / edit pill: 44 dp high, r22. */
    val Pill = RoundedCornerShape(HatchDimens.PillRadius)

    /** Persona chip at the start of the pill: 36 dp high, r18. */
    val PersonaChip = RoundedCornerShape(HatchDimens.PersonaChipRadius)

    /** Grouped list cards (Settings, rules, logins …): r20. */
    val Card = RoundedCornerShape(HatchDimens.CardRadius)

    /** Bottom sheets: top corners r28. */
    val Sheet = RoundedCornerShape(topStart = HatchDimens.SheetRadius, topEnd = HatchDimens.SheetRadius)

    /** Dialogs and anchored cards (Site info): r28. */
    val Dialog = RoundedCornerShape(HatchDimens.SheetRadius)

    /** Chips: r10. */
    val Chip = RoundedCornerShape(HatchDimens.ChipRadius)

    /** Buttons: 40 dp high, r20 (fully rounded). */
    val Button = RoundedCornerShape(HatchDimens.ButtonRadius)

    /** FAB / extended FAB: 56 dp, r16. */
    val Fab = RoundedCornerShape(HatchDimens.FabRadius)

    /** The Hatch mark (home screen, crash screen): r14 at 44–48 dp. */
    val Mark = RoundedCornerShape(HatchDimens.MarkRadius)
}

/** The design's sizes in dp. Touch targets are never below [MinTouch]. */
object HatchDimens {
    val Toolbar = 56.dp
    val Pill = 44.dp
    val PillRadius = 22.dp
    val PersonaChip = 36.dp
    val PersonaChipRadius = 18.dp
    val Progress = 3.dp
    val MinTouch = 48.dp
    val Row = 56.dp
    val RowOneLine = 48.dp
    val Inset = 16.dp
    val ScreenPadding = 16.dp
    val CardRadius = 20.dp
    val SheetRadius = 28.dp
    val ChipRadius = 10.dp
    val Button = 40.dp
    val ButtonRadius = 20.dp
    val Fab = 56.dp
    val FabRadius = 16.dp
    val GapSmall = 8.dp
    val GapMedium = 12.dp
    val GapLarge = 20.dp
    val FocusRing = 2.dp
    val FocusRingOffset = 2.dp
    val Hairline = 1.dp
    val MarkRadius = 14.dp

    /**
     * The scrim behind the bottom sheets (Main menu, Persona sheet): the mockups' `rgba(0,0,0,.5)` (DF-03), not M3's
     * 0.32 default. Site info uses its own 0.45 (`SiteInfoDimens.SCRIM_ALPHA`, its mockup).
     */
    const val SHEET_SCRIM_ALPHA = 0.5f

    /** Toolbar tint crossfade when the persona flips (emphasized-decelerate, see [HatchMotion]). */
    const val TINT_CROSSFADE_MS = 200
}
