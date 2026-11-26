/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

// Adapted from Fenix 158 (mobile/android/fenix/app/src/main/java/org/mozilla/fenix/tabstray/ui/tabitems/
// TabGridTabItem.kt, tag FIREFOX_158_0_RELEASE): same structure (favicon + title + close header, thumbnail,
// selected ring, M3 SwipeToDismissBox), restyled to Hatch's "Tab tray" mockups (r18 `s1` card, 40 dp header,
// 170 dp thumbnail, 28 dp check circle in select mode) with BrowserState tabs directly. Listed in THIRD_PARTY.md.

package app.hatch.ui.tabs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hatch.ui.surfaces.SurfaceIcons
import app.hatch.ui.theme.HatchTheme
import app.hatch.ui.theme.hatchFocusRing
import mozilla.components.browser.state.state.TabSessionState

/** Tab-tray card geometry from the "Tab tray" mockups (dp). */
internal object TabCardDimens {
    val Radius = 18.dp
    val Header = 40.dp
    val Favicon = 16.dp
    val FaviconRadius = 4.dp
    val Close = 32.dp

    /** The close button's touch box: 48 dp wide × the 40 dp header (A11Y-08); the 32 dp circle is only the visual. */
    val CloseTouchWidth = 48.dp
    val CloseIcon = 16.dp
    val Thumbnail = 170.dp
    val Ring = 2.dp
    val Check = 28.dp
    val CheckInset = 8.dp
    const val UNSELECTED_THUMBNAIL_ALPHA = 0.6f
}

/** The card's spoken strings. */
internal object TabCardText {
    /** "Close tab, <title>" (the URL when the title is blank), so TalkBack can tell the close buttons apart. */
    fun closeLabel(title: String, url: String): String = "Close tab, ${title.ifBlank { url }}"
}

/** Selection facts for one grid card. */
data class TabItemSelection(val multiSelect: Boolean, val isSelected: Boolean, val isCurrent: Boolean) {
    /** The 2 dp accent ring: the current tab normally, the selected tabs in select mode. */
    val ringed: Boolean get() = if (multiSelect) isSelected else isCurrent
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabGridItem(
    tab: TabSessionState,
    selection: TabItemSelection,
    swipeState: SwipeToDismissBoxState,
    swipingEnabled: Boolean,
    onClick: (TabSessionState) -> Unit,
    onLongClick: (TabSessionState) -> Unit,
    onClose: (TabSessionState) -> Unit,
    /** The 16 dp favicon (r4). */
    favicon: @Composable () -> Unit,
    /** The thumbnail filling the 170 dp area. */
    thumbnail: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    // SwipeToDismissBox invokes onDismiss from a LaunchedEffect keyed on the callback: keep the lambda stable so a
    // recomposition after the dismissal does not close the tab twice.
    val currentTab by rememberUpdatedState(tab)
    val currentOnClose by rememberUpdatedState(onClose)
    val onDismiss = remember { { _: SwipeToDismissBoxValue -> currentOnClose(currentTab) } }
    val c = HatchTheme.colors
    val shape = RoundedCornerShape(TabCardDimens.Radius)

    SwipeToDismissBox(
        modifier = modifier,
        state = swipeState,
        backgroundContent = {},
        gesturesEnabled = swipingEnabled,
        onDismiss = onDismiss,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .swipeToDismissFade(swipeState)
                .outerRing(if (selection.ringed) c.ac else Color.Transparent, TabCardDimens.Ring, TabCardDimens.Radius)
                .hatchFocusRing(shape)
                .clip(shape)
                .background(c.s1)
                .combinedClickable(onClick = { onClick(tab) }, onLongClick = { onLongClick(tab) })
                .semantics { selected = if (selection.multiSelect) selection.isSelected else selection.isCurrent },
        ) {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = TabCardDimens.Header)
                        .padding(start = 10.dp, end = if (selection.multiSelect) 10.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    favicon()
                    Text(
                        text = tab.content.title.ifBlank { tab.content.url },
                        modifier = Modifier.weight(1f),
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!selection.multiSelect) {
                        // A11Y-08: the clickable is the 48 × 40 dp box (the header's full height), so a touch next to the
                        // 32 dp circle closes the tab instead of opening it; TalkBack names the tab it closes.
                        val closeLabel = TabCardText.closeLabel(tab.content.title, tab.content.url)
                        Box(
                            Modifier
                                .size(width = TabCardDimens.CloseTouchWidth, height = TabCardDimens.Header)
                                .hatchFocusRing(CircleShape)
                                .clip(CircleShape)
                                .clickable(role = Role.Button, onClickLabel = closeLabel, onClick = { onClose(tab) })
                                .semantics { contentDescription = closeLabel },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(TabCardDimens.Close).clip(CircleShape), contentAlignment = Alignment.Center) {
                                Icon(SurfaceIcons.CloseSmall, contentDescription = null, tint = c.mu, modifier = Modifier.size(TabCardDimens.CloseIcon))
                            }
                        }
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(TabCardDimens.Thumbnail)
                        .alpha(if (selection.multiSelect && !selection.isSelected) TabCardDimens.UNSELECTED_THUMBNAIL_ALPHA else 1f)
                        .background(c.s2),
                ) { thumbnail() }
            }
            if (selection.multiSelect) {
                CheckCircle(
                    selected = selection.isSelected,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(TabCardDimens.CheckInset),
                )
            }
        }
    }
}

/** The select-mode check: filled `ac` with an `oa` check when selected, a 2 dp `ac` ring otherwise. */
@Composable
private fun CheckCircle(selected: Boolean, modifier: Modifier = Modifier) {
    val c = HatchTheme.colors
    Box(
        modifier
            .size(TabCardDimens.Check)
            .clip(CircleShape)
            .background(if (selected) c.ac else Color.Transparent)
            .border(TabCardDimens.Ring, c.ac, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(SurfaceIcons.CheckBold, contentDescription = "Selected", tint = c.oa, modifier = Modifier.size(16.dp))
    }
}

/** A [width] ring drawn just outside the bounds (CSS `box-shadow: 0 0 0 2px`), concentric with [radius]. */
internal fun Modifier.outerRing(color: Color, width: Dp, radius: Dp): Modifier = drawWithContent {
    drawContent()
    if (color.alpha == 0f) return@drawWithContent
    val stroke = width.toPx()
    val inset = stroke / 2
    val ringSize = Size(size.width + inset * 2, size.height + inset * 2)
    val outline = RoundedCornerShape(radius + width / 2).createOutline(ringSize, layoutDirection, this)
    translate(-inset, -inset) { drawOutline(outline, color, style = Stroke(stroke)) }
}
