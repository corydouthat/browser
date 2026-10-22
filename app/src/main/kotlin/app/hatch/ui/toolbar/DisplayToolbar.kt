package app.hatch.ui.toolbar

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hatch.R
import app.hatch.ui.theme.HatchDimens
import app.hatch.ui.theme.HatchMotion
import app.hatch.ui.theme.HatchPalette
import app.hatch.ui.theme.HatchShapes
import app.hatch.ui.theme.HatchTheme
import app.hatch.ui.theme.ThemeMode
import app.hatch.ui.theme.hatchFocusRing
import mozilla.components.compose.browser.toolbar.concept.PageOrigin.Companion.PageOriginContextualMenuInteractions.CopyToClipboardClicked
import mozilla.components.compose.browser.toolbar.concept.PageOrigin.Companion.PageOriginContextualMenuInteractions.LoadFromClipboardClicked
import mozilla.components.compose.browser.toolbar.concept.PageOrigin.Companion.PageOriginContextualMenuInteractions.PasteFromClipboardClicked
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction.BrowserToolbarEvent

/** What the display toolbar shows: everything is a plain value so previews and tests need no store. */
@Immutable
data class DisplayToolbarModel(
    /** [ToolbarState.displayHost] of the selected tab; empty on the Hatch home (the hint shows). */
    val host: String,
    val security: ToolbarState.ChipSecurity,
    /** The persona's desktop flag: chip glyph (monitor / phone) and the row's tint. */
    val desktop: Boolean,
    /** 0..100 while loading, null otherwise ([ToolbarState.progress]). */
    val progress: Int?,
    val tabCount: Int,
)

/**
 * The chrome's colours for the persona's [desktop] flag, each crossfading over 200 ms (emphasized-decelerate) when
 * the persona flips: the design's only decorative motion. BrowserScreen paints the status-bar strip with the same
 * [ToolbarColors.background], so strip and toolbar tint together.
 */
@Composable
fun rememberToolbarColors(desktop: Boolean): ToolbarColors {
    val target = ToolbarColors.of(HatchTheme.colors, desktop)
    @Composable
    fun anim(c: Color): Color = animateColorAsState(c, animationSpec = HatchMotion.tintCrossfade(), label = "toolbarTint").value
    return ToolbarColors(
        background = anim(target.background),
        content = anim(target.content),
        pill = anim(target.pill),
        chipFill = anim(target.chipFill),
        chipContent = anim(target.chipContent),
        chipSeparator = anim(target.chipSeparator),
        text = anim(target.text),
        hint = target.hint,
        outline = target.outline,
        progress = target.progress,
        progressTrack = target.progressTrack,
        divider = anim(target.divider),
    )
}

/**
 * The 56 dp toolbar row shared by the display, edit and find toolbars (mockups of section 01): padding 0 4 0 8,
 * 2 dp gaps, content centred; the 3 dp progress bar sits on the bottom edge while loading, and the 1 dp hairline
 * under it shows only where [ToolbarColors.divider] is opaque (phone-mode find row). Heights are minimums so the row
 * grows with the font scale instead of clipping.
 */
@Composable
internal fun ToolbarRow(
    colors: ToolbarColors,
    modifier: Modifier = Modifier,
    progress: Int? = null,
    showDivider: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth().background(colors.background)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = HatchDimens.Toolbar).padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
        if (showDivider) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(HatchDimens.Hairline).background(colors.divider))
        }
        if (progress != null) ProgressLine(progress, colors, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * "progress 3dp · primary on track". Drawn, not a LinearProgressIndicator (M3 1.4 adds a gap and a stop dot). For
 * TalkBack it is a progress bar ("Loading page, 45 percent"; A11Y-12): [progressSemantics] gives it the range info.
 */
@Composable
private fun ProgressLine(progress: Int, colors: ToolbarColors, modifier: Modifier = Modifier) {
    val fraction = progress.coerceIn(0, 100) / 100f
    val label = stringResource(R.string.toolbar_loading)
    Spacer(
        modifier
            .fillMaxWidth()
            .height(HatchDimens.Progress)
            .semantics { progressSemantics(this, fraction, label) }
            .drawBehind {
                drawRect(colors.progressTrack)
                drawRect(colors.progress, topLeft = Offset.Zero, size = Size(size.width * fraction, size.height))
            },
    )
}

/**
 * Display mode (mockups "Browser · Login picker", "Page error", "Content crashed", "Site info"): the 44 dp pill with
 * the persona chip (security glyph | device glyph; tap → Site info) and the host (tap → edit mode, long-press →
 * Copy / Paste / Paste & go), then the tab counter and the overflow. Every interaction is a [BrowserToolbarEvent]
 * for the `BrowserToolbarStore`, so `HatchToolbarMiddleware` stays the one place that acts on them.
 */
@Composable
fun DisplayToolbar(
    model: DisplayToolbarModel,
    colors: ToolbarColors,
    onEvent: (BrowserToolbarEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    ToolbarRow(colors, modifier, progress = model.progress) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = HatchDimens.Pill)
                .background(colors.pill, HatchShapes.Pill)
                .padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PersonaChip(model.security, model.desktop, colors, onClick = { onEvent(ToolbarEvents.SiteInfoClicked) })
            HostText(model.host, colors, onEvent)
        }
        ToolbarEndActions(model.tabCount, colors, onEvent)
    }
}

