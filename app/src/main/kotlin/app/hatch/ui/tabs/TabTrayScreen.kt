package app.hatch.ui.tabs

import android.content.Intent
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hatch.Components
import app.hatch.ui.home.PreviewFavicon
import app.hatch.ui.overlayPane
import app.hatch.ui.home.PreviewThumbnail
import app.hatch.ui.home.previewTabs
import app.hatch.ui.surfaces.PreviewTheme
import app.hatch.ui.surfaces.SurfaceIcons
import app.hatch.ui.surfaces.SurfacePreviewFrame
import app.hatch.ui.surfaces.SurfacePreviewThemes
import app.hatch.ui.theme.HatchDimens
import app.hatch.ui.theme.HatchShapes
import app.hatch.ui.theme.HatchTheme
import app.hatch.ui.theme.hatchFocusRing
import kotlinx.coroutines.CancellationException
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.concept.base.images.ImageLoadRequest
import mozilla.components.lib.state.ext.observeAsComposableState

/** The tray's top-bar title: "N tabs" ("1 tab"), "N selected" in select mode, "Tabs" when empty. */
internal fun trayTitle(tabCount: Int, state: TabTrayState): String = when {
    state.selectMode -> "${state.selectedCount} selected"
    tabCount == 0 -> "Tabs"
    tabCount == 1 -> "1 tab"
    else -> "$tabCount tabs"
}

/**
 * Full-screen tab tray (`Overlay.Tabs`, "Tab tray" mockups): 64 dp top bar (close, "N tabs", overflow with
 * "Select tabs" / "Close all tabs"), a grid of [TabGridItem]s (2 columns on phones, 3–4 on wider screens), swipe to
 * close, long-press / "Select tabs" multi-select (top bar and status bar tinted `tb`, back, "N selected", share,
 * delete), "Close all" with confirmation, the "New tab" extended FAB, the empty state, and predictive back (scales the
 * tray while the gesture is in progress; completion leaves select mode first, then closes the tray).
 *
 * Closing goes through [onCloseTabs] / [onCloseAll] (`HatchApp.closeTabs`, UXP-04): one close path for the tray,
 * the toolbar menu and Ctrl+W, with the "Tab closed – Undo" snackbar on the app-wide host above this screen.
 */
@Composable
fun TabTrayScreen(
    components: Components,
    onDismiss: () -> Unit,
    onNewTab: () -> Unit,
    onCloseTabs: (List<String>) -> Unit,
    onCloseAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val store = components.store
    val tabs by store.observeAsComposableState { it.tabs }
    val selectedTabId by store.observeAsComposableState { it.selectedTabId }
    val generation by components.thumbnailGeneration.generation.collectAsState()
    var state by remember { mutableStateOf(TabTrayState()) }
    var confirmCloseAll by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scale = remember { Animatable(1f) }

    LaunchedEffect(tabs) { state = state.retain(tabs.map { it.id }) }

    PredictiveBackHandler(enabled = true) { progress ->
        try {
            progress.collect { event -> scale.snapTo(1f - 0.08f * event.progress) }
            if (state.selectMode) state = state.exitSelect() else onDismiss()
            scale.snapTo(1f)
        } catch (_: CancellationException) {
            scale.animateTo(1f)
        }
    }

    fun closeTabs(ids: List<String>) {
        if (ids.isEmpty()) return
        state = state.exitSelect()
        onCloseTabs(ids)
    }

    fun shareTabs(selected: List<TabSessionState>) {
        if (selected.isEmpty()) return
        val text = selected.joinToString("\n") { it.content.url }
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        context.startActivity(Intent.createChooser(send, null))
        state = state.exitSelect()
    }

    TabTrayLayout(
        tabs = tabs,
        selectedTabId = selectedTabId,
        state = state,
        onState = { state = it },
        onDismiss = onDismiss,
        onNewTab = onNewTab,
        onOpenTab = { id ->
            components.tabsUseCases.selectTab(id)
            onDismiss()
        },
        onCloseTabs = ::closeTabs,
        onShare = { ids -> shareTabs(tabs.filter { it.id in ids }) },
        onCloseAllRequest = { confirmCloseAll = true },
        favicon = { tab ->
            Favicon(
                url = tab.content.url,
                icons = components.icons,
                size = TabCardDimens.Favicon,
                bitmap = tab.content.icon,
                isPrivate = tab.content.private,
                shape = RoundedCornerShape(TabCardDimens.FaviconRadius),
            )
        },
        thumbnail = { tab, sizePx ->
            ThumbnailImage(
                request = ImageLoadRequest(id = tab.id, size = sizePx, isPrivate = tab.content.private),
                storage = components.thumbnailStorage,
                generation = generation,
                modifier = Modifier.fillMaxSize(),
                fallbackContent = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Favicon(
                            url = tab.content.url,
                            icons = components.icons,
                            size = 36.dp,
                            bitmap = tab.content.icon,
                            isPrivate = tab.content.private,
                            shape = RoundedCornerShape(8.dp),
                        )
                    }
                },
            )
        },
        modifier = modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value },
    )

    if (confirmCloseAll) {
        AlertDialog(
            onDismissRequest = { confirmCloseAll = false },
            title = { Text("Close all tabs?") },
            text = { Text("${tabs.size} tabs will be closed. You can undo for a few seconds.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCloseAll = false
                    state = state.exitSelect()
                    onCloseAll()
                }) { Text("Close all") }
            },
            dismissButton = { TextButton(onClick = { confirmCloseAll = false }) { Text("Cancel") } },
        )
    }
}

