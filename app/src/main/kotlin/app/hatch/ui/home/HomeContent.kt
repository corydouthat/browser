package app.hatch.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hatch.R
import app.hatch.persona.Persona
import app.hatch.persona.Presets
import app.hatch.ui.surfaces.PersonaLabels
import app.hatch.ui.surfaces.PreviewTheme
import app.hatch.ui.surfaces.SurfaceIcons
import app.hatch.ui.surfaces.SurfacePreviewFrame
import app.hatch.ui.surfaces.SurfacePreviewThemes
import app.hatch.ui.surfaces.shouldToggleDesktop
import app.hatch.ui.tabs.Favicon
import app.hatch.ui.tabs.ThumbnailImage
import app.hatch.ui.theme.HatchDimens
import app.hatch.ui.theme.HatchShapes
import app.hatch.ui.theme.HatchTheme
import app.hatch.ui.theme.hatchFocusRing
import mozilla.components.browser.icons.BrowserIcons
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.thumbnails.storage.ThumbnailStorage
import mozilla.components.concept.base.images.ImageLoadRequest

/** Tabs for the "Recently open" grid: everything except the current tab, most recently accessed first. */
internal fun recentTabs(tabs: List<TabSessionState>, currentId: String?, limit: Int = 6): List<TabSessionState> =
    tabs.filter { it.id != currentId && it.content.url != "about:blank" }
        .sortedByDescending { maxOf(it.lastAccess, it.createdAt) }
        .take(limit)

/** Home geometry from the "New tab" mockup (dp). */
internal object HomeDimens {
    val Mark = 44.dp
    val ModeCardHeight = 104.dp
    val ModeCardRadius = 20.dp
    val ModeCardPadding = 14.dp
    val ChipHeight = 36.dp
    val RecentRadius = 16.dp
    val RecentThumbnail = 96.dp
    val RecentRow = 40.dp
    val RecentFavicon = 14.dp
    val FaviconRadius = 4.dp
    val Ring = 2.dp
}

/**
 * The Hatch home ("New tab" mockup): shown over an `about:blank` tab with no history while the toolbar is in edit
 * mode (BrowserScreen), so typing starts immediately. Mark + wordmark, the Desktop / Phone mode cards, the
 * "Homepage" and "Change persona" chips, and a two-column "Recently open" grid. Every callback is wrapped by the
 * caller to leave URL edit mode first (UXP-01).
 *
 * [desktopPersona] is what the Desktop card would apply (`PersonaManager.forceDesktop()`), so its subtitle names the
 * desktop identity even while the phone mode is selected.
 */
@Composable
fun HomeContent(
    persona: Persona,
    desktopPersona: Persona,
    tabs: List<TabSessionState>,
    currentTabId: String?,
    thumbnails: ThumbnailStorage,
    /** `ThumbnailGeneration.generation`: a capture written after this grid composed is picked up (ENG-06). */
    thumbnailGeneration: Long,
    icons: BrowserIcons,
    homeUrl: String,
    onChangePersona: () -> Unit,
    onToggleDesktop: () -> Unit,
    onOpenTab: (String) -> Unit,
    onOpenHomepage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // PERF-08: request thumbnails at the card's width (two columns, 16 dp screen padding, 8 dp gap).
    val cardWidth = ((LocalConfiguration.current.screenWidthDp - 40) / 2).coerceAtLeast(1)
    val sizePx = with(LocalDensity.current) { cardWidth.dp.roundToPx() }
    HomeLayout(
        desktop = persona.desktop,
        desktopLabel = PersonaLabels.full(desktopPersona),
        recent = recentTabs(tabs, currentTabId),
        homeUrl = homeUrl,
        onChangePersona = onChangePersona,
        onSelectMode = { wantDesktop -> if (shouldToggleDesktop(persona.desktop, wantDesktop)) onToggleDesktop() },
        onOpenTab = onOpenTab,
        onOpenHomepage = onOpenHomepage,
        favicon = { tab, size ->
            Favicon(
                url = tab.content.url,
                icons = icons,
                size = size,
                bitmap = tab.content.icon,
                isPrivate = tab.content.private,
                shape = RoundedCornerShape(HomeDimens.FaviconRadius),
            )
        },
        thumbnail = { tab ->
            ThumbnailImage(
                request = ImageLoadRequest(tab.id, sizePx, tab.content.private),
                storage = thumbnails,
                generation = thumbnailGeneration,
                modifier = Modifier.fillMaxSize(),
                fallbackContent = { ThumbnailFallback(tab) },
            )
        },
        modifier = modifier,
    )
}