/**
 * The persona chip, 36 dp r18 at the start of the pill: desktop = filled primary with onPrimary glyphs, phone = no
 * fill with onSurfaceVariant glyphs. 14 dp glyphs, 6 dp gaps, 1 × 14 dp separator, padding 0 10. Its touch area is
 * widened to 48 dp by Compose's minimum-touch-target hit expansion (a 48 dp layout would not fit the 44 dp pill).
 */
@Composable
private fun PersonaChip(
    security: ToolbarState.ChipSecurity,
    desktop: Boolean,
    colors: ToolbarColors,
    onClick: () -> Unit,
) {
    val securityLabel = stringResource(
        when (security) {
            ToolbarState.ChipSecurity.Lock -> R.string.toolbar_security_secure
            ToolbarState.ChipSecurity.Globe -> R.string.toolbar_security_insecure
            ToolbarState.ChipSecurity.Warning -> R.string.toolbar_security_broken
        },
    )
    val personaLabel = stringResource(if (desktop) R.string.toolbar_persona_desktop else R.string.toolbar_persona_phone)
    val siteInfo = stringResource(R.string.toolbar_site_info)
    Row(
        Modifier
            .heightIn(min = HatchDimens.PersonaChip)
            .hatchFocusRing(HatchShapes.PersonaChip)
            .clip(HatchShapes.PersonaChip)
            .background(colors.chipFill)
            .clickable(onClickLabel = siteInfo, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$siteInfo. $securityLabel. $personaLabel" }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(ToolbarIcons.security(security), contentDescription = null, tint = colors.chipContent, modifier = Modifier.size(14.dp))
        Box(Modifier.width(HatchDimens.Hairline).height(14.dp).background(colors.chipSeparator))
        Icon(
            if (desktop) ToolbarIcons.Monitor else ToolbarIcons.Phone,
            contentDescription = null,
            tint = colors.chipContent,
            modifier = Modifier.size(14.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.HostText(host: String, colors: ToolbarColors, onEvent: (BrowserToolbarEvent) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box(
        Modifier
            .weight(1f)
            .heightIn(min = HatchDimens.Pill)
            .hatchFocusRing(HostShape, inside = true)
            .clip(HostShape)
            .combinedClickable(
                onClickLabel = stringResource(R.string.toolbar_address),
                onLongClickLabel = stringResource(R.string.toolbar_address_actions),
                onLongClick = { menu = true },
                onClick = { onEvent(ToolbarEvents.OriginClicked) },
            )
            .padding(start = 8.dp, end = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (host.isEmpty()) {
            Text(
                stringResource(R.string.toolbar_search_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.hint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(host, style = MaterialTheme.typography.bodyLarge, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AddressMenu(expanded = menu, canCopy = host.isNotEmpty(), onDismiss = { menu = false }, onEvent = onEvent)
    }
}

/** AC's page-origin long-press menu is private (`BrowserDisplayToolbar`); this is the same three entries, same events. */
@Composable
private fun AddressMenu(expanded: Boolean, canCopy: Boolean, onDismiss: () -> Unit, onEvent: (BrowserToolbarEvent) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.toolbar_copy)) },
            enabled = canCopy,
            onClick = { onDismiss(); onEvent(CopyToClipboardClicked) },
        )
        DropdownMenuItem(text = { Text(stringResource(R.string.toolbar_paste)) }, onClick = { onDismiss(); onEvent(PasteFromClipboardClicked) })
        DropdownMenuItem(text = { Text(stringResource(R.string.toolbar_paste_go)) }, onClick = { onDismiss(); onEvent(LoadFromClipboardClicked) })
    }
}

/**
 * Tab counter + overflow, shared by display and edit mode. The counter is a 20 dp r6 square with a 2 dp border and
 * an 11 sp bold number in a 44 dp box; the overflow is three dots in a 36 × 44 box. Both are padded to the 48 dp
 * minimum touch target (`minimumInteractiveComponentSize`), so the pair is 96 dp wide instead of the mockup's 44 + 36
 * and the pill ends [ToolbarTouchTargets.PILL_NARROWER_DP] dp earlier (DF-01; docs/limitations.md "In-pill touch
 * targets"). Long-press on the counter: New tab / Close tab.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ToolbarEndActions(tabCount: Int, colors: ToolbarColors, onEvent: (BrowserToolbarEvent) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val tabsLabel = pluralStringResource(R.plurals.toolbar_tabs_count, tabCount, tabCount)
    Box {
        Box(
            Modifier
                .minimumInteractiveComponentSize()
                .size(44.dp)
                .hatchFocusRing(CircleShape)
                .clip(CircleShape)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.toolbar_tabs),
                    onLongClickLabel = stringResource(R.string.toolbar_tab_actions),
                    onLongClick = { menu = true },
                    onClick = { onEvent(ToolbarEvents.TabCounterClicked) },
                )
                .semantics { contentDescription = tabsLabel },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .sizeIn(minWidth = 20.dp, minHeight = 20.dp)
                    .border(2.dp, colors.content, TabCounterShape)
                    .padding(horizontal = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    ToolbarState.tabCountLabel(tabCount),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold),
                    color = colors.content,
                    maxLines = 1,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.toolbar_new_tab)) },
                onClick = { menu = false; onEvent(ToolbarEvents.NewTabClicked) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.toolbar_close_tab)) },
                enabled = tabCount > 0,
                onClick = { menu = false; onEvent(ToolbarEvents.CloseTabClicked) },
            )
        }
    }
    ToolbarIconButton(
        icon = ToolbarIcons.Overflow,
        contentDescription = stringResource(R.string.toolbar_menu),
        tint = colors.content,
        width = 36.dp,
        onClick = { onEvent(ToolbarEvents.MenuClicked) },
    )
}

/** A toolbar glyph button: [width] × 44 dp box (min 48 dp touch target), 22 dp glyph. */
@Composable
internal fun ToolbarIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = 44.dp,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .size(width = width, height = 44.dp)
            .hatchFocusRing(RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) tint else tint.copy(alpha = DISABLED_ALPHA), modifier = Modifier.size(22.dp))
    }
}

/**
 * DF-01 / DF-02: how much narrower than the mockups the pills are because every toolbar button keeps a 48 dp touch
 * target. Display / edit row: counter (48 − 44) + overflow (48 − 36) = 16 dp. Find row: up, down, close 3 × (48 − 44)
 * = 12 dp. The numbers docs/limitations.md quotes; `ToolbarTouchTargetsTest` keeps them in step.
 */
object ToolbarTouchTargets {
    const val MIN_TOUCH_DP = 48
    const val COUNTER_DP = 44
    const val OVERFLOW_DP = 36
    const val FIND_BUTTON_DP = 44
    const val FIND_BUTTONS = 3
    const val PILL_NARROWER_DP = (MIN_TOUCH_DP - COUNTER_DP) + (MIN_TOUCH_DP - OVERFLOW_DP)
    const val FIND_PILL_NARROWER_DP = FIND_BUTTONS * (MIN_TOUCH_DP - FIND_BUTTON_DP)
}

/** The toolbar progress line's semantics: a 0–1 progress bar labelled [label], no raw "45%" text (A11Y-12). */
internal fun progressSemantics(receiver: androidx.compose.ui.semantics.SemanticsPropertyReceiver, fraction: Float, label: String) {
    with(receiver) {
        contentDescription = label
        progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
    }
}

/** The host area's ripple follows the pill's rounded end. */
private val HostShape = RoundedCornerShape(topEnd = HatchDimens.PillRadius, bottomEnd = HatchDimens.PillRadius)

/** The tab counter square's r6 (the mockup's `border-radius:6px`; no shared token has this radius). */
private val TabCounterShape = RoundedCornerShape(6.dp)

internal const val DISABLED_ALPHA = 0.38f

// ---- previews ------------------------------------------------------------------------------------------------------

@Composable
internal fun ToolbarPreviewFrame(palette: HatchPalette, dark: Boolean, desktop: Boolean, content: @Composable BoxScope.(ToolbarColors) -> Unit) {
    HatchTheme(palette = palette, mode = if (dark) ThemeMode.Dark else ThemeMode.Light) {
        val colors = rememberToolbarColors(desktop)
        Column(Modifier.background(HatchTheme.colors.bg)) {
            // The 32 dp status strip of the mockups, in the toolbar's tint.
            Box(Modifier.fillMaxWidth().height(32.dp).background(colors.background))
            Box(Modifier.fillMaxWidth()) { content(colors) }
            Spacer(Modifier.height(40.dp))
        }
    }
}

private val previewDesktop = DisplayToolbarModel("secure.chase.com", ToolbarState.ChipSecurity.Lock, desktop = true, progress = null, tabCount = 4)

@Preview(name = "Display · desktop · Graphite light", widthDp = 360)
@Composable
internal fun DisplayDesktopGraphiteLight() = ToolbarPreviewFrame(HatchPalette.Graphite, dark = false, desktop = true) {
    DisplayToolbar(previewDesktop, it, onEvent = {})
}

@Preview(name = "Display · desktop · Graphite dark", widthDp = 360)
@Composable
internal fun DisplayDesktopGraphiteDark() = ToolbarPreviewFrame(HatchPalette.Graphite, dark = true, desktop = true) {
    DisplayToolbar(previewDesktop, it, onEvent = {})
}

@Preview(name = "Display · phone · Graphite light", widthDp = 360)
@Composable
internal fun DisplayPhoneGraphiteLight() = ToolbarPreviewFrame(HatchPalette.Graphite, dark = false, desktop = false) {
    DisplayToolbar(previewDesktop.copy(host = "docs.google.com", desktop = false), it, onEvent = {})
}

@Preview(name = "Display · phone · Phosphor dark", widthDp = 360)
@Composable
internal fun DisplayPhonePhosphorDark() = ToolbarPreviewFrame(HatchPalette.Phosphor, dark = true, desktop = false) {
    DisplayToolbar(previewDesktop.copy(host = "docs.google.com", desktop = false), it, onEvent = {})
}

@Preview(name = "Display · loading · Phosphor light", widthDp = 360)
@Composable
internal fun DisplayLoadingPhosphorLight() = ToolbarPreviewFrame(HatchPalette.Phosphor, dark = false, desktop = true) {
    DisplayToolbar(previewDesktop.copy(progress = 45), it, onEvent = {})
}

@Preview(name = "Display · insecure · Indigo dark", widthDp = 360)
@Composable
internal fun DisplayInsecureIndigoDark() = ToolbarPreviewFrame(HatchPalette.Indigo, dark = true, desktop = true) {
    DisplayToolbar(previewDesktop.copy(host = "maps.google.com", security = ToolbarState.ChipSecurity.Globe), it, onEvent = {})
}

@Preview(name = "Display · broken https · Amber light", widthDp = 360)
@Composable
internal fun DisplayBrokenAmberLight() = ToolbarPreviewFrame(HatchPalette.Amber, dark = false, desktop = false) {
    DisplayToolbar(previewDesktop.copy(security = ToolbarState.ChipSecurity.Warning, desktop = false, tabCount = 128), it, onEvent = {})
}

@Preview(name = "Display · home hint · Graphite light · 200% font", widthDp = 360, fontScale = 2f)
@Composable
internal fun DisplayHomeLargeFont() = ToolbarPreviewFrame(HatchPalette.Graphite, dark = false, desktop = true) {
    DisplayToolbar(previewDesktop.copy(host = ""), it, onEvent = {})
}