/** The stateless tray (previewable): favicons and thumbnails come in as slots. */
@Composable
internal fun TabTrayLayout(
    tabs: List<TabSessionState>,
    selectedTabId: String?,
    state: TabTrayState,
    onState: (TabTrayState) -> Unit,
    onDismiss: () -> Unit,
    onNewTab: () -> Unit,
    onOpenTab: (String) -> Unit,
    onCloseTabs: (List<String>) -> Unit,
    onShare: (Set<String>) -> Unit,
    onCloseAllRequest: () -> Unit,
    favicon: @Composable (TabSessionState) -> Unit,
    thumbnail: @Composable (TabSessionState, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HatchTheme.colors
    // A Surface (not a Box) so touches never fall through to the browser composed underneath.
    Surface(modifier.fillMaxSize(), color = c.bg, contentColor = c.ink) {
        // A11Y-03 / A11Y-04: focus and TalkBack enter the tray when it opens; content clears a landscape cutout.
        Box(Modifier.fillMaxSize().overlayPane(trayTitle(tabs.size, state))) {
            Column(Modifier.fillMaxSize()) {
                TrayTopBar(
                    title = trayTitle(tabs.size, state),
                    selectMode = state.selectMode,
                    hasTabs = tabs.isNotEmpty(),
                    hasSelection = state.selectedCount > 0,
                    onClose = onDismiss,
                    onLeaveSelect = { onState(state.exitSelect()) },
                    onSelectTabs = { onState(state.enterSelect()) },
                    onCloseAll = onCloseAllRequest,
                    onShare = { onShare(state.selectedIds) },
                    onDelete = { onCloseTabs(state.selectedIds.toList()) },
                )
                if (tabs.isEmpty()) {
                    EmptyTray(Modifier.weight(1f))
                } else {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val columns = TabTrayState.columnsFor(maxWidth.value.toInt())
                        // PERF-08: request thumbnails at the card's width, not a fixed 240 dp (half the bitmap memory).
                        val thumbnailSizePx = TabTrayState.thumbnailSizePx(maxWidth.value, columns, LocalDensity.current.density)
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns),
                            contentPadding = PaddingValues(
                                start = TabTrayState.GRID_PADDING_DP.dp,
                                end = TabTrayState.GRID_PADDING_DP.dp,
                                top = if (state.selectMode) TabTrayState.GRID_PADDING_DP.dp else 4.dp,
                                bottom = 96.dp,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(TabTrayState.GRID_SPACING_DP.dp),
                            verticalArrangement = Arrangement.spacedBy(TabTrayState.GRID_SPACING_DP.dp),
                            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                        ) {
                            items(tabs, key = { it.id }) { tab ->
                                val swipeState = rememberSwipeToDismissBoxState(tab.id)
                                TabGridItem(
                                    tab = tab,
                                    selection = TabItemSelection(
                                        multiSelect = state.selectMode,
                                        isSelected = tab.id in state.selectedIds,
                                        isCurrent = tab.id == selectedTabId,
                                    ),
                                    swipeState = swipeState,
                                    swipingEnabled = !state.selectMode,
                                    onClick = { t -> if (state.selectMode) onState(state.toggle(t.id)) else onOpenTab(t.id) },
                                    onLongClick = { t -> onState(state.toggle(t.id)) },
                                    onClose = { t -> onCloseTabs(listOf(t.id)) },
                                    favicon = { favicon(tab) },
                                    thumbnail = { thumbnail(tab, thumbnailSizePx) },
                                )
                            }
                        }
                    }
                }
            }
            if (!state.selectMode) {
                NewTabFab(onNewTab, Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 20.dp))
            }
        }
    }
}