/** The stateless home (previewable): every engine-backed piece comes in through [favicon] and [thumbnail]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeLayout(
    desktop: Boolean,
    desktopLabel: String,
    recent: List<TabSessionState>,
    homeUrl: String,
    onChangePersona: () -> Unit,
    onSelectMode: (desktop: Boolean) -> Unit,
    onOpenTab: (String) -> Unit,
    onOpenHomepage: () -> Unit,
    favicon: @Composable (TabSessionState, Dp) -> Unit,
    thumbnail: @Composable (TabSessionState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HatchTheme.colors
    Box(modifier.fillMaxSize().background(c.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = HatchDimens.ScreenPadding, end = HatchDimens.ScreenPadding, top = 40.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(HatchDimens.GapMedium),
            ) {
                Image(
                    painterResource(R.drawable.hatch_mark),
                    contentDescription = null,
                    modifier = Modifier.size(HomeDimens.Mark).clip(HatchShapes.Mark),
                )
                Column {
                    Text("Hatch", style = MaterialTheme.typography.headlineSmall, color = c.ink)
                    Text("Desktop web, on the road.", style = MaterialTheme.typography.bodyMedium, color = c.mu)
                }
            }
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(HatchDimens.GapSmall)) {
                ModeCard(
                    selected = desktop,
                    icon = SurfaceIcons.Monitor,
                    title = "Desktop",
                    subtitle = desktopLabel,
                    onClick = { onSelectMode(true) },
                    modifier = Modifier.weight(1f),
                )
                ModeCard(
                    selected = !desktop,
                    icon = SurfaceIcons.Phone,
                    title = "Phone",
                    subtitle = "Real device",
                    onClick = { onSelectMode(false) },
                    modifier = Modifier.weight(1f),
                )
            }
            // A11Y-05: a FlowRow, so at large font scales "Change persona" wraps under "Homepage" instead of being
            // squeezed and clipped; each chip also ellipsizes rather than clip when it alone is wider than the row.
            FlowRow(
                Modifier.padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(HatchDimens.GapSmall),
                verticalArrangement = Arrangement.spacedBy(HatchDimens.GapSmall),
            ) {
                OutlineChip(
                    label = "Homepage",
                    icon = SurfaceIcons.Home,
                    description = "Homepage, $homeUrl",
                    onClick = onOpenHomepage,
                )
                OutlineChip(label = "Change persona", icon = null, description = null, onClick = onChangePersona)
            }
            if (recent.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Recently open",
                        style = MaterialTheme.typography.labelMedium,
                        color = c.mu,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    // Non-lazy two-column grid: the home scrolls as one column and shows at most six cards.
                    recent.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(HatchDimens.GapSmall)) {
                            pair.forEach { tab ->
                                RecentCard(tab, favicon, thumbnail, onClick = { onOpenTab(tab.id) }, modifier = Modifier.weight(1f))
                            }
                            if (pair.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeCard(
    selected: Boolean,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HatchTheme.colors
    val shape = RoundedCornerShape(HomeDimens.ModeCardRadius)
    val content = if (selected) c.ti else c.mu
    Column(
        modifier
            .hatchFocusRing(shape)
            .heightIn(min = HomeDimens.ModeCardHeight)
            .clip(shape)
            .background(if (selected) c.tb else c.s1)
            .then(if (selected) Modifier.border(HomeDimens.Ring, c.ac, shape) else Modifier)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(HomeDimens.ModeCardPadding),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
            if (selected) Icon(SurfaceIcons.Check, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.padding(top = HatchDimens.GapSmall)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = if (selected) c.ti else c.ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = content, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The mockup's outlined chip: 36 dp, r10, 1 dp `ln2` outline, 14 sp. */
