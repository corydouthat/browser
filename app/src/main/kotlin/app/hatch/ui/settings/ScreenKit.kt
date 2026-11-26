package app.hatch.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hatch.ui.theme.HatchDimens
import app.hatch.ui.theme.HatchShapes
import app.hatch.ui.theme.HatchTheme
import app.hatch.ui.theme.HatchType
import app.hatch.ui.theme.hatchFocusRing

/**
 * The building blocks the design repeats on every full-screen page (Settings and its pages, the persona editor, site
 * rules, site permissions, saved logins): the 64 dp top bar, grouped r20 cards with 1 dp hairlines, list rows, the
 * accent switch, pill buttons, the r16 segmented track, the r12 outlined form field with an inline error, and the
 * FABs. Sizes are the mockups' CSS px (= dp); every interactive element keeps a 48 dp touch target and grows with
 * the font scale (heights are minimums).
 */

/** Which glyph the top bar's leading button shows: back arrow (pages) or X (editors that discard). */
enum class NavIcon { Back, Close }

/**
 * The mockups' top bar: 64 dp under the status bar, a 48 dp nav button, title 22 sp 500, trailing actions.
 * [endPadding] is the mockup's: 12 dp where the trailing action is text or a button ("Import", "Save"), 4 dp where it
 * is a 48 dp icon button (Settings' search) or there is none (`padding:0 4px`; DF-07).
 *
 * The bar pads the top and horizontal safe-drawing insets (status bar, a landscape display cutout, side navigation
 * bar; A11Y-04), so its buttons never sit under the camera or the navigation bar.
 */
@Composable
fun ScreenTopBar(
    title: String,
    onNav: () -> Unit,
    modifier: Modifier = Modifier,
    nav: NavIcon = NavIcon.Back,
    endPadding: Dp = TopBarEndPaddingText,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = HatchTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .heightIn(min = TopBarHeight)
            .padding(start = 4.dp, end = endPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onNav, modifier = Modifier.hatchFocusRing(CircleShape)) {
            Icon(
                if (nav == NavIcon.Back) ScreenIcons.Back else ScreenIcons.Close,
                contentDescription = if (nav == NavIcon.Back) "Back" else "Close",
                tint = c.ink,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = c.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        actions()
    }
}

/** A top-bar text action ("Import", "Edit", "Clear all"): 14 sp 600 in the text accent (or [color]). */
@Composable
fun TopBarTextAction(text: String, onClick: () -> Unit, color: Color = HatchTheme.colors.acT, enabled: Boolean = true) {
    Box(
        Modifier
            .heightIn(min = HatchDimens.MinTouch)
            .widthIn(min = HatchDimens.MinTouch)
            .hatchFocusRing(HatchShapes.Button)
            .clip(HatchShapes.Button)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = color, modifier = Modifier.alpha(if (enabled) 1f else DisabledAlpha))
    }
}

/** The editors' "Save": primary fill, 40 dp r20, 40 % opacity and disabled while the form is invalid. */
@Composable
fun TopBarPrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val c = HatchTheme.colors
    Box(Modifier.minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .alpha(if (enabled) 1f else DisabledAlpha)
                .heightIn(min = HatchDimens.Button)
                .hatchFocusRing(HatchShapes.Button)
                .clip(HatchShapes.Button)
                .background(c.ac)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge, color = c.oa)
        }
    }
}

/** Group title above a card ("General", "Presets"): 13 sp 600, muted. */
@Composable
fun GroupTitle(text: String, modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 2.dp)) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = HatchTheme.colors.mu,
        modifier = modifier.padding(padding).semantics { heading() },
    )
}

/** A grouped card: surfaceContainer, r20, clipped so row ripples follow the corners. */
@Composable
fun GroupedCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(HatchShapes.Card)
            .background(HatchTheme.colors.s1),
        content = content,
    )
}

/** The 1 dp hairline between rows of a card (`ln`). */
@Composable
fun CardDivider() {
    HorizontalDivider(thickness = HatchDimens.Hairline, color = HatchTheme.colors.ln)
}

/** Cards list their rows with a hairline above every row but the first. */
@Composable
fun <T> CardRows(items: List<T>, row: @Composable (T) -> Unit) {
    items.forEachIndexed { i, item ->
        if (i > 0) CardDivider()
        row(item)
    }
}

/**
 * A card row: optional 20 dp leading icon, title 15 sp, optional summary 13 sp muted, optional trailing content.
 * [minHeight] is the mockup's row height (60 dp Settings, 64 dp switches, 56 dp permissions).
 */
@Composable
fun CardRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    minHeight: Dp = 60.dp,
    summaryStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    titleMaxLines: Int = Int.MAX_VALUE,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = HatchTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .then(
                if (onClick != null) {
                    Modifier.hatchFocusRing(CardRowFocusShape, inside = true).clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = c.mu, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
            if (summary != null) Text(summary, style = summaryStyle, color = c.mu)
        }
        trailing?.invoke()
    }
}

