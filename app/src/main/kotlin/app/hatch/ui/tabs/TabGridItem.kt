/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

// Adapted from Fenix 158 (mobile/android/fenix/app/src/main/java/org/mozilla/fenix/tabstray/ui/tabitems/
// TabGridTabItem.kt, tag FIREFOX_158_0_RELEASE): same structure (favicon + title + close header, thumbnail card,
// selected border, M3 SwipeToDismissBox), on top of Hatch's Material 3 theme with BrowserState tabs directly.
// Listed in THIRD_PARTY.md.

package app.hatch.ui.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mozilla.components.browser.icons.BrowserIcons
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.thumbnails.storage.ThumbnailStorage
import mozilla.components.concept.base.images.ImageLoadRequest
import mozilla.components.ui.icons.R as iconsR

private val HeaderFaviconSize = 14.dp
private val HeaderIconTouchTarget = 40.dp
private const val GRID_ITEM_ASPECT_RATIO = 0.95f
private val CardShape = RoundedCornerShape(12.dp)
private val ThumbnailShape = RoundedCornerShape(8.dp)

/** Selection facts for one grid card. */
data class TabItemSelection(val multiSelect: Boolean, val isSelected: Boolean, val isCurrent: Boolean)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabGridItem(
    tab: TabSessionState,
    selection: TabItemSelection,
    icons: BrowserIcons,
    thumbnails: ThumbnailStorage,
    /** Thumbnail request size (px): the card's width, computed by the grid (PERF-08). */
    thumbnailSizePx: Int,
    /** `ThumbnailGeneration.generation`: a new value re-reads the thumbnail file (ENG-06). */
    thumbnailGeneration: Long,
    swipeState: SwipeToDismissBoxState,
    swipingEnabled: Boolean,
    onClick: (TabSessionState) -> Unit,
    onLongClick: (TabSessionState) -> Unit,
    onClose: (TabSessionState) -> Unit,
    modifier: Modifier = Modifier,
) {
    // SwipeToDismissBox invokes onDismiss from a LaunchedEffect keyed on the callback: keep the lambda stable so a
    // recomposition after the dismissal does not close the tab twice.
    val currentTab by rememberUpdatedState(tab)
    val currentOnClose by rememberUpdatedState(onClose)
    val onDismiss = remember { { _: SwipeToDismissBoxValue -> currentOnClose(currentTab) } }

    SwipeToDismissBox(
        modifier = modifier,
        state = swipeState,
        backgroundContent = {},
        gesturesEnabled = swipingEnabled,
        onDismiss = onDismiss,
    ) {
        val border = when {
            selection.multiSelect && selection.isSelected -> BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
            selection.isCurrent -> BorderStroke(2.dp, MaterialTheme.colorScheme.secondary)
            else -> null
        }
        val container = when {
            selection.multiSelect && selection.isSelected -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .swipeToDismissFade(swipeState)
                .clip(CardShape)
                .combinedClickable(onClick = { onClick(tab) }, onLongClick = { onLongClick(tab) })
                .semantics { selected = selection.isCurrent },
            shape = CardShape,
            border = border,
            colors = CardDefaults.cardColors(containerColor = container),
        ) {
            Column(Modifier.aspectRatio(GRID_ITEM_ASPECT_RATIO)) {
                Row(Modifier.fillMaxWidth().wrapContentHeight(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(8.dp))
                    Favicon(
                        url = tab.content.url,
                        icons = icons,
                        size = HeaderFaviconSize,
                        bitmap = tab.content.icon,
                        isPrivate = tab.content.private,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = tab.content.title.ifBlank { tab.content.url },
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (selection.multiSelect) {
                        Box(Modifier.size(HeaderIconTouchTarget), contentAlignment = Alignment.Center) {
                            Icon(
                                painterResource(
                                    if (selection.isSelected) iconsR.drawable.mozac_ic_checkmark_24 else iconsR.drawable.mozac_ic_subtract_24,
                                ),
                                contentDescription = if (selection.isSelected) "Selected" else "Not selected",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        IconButton(onClick = { onClose(tab) }, modifier = Modifier.size(HeaderIconTouchTarget)) {
                            Icon(
                                painterResource(iconsR.drawable.mozac_ic_cross_20),
                                contentDescription = "Close tab",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Card(
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp).fillMaxSize(),
                    shape = ThumbnailShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        ThumbnailImage(
                            request = ImageLoadRequest(id = tab.id, size = thumbnailSizePx, isPrivate = tab.content.private),
                            storage = thumbnails,
                            generation = thumbnailGeneration,
                            modifier = Modifier.fillMaxSize(),
                            fallbackContent = {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Favicon(
                                        url = tab.content.url,
                                        icons = icons,
                                        size = 36.dp,
                                        bitmap = tab.content.icon,
                                        isPrivate = tab.content.private,
                                        shape = RoundedCornerShape(6.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