/** 64 dp bar under the status bar; in select mode both are tinted `tb` with `ti` content. */
@Composable
private fun TrayTopBar(
    title: String,
    selectMode: Boolean,
    hasTabs: Boolean,
    hasSelection: Boolean,
    onClose: () -> Unit,
    onLeaveSelect: () -> Unit,
    onSelectTabs: () -> Unit,
    onCloseAll: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = HatchTheme.colors
    val fg = if (selectMode) c.ti else c.ink
    Box(Modifier.fillMaxWidth().background(if (selectMode) c.tb else c.bg).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (selectMode) {
                BarButton(SurfaceIcons.Back, "Leave selection", fg, onClick = onLeaveSelect)
            } else {
                BarButton(SurfaceIcons.Close, "Close tab tray", fg, onClick = onClose)
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = fg,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (selectMode) {
                BarButton(SurfaceIcons.Share, "Share selected tabs", fg, enabled = hasSelection, onClick = onShare)
                BarButton(SurfaceIcons.Delete, "Close selected tabs", fg, enabled = hasSelection, onClick = onDelete)
            } else if (hasTabs) {
                var overflow by remember { mutableStateOf(false) }
                Box {
                    BarButton(SurfaceIcons.Overflow, "More", fg, onClick = { overflow = true })
                    DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                        DropdownMenuItem(
                            text = { Text("Select tabs") },
                            // UXP-07: select mode starts empty (Fenix `Mode.Select(emptySet())`).
                            onClick = { overflow = false; onSelectTabs() },
                        )
                        DropdownMenuItem(
                            text = { Text("Close all tabs") },
                            onClick = { overflow = false; onCloseAll() },
                        )
                    }
                }
            }
        }
    }
}

/** 48 dp icon button with a 22 dp glyph; disabled at 50 %. */
@Composable
private fun BarButton(icon: ImageVector, description: String, tint: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .hatchFocusRing(CircleShape)
            .size(HatchDimens.MinTouch)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** "New tab" extended FAB: 56 dp, r16, `tb` / `ti`, 20 dp plus, 15 sp 600. */
@Composable
private fun NewTabFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = HatchTheme.colors
    Surface(
        onClick = onClick,
        modifier = modifier.hatchFocusRing(HatchShapes.Fab),
        shape = HatchShapes.Fab,
        color = c.tb,
        contentColor = c.ti,
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.heightIn(min = HatchDimens.Fab).padding(start = 16.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(SurfaceIcons.Plus, contentDescription = null, tint = c.ti, modifier = Modifier.size(20.dp))
            Text("New tab", style = MaterialTheme.typography.titleSmall, color = c.ti)
        }
    }
}

/** "Tab tray · Empty": 64 dp r20 tile with the "0" counter glyph, "No open tabs", "Open one to get started." */
@Composable
private fun EmptyTray(modifier: Modifier = Modifier) {
    val c = HatchTheme.colors
    Column(
        modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(c.s1), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(26.dp).border(2.5.dp, c.mu, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("0", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, lineHeight = 13.sp), color = c.mu)
            }
        }
        Text(
            "No open tabs",
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
            color = c.ink,
            textAlign = TextAlign.Center,
        )
        Text("Open one to get started.", style = MaterialTheme.typography.bodyLarge, color = c.mu, textAlign = TextAlign.Center)
    }
}

// ---- previews ------------------------------------------------------------------------------------------------

@Composable
private fun TrayPreview(theme: PreviewTheme, tabs: List<TabSessionState>, state: TabTrayState) {
    SurfacePreviewFrame(theme) {
        TabTrayLayout(
            tabs = tabs,
            selectedTabId = tabs.firstOrNull()?.id,
            state = state,
            onState = {},
            onDismiss = {},
            onNewTab = {},
            onOpenTab = {},
            onCloseTabs = {},
            onShare = {},
            onCloseAllRequest = {},
            favicon = { tab -> PreviewFavicon(tab, TabCardDimens.Favicon) },
            thumbnail = { tab, _ -> PreviewThumbnail(tab) },
        )
    }
}

@Preview(widthDp = 360, heightDp = 760)
@Composable
internal fun TabTrayPreview(@PreviewParameter(SurfacePreviewThemes::class) theme: PreviewTheme) {
    TrayPreview(theme, previewTabs, TabTrayState())
}

@Preview(widthDp = 360, heightDp = 760)
@Composable
internal fun TabTraySelectionPreview(@PreviewParameter(SurfacePreviewThemes::class) theme: PreviewTheme) {
    TrayPreview(theme, previewTabs, TabTrayState(selectMode = true, selectedIds = setOf("p2", "p3")))
}

@Preview(widthDp = 360, heightDp = 760)
@Composable
internal fun TabTrayEmptyPreview(@PreviewParameter(SurfacePreviewThemes::class) theme: PreviewTheme) {
    TrayPreview(theme, emptyList(), TabTrayState())
}