/**
 * A 64 dp card row with a switch; the whole row toggles: one TalkBack stop announced as "<title>, <summary>, On/Off,
 * Switch" (`toggleable` sets ToggleableState; `selectable` would say "Selected" with no checked state; A11Y-01).
 */
@Composable
fun SwitchCardRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = HatchTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .hatchFocusRing(CardRowFocusShape, inside = true)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodyMedium, color = c.mu)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = hatchSwitchColors())
    }
}

/** The design's switch: accent track with an onPrimary thumb when on; a muted 2 dp outline and thumb when off. */
@Composable
fun hatchSwitchColors(): SwitchColors {
    val c = HatchTheme.colors
    return SwitchDefaults.colors(
        checkedThumbColor = c.oa,
        checkedTrackColor = c.ac,
        checkedBorderColor = Color.Transparent,
        checkedIconColor = c.ac,
        uncheckedThumbColor = c.mu,
        uncheckedTrackColor = Color.Transparent,
        uncheckedBorderColor = c.mu,
        uncheckedIconColor = c.s1,
    )
}

/** Filled primary pill ("Apply", "Try again"): [height] high, fully rounded. */
@Composable
fun PrimaryPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 44.dp, enabled: Boolean = true, horizontalPadding: Dp = 22.dp) {
    val c = HatchTheme.colors
    PillBox(text, onClick, modifier, height, enabled, c.ac, c.oa, horizontalPadding, null)
}

/** Neutral pill on surfaceContainer ("Share", "Copy", "Test on current site"). */
@Composable
fun TonalPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 44.dp, enabled: Boolean = true, container: Color = HatchTheme.colors.s1) {
    PillBox(text, onClick, modifier, height, enabled, container, HatchTheme.colors.ink, 16.dp, null)
}

/** Outlined pill (1 dp `ln2`) in [color]: "Delete login", "Delete rule". */
@Composable
fun OutlinedPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = HatchTheme.colors.dg, height: Dp = 48.dp) {
    PillBox(text, onClick, modifier, height, true, Color.Transparent, color, 16.dp, HatchTheme.colors.ln2, textStyle = MaterialTheme.typography.titleSmall)
}

/** Text-only pill ("Cancel", "Clear Hatch data…"): 44 dp, r22. */
@Composable
fun TextPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = HatchTheme.colors.acT, height: Dp = 44.dp) {
    PillBox(text, onClick, modifier, height, true, Color.Transparent, color, 18.dp, null)
}

@Composable
private fun PillBox(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    height: Dp,
    enabled: Boolean,
    container: Color,
    content: Color,
    horizontalPadding: Dp,
    outline: Color?,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
) {
    val shape = RoundedCornerShape(height / 2)
    // The pill keeps the mockup's height; the outer box grows the touch target to 48 dp around it.
    Box(modifier.minimumInteractiveComponentSize(), contentAlignment = Alignment.Center, propagateMinConstraints = true) {
        Box(
            Modifier
                .alpha(if (enabled) 1f else DisabledAlpha)
                .heightIn(min = height)
                .hatchFocusRing(shape)
                .clip(shape)
                .background(container)
                .then(if (outline != null) Modifier.border(HatchDimens.Hairline, outline, shape) else Modifier)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = horizontalPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = textStyle, color = content, textAlign = TextAlign.Center)
        }
    }
}

/**
 * The persona editor's segmented control: a surfaceContainer track (r16, 4 dp inset, 4 dp gap) whose selected
 * segment is a surface pill (r12, 40 dp) with 600 ink text; the others are muted. [trackRadius] / [segmentRadius] /
 * [segmentHeight] / [inset] take the rule editor's smaller variant (r12 / r9 / 36 dp / 3 dp).
 *
 * Segments keep the mockup height (DF-08): the persona track is 48 dp in all (40 + 2 × 4), and touches on the track
 * inset reach the nearest segment through Compose's minimum-touch-target hit expansion (nothing else is hit there).
 * The height is a minimum, so the segments still grow with the font scale.
 */
@Composable
fun SegmentedTabs(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    trackRadius: Dp = 16.dp,
    segmentRadius: Dp = 12.dp,
    segmentHeight: Dp = 40.dp,
    inset: Dp = 4.dp,
) {
    val c = HatchTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(trackRadius))
            .background(c.s1)
            .padding(inset)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(inset),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = segmentHeight)
                    .hatchFocusRing(RoundedCornerShape(segmentRadius), inside = true)
                    .clip(RoundedCornerShape(segmentRadius))
                    .background(if (on) c.bg else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab) { onSelect(i) }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (on) c.ink else c.mu,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The design's outlined form field (r12): a 12 sp label over the value (15 sp, or [valueStyle] for machine values in
 * mono), 1 dp [app.hatch.ui.theme.HatchColors.outline] (≥ 3:1; A11Y-07) outline, 2 dp accent while focused, 2 dp error ring + error-coloured label + [error] helper text
 * under it when invalid. [placeholder] shows (muted) while empty, e.g. "Engine default".
 */
