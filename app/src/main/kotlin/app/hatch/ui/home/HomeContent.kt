package app.hatch.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hatch.persona.Persona
import app.hatch.ui.tabs.ThumbnailImage
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.thumbnails.storage.ThumbnailStorage
import mozilla.components.concept.base.images.ImageLoadRequest

/** Tabs for the "Recently open" row: everything except the current tab, most recently accessed first. */
internal fun recentTabs(tabs: List<TabSessionState>, currentId: String?, limit: Int = 8): List<TabSessionState> =
    tabs.filter { it.id != currentId && it.content.url != "about:blank" }
        .sortedByDescending { maxOf(it.lastAccess, it.createdAt) }
        .take(limit)

/**
 * The Hatch home: shown over an `about:blank` tab with no history. Persona card (identity, region, desktop switch,
 * "Change…"), a "Recently open" thumbnails row, and the homepage shortcut. The toolbar enters edit mode when the
 * home appears (BrowserScreen), so typing starts immediately.
 */
@Composable
fun HomeContent(
    persona: Persona,
    tabs: List<TabSessionState>,
    currentTabId: String?,
    thumbnails: ThumbnailStorage,
    /** `ThumbnailGeneration.generation`: a capture written after this row composed is picked up (ENG-06). */
    thumbnailGeneration: Long,
    homeUrl: String,
    onChangePersona: () -> Unit,
    onToggleDesktop: () -> Unit,
    onOpenTab: (String) -> Unit,
    onOpenHomepage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recent = recentTabs(tabs, currentTabId)
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("Hatch", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text("Desktop web, on the road.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Persona", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(persona.identity.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        buildString {
                            append(persona.region.name)
                            append(" · ").append(persona.region.languages.joinToString(","))
                            persona.region.timeZone?.let { append(" · ").append(it) }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Force desktop", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(checked = persona.desktop, onCheckedChange = { onToggleDesktop() })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onChangePersona) { Text("Change…") }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Recently open", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                AssistChip(onClick = onOpenHomepage, label = { Text("Homepage", maxLines = 1, overflow = TextOverflow.Ellipsis) })
            }
            Spacer(Modifier.height(8.dp))
            if (recent.isEmpty()) {
                Text(
                    "Tabs you open show up here. Tap the address bar to start, or open $homeUrl.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val sizePx = with(LocalDensity.current) { 160.dp.roundToPx() }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recent, key = { it.id }) { tab ->
                        Card(Modifier.width(160.dp).clickable { onOpenTab(tab.id) }) {
                            ThumbnailImage(
                                request = ImageLoadRequest(tab.id, sizePx, tab.content.private),
                                storage = thumbnails,
                                generation = thumbnailGeneration,
                                modifier = Modifier.fillMaxWidth().height(110.dp),
                                fallbackContent = { TabFallback(tab) },
                            )
                            Text(
                                tab.content.title.ifBlank { tab.content.url },
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun TabFallback(tab: TabSessionState) {
    Surface(Modifier.fillMaxWidth().height(110.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.Center) {
            Text(
                tab.content.url.removePrefix("https://").removePrefix("http://").removePrefix("www."),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