@Composable
internal fun OutlineChip(label: String, icon: ImageVector?, description: String?, onClick: () -> Unit) {
    val c = HatchTheme.colors
    Row(
        Modifier
            .hatchFocusRing(HatchShapes.Chip)
            .heightIn(min = HomeDimens.ChipHeight)
            .clip(HatchShapes.Chip)
            .border(HatchDimens.Hairline, c.ln2, HatchShapes.Chip)
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = HatchDimens.GapMedium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal),
            color = c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
private fun RecentCard(
    tab: TabSessionState,
    favicon: @Composable (TabSessionState, Dp) -> Unit,
    thumbnail: @Composable (TabSessionState) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HatchTheme.colors
    val shape = RoundedCornerShape(HomeDimens.RecentRadius)
    Column(
        modifier
            .hatchFocusRing(shape)
            .clip(shape)
            .background(c.s1)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().height(HomeDimens.RecentThumbnail).background(c.s2)) { thumbnail(tab) }
        Row(
            Modifier.fillMaxWidth().heightIn(min = HomeDimens.RecentRow).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HatchDimens.GapSmall),
        ) {
            favicon(tab, HomeDimens.RecentFavicon)
            Text(
                tab.content.title.ifBlank { tab.content.url },
                style = MaterialTheme.typography.bodyMedium,
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** No capture yet: the host on the neutral thumbnail tone. */
@Composable
private fun ThumbnailFallback(tab: TabSessionState) {
    Box(Modifier.fillMaxSize().background(HatchTheme.colors.s2).padding(10.dp)) {
        Text(
            tab.content.url.removePrefix("https://").removePrefix("http://").removePrefix("www."),
            style = MaterialTheme.typography.bodySmall,
            color = HatchTheme.colors.mu,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---- previews ------------------------------------------------------------------------------------------------

internal val previewTabs: List<TabSessionState> by lazy {
    listOf(
        createTab("https://console.aws.amazon.com/", title = "AWS Console", id = "p1"),
        createTab("https://docs.google.com/spreadsheets/d/x", title = "Trip budget – Google Docs", id = "p2"),
        createTab("https://secure.chase.com/web/auth", title = "Chase – Accounts", id = "p3"),
        createTab("https://maps.google.com/", title = "Maps", id = "p4"),
    )
}

private val previewSwatches: List<Color> by lazy { listOf(Color(0xFF232A33), Color(0xFF2F6FDB), Color(0xFF1A4C8C), Color(0xFF34A853)) }

/** Preview stand-in for a favicon: the mockup's coloured r4 square. */
@Composable
internal fun PreviewFavicon(tab: TabSessionState, size: Dp) {
    val i = previewTabs.indexOfFirst { it.id == tab.id }.coerceAtLeast(0)
    Box(Modifier.size(size).clip(RoundedCornerShape(HomeDimens.FaviconRadius)).background(previewSwatches[i % previewSwatches.size]))
}

/** Preview stand-in for a thumbnail: a light page with the site's header bar. */
@Composable
internal fun PreviewThumbnail(tab: TabSessionState) {
    val i = previewTabs.indexOfFirst { it.id == tab.id }.coerceAtLeast(0)
    Column(Modifier.fillMaxSize().background(Color(0xFFF4F5F6))) {
        Box(Modifier.fillMaxWidth().height(12.dp).background(previewSwatches[i % previewSwatches.size]))
    }
}

@Preview(widthDp = 360, heightDp = 700)
@Composable
internal fun HomePreview(@PreviewParameter(SurfacePreviewThemes::class) theme: PreviewTheme) {
    SurfacePreviewFrame(theme) {
        HomeLayout(
            desktop = true,
            desktopLabel = PersonaLabels.full(Presets.default()),
            recent = previewTabs.take(2),
            homeUrl = "https://start.duckduckgo.com",
            onChangePersona = {},
            onSelectMode = {},
            onOpenTab = {},
            onOpenHomepage = {},
            favicon = { tab, size -> PreviewFavicon(tab, size) },
            thumbnail = { tab -> PreviewThumbnail(tab) },
        )
    }
}

@Preview(widthDp = 360, heightDp = 700)
@Composable
internal fun HomePhonePreview(@PreviewParameter(SurfacePreviewThemes::class) theme: PreviewTheme) {
    SurfacePreviewFrame(theme) {
        HomeLayout(
            desktop = false,
            desktopLabel = PersonaLabels.full(Presets.default()),
            recent = emptyList(),
            homeUrl = "https://start.duckduckgo.com",
            onChangePersona = {},
            onSelectMode = {},
            onOpenTab = {},
            onOpenHomepage = {},
            favicon = { tab, size -> PreviewFavicon(tab, size) },
            thumbnail = { tab -> PreviewThumbnail(tab) },
        )
    }
}