@Composable
fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    error: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val c = HatchTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val ringWidth = if (error != null || focused) 2.dp else HatchDimens.Hairline
    val ringColor = when {
        error != null -> c.dg
        focused -> c.ac
        else -> c.outline
    }
    val shape = RoundedCornerShape(FieldRadius)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = valueStyle.copy(color = c.ink),
            cursorBrush = SolidColor(c.ac),
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = visualTransformation,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = label
                    val problem = error
                    if (problem != null) this.error(problem)
                },
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .border(ringWidth, ringColor, shape)
                        .padding(start = 14.dp, end = if (trailing != null) 4.dp else 14.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = if (error != null) c.dg else c.mu)
                        Box {
                            if (value.isEmpty() && placeholder != null) Text(placeholder, style = valueStyle, color = c.mu)
                            inner()
                        }
                    }
                    trailing?.invoke()
                }
            },
        )
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = c.dg, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

/**
 * A read-only field with the [FormField] look that opens a menu of [options] (id to label): the rule editor's World
 * and Run at. The value shows [options]' label for [selected].
 */
@Composable
fun SelectField(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp),
) {
    val c = HatchTheme.colors
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(FieldRadius)
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .hatchFocusRing(shape)
                .clip(shape)
                .border(HatchDimens.Hairline, c.outline, shape)
                .clickable(role = Role.DropdownList) { open = true }
                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = c.mu)
                Text(options.firstOrNull { it.first == selected }?.second ?: selected, style = valueStyle, color = c.ink)
            }
            Icon(ScreenIcons.ChevronDown, contentDescription = null, tint = c.mu, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, text) ->
                DropdownMenuItem(
                    text = { Text(text, style = valueStyle) },
                    onClick = { open = false; onSelect(id) },
                    trailingIcon = if (id == selected) { { Icon(ScreenIcons.Check, contentDescription = "Selected", modifier = Modifier.size(18.dp)) } } else null,
                )
            }
        }
    }
}

/** The surfaceContainer note box with an info glyph (persona sheet time-zone notice, location notice). */
@Composable
fun InfoBox(text: String, modifier: Modifier = Modifier) {
    val c = HatchTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.s1)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(ScreenIcons.Info, contentDescription = null, tint = c.mu, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 19.sp), color = c.mu)
    }
}

/** Muted explanatory text under a page title (14 sp, 1.5 line height, 8 dp inset like the group titles). */
@Composable
fun PageNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, lineHeight = 21.sp),
        color = HatchTheme.colors.mu,
        modifier = modifier.padding(horizontal = 8.dp),
    )
}

/** Extended FAB ("New identity", "New rule"): primaryContainer, 56 dp, r16, 20 dp plus glyph, 15 sp 600. */
@Composable
fun HatchExtendedFab(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = HatchTheme.colors
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = HatchShapes.Fab,
        color = c.tb,
        contentColor = c.ti,
        shadowElevation = 6.dp,
        modifier = modifier.heightIn(min = HatchDimens.Fab),
    ) {
        Row(
            Modifier.heightIn(min = HatchDimens.Fab).padding(start = 16.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(ScreenIcons.Plus, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** Icon-only FAB (Saved logins "+"): primaryContainer, 56 dp, r16, 22 dp glyph. */
@Composable
fun HatchFab(contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = HatchTheme.colors
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = HatchShapes.Fab,
        color = c.tb,
        contentColor = c.ti,
        shadowElevation = 6.dp,
        modifier = modifier.size(HatchDimens.Fab),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(ScreenIcons.Plus, contentDescription = contentDescription, modifier = Modifier.size(22.dp))
        }
    }
}

/** Space under the last card so the FAB never covers it. */
@Composable
fun FabClearance() {
    Spacer(Modifier.height(96.dp))
}

/** The mono build footer ("Hatch · v0.1.0 (build 22)"), centred and muted. */
@Composable
fun MonoFooter(lines: List<String>) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        lines.forEach { Text(it, style = HatchType.mono, color = HatchTheme.colors.mu, textAlign = TextAlign.Center) }
    }
}

/** A small horizontal gap. */
@Composable
fun HGap(width: Dp) {
    Spacer(Modifier.width(width))
}

private val TopBarHeight = 64.dp

/** [ScreenTopBar] end padding when the trailing action is text or a filled button (`padding:0 12px 0 4px`). */
val TopBarEndPaddingText = 12.dp

/** [ScreenTopBar] end padding for an icon button or no action (`padding:0 4px`). */
val TopBarEndPaddingIcon = 4.dp

/** The inside focus ring of a card row: inset 2 dp, so the grouped card's r20 clip never cuts it. */
internal val CardRowFocusShape = RoundedCornerShape(16.dp)
private val FieldRadius = 12.dp
private const val DisabledAlpha = 0.4f
