package app.hatch.ui.tabs

import android.content.Intent
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.hatch.Components
import kotlinx.coroutines.CancellationException
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.components.ui.icons.R as iconsR

/**
 * Full-screen tab tray (`Overlay.Tabs`): grid of [TabGridItem]s (2 columns on phones, 3–4 on wider screens),
 * swipe to close, long-press / "Select tabs" multi-select with close/share, "Close all" with confirmation, FAB for a
 * new tab, empty state, predictive back (scales the tray while the gesture is in progress; completion leaves select
 * mode first, then closes the tray).
 *
 * Closing goes through [onCloseTabs] / [onCloseAll] (`HatchApp.closeTabs`, UXP-04): one close path for the tray,
 * the toolbar menu and Ctrl+W, with the "Tab closed – Undo" snackbar on the app-wide host above this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var overflow by remember { mutableStateOf(false) }
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

    Scaffold(
        modifier = modifier.fillMaxSize().graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.selectMode) "${state.selectedCount} selected" else "${tabs.size} ${if (tabs.size == 1) "tab" else "tabs"}",
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (state.selectMode) state = state.exitSelect() else onDismiss() }) {
                        Icon(
                            painterResource(if (state.selectMode) iconsR.drawable.mozac_ic_cross_24 else iconsR.drawable.mozac_ic_chevron_down_24),
                            contentDescription = if (state.selectMode) "Leave selection" else "Close tab tray",
                        )
                    }
                },
                actions = {
                    if (state.selectMode) {
                        val hasSelection = state.selectedCount > 0
                        IconButton(enabled = hasSelection, onClick = { shareTabs(tabs.filter { it.id in state.selectedIds }) }) {
                            Icon(painterResource(iconsR.drawable.mozac_ic_share_android_24), contentDescription = "Share selected tabs")
                        }
                        IconButton(enabled = hasSelection, onClick = { closeTabs(state.selectedIds.toList()) }) {
                            Icon(painterResource(iconsR.drawable.mozac_ic_delete_24), contentDescription = "Close selected tabs")
                        }
                    } else {
                        Box {
                            IconButton(onClick = { overflow = true }) {
                                Icon(painterResource(iconsR.drawable.mozac_ic_ellipsis_vertical_24), contentDescription = "More")
                            }
                            DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                                DropdownMenuItem(
                                    text = { Text("Select tabs") },
                                    enabled = tabs.isNotEmpty(),
                                    // UXP-07: select mode starts empty (Fenix `Mode.Select(emptySet())`).
                                    onClick = { overflow = false; state = state.enterSelect() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Close all tabs") },
                                    enabled = tabs.isNotEmpty(),
                                    onClick = { overflow = false; confirmCloseAll = true },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!state.selectMode) {
                FloatingActionButton(onClick = onNewTab) {
                    Icon(painterResource(iconsR.drawable.mozac_ic_plus_24), contentDescription = "New tab")
                }
            }
        },
    ) { padding ->
        if (tabs.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No open tabs", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("Open one to get started.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onNewTab) { Text("New tab") }
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val columns = TabTrayState.columnsFor(maxWidth.value.toInt())
                // PERF-08: request thumbnails at the card's width, not a fixed 240 dp (half the bitmap memory).
                val thumbnailSizePx = TabTrayState.thumbnailSizePx(maxWidth.value, columns, LocalDensity.current.density)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(
                        start = TabTrayState.GRID_PADDING_DP.dp,
                        end = TabTrayState.GRID_PADDING_DP.dp,
                        top = 8.dp,
                        bottom = 96.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(TabTrayState.GRID_SPACING_DP.dp),
                    verticalArrangement = Arrangement.spacedBy(TabTrayState.GRID_SPACING_DP.dp),
                    modifier = Modifier.fillMaxSize(),
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
                            icons = components.icons,
                            thumbnails = components.thumbnailStorage,
                            thumbnailSizePx = thumbnailSizePx,
                            thumbnailGeneration = generation,
                            swipeState = swipeState,
                            swipingEnabled = !state.selectMode,
                            onClick = { t ->
                                if (state.selectMode) {
                                    state = state.toggle(t.id)
                                } else {
                                    components.tabsUseCases.selectTab(t.id)
                                    onDismiss()
                                }
                            },
                            onLongClick = { t -> state = state.toggle(t.id) },
                            onClose = { t -> closeTabs(listOf(t.id)) },
                        )
                    }
                }
            }
        }
    }

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
