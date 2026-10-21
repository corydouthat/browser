package app.hatch.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import app.hatch.BuildConfig
import app.hatch.Components
import app.hatch.persona.UrlRewriteRules
import app.hatch.rules.RulesUiState
import app.hatch.ui.SettingsPage
import app.hatch.ui.rules.RuleText
import app.hatch.ui.surfaces.PersonaLabels
import app.hatch.ui.surfaces.PreviewTheme
import app.hatch.ui.surfaces.SurfacePreviewFrame
import app.hatch.ui.surfaces.SurfacePreviewThemes
import app.hatch.ui.theme.HatchTheme
import app.hatch.ui.theme.hatchFocusRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.lib.state.ext.observeAsComposableState

/**
 * The Settings root (mockup "Settings · root"): four grouped cards (General, Desktop persona, Privacy & sites, More),
 * each row a 20 dp icon, title and a live summary, then the mono build footer. A row pushes its [SettingsPage], or opens
 * the persona editor / site permissions / site rules overlays. The search action filters the rows; its state is
 * [app.hatch.ui.AppUiState.settingsSearch] ([search], null = not searching), so the system back and Esc both close the
 * search first through `AppUiState.onBack` (RS-05).
 */
@Composable
internal fun SettingsRootPage(
    components: Components,
    onClose: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
    search: String?,
    onSearch: (String?) -> Unit,
) {
    val settings = components.settings
    val tick by settings.changes.collectAsState()
    val identities by components.personaManager.identities.collectAsState()
    val regions by components.personaManager.regions.collectAsState()
    val rules by components.rulesRepository.rules.collectAsState()
    val extensions by components.store.observeAsComposableState { it.extensions }
    var sitesWithPermissions by remember { mutableStateOf<Int?>(null) }
    var savedLogins by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        sitesWithPermissions = runCatching { withContext(Dispatchers.IO) { components.sitePermissionsStorage.all().size } }.getOrNull()
    }
    LaunchedEffect(Unit) {
        // `LoginsStorage.count()` (concept-storage LoginsStorage.kt:164) counts rows without decrypting them.
        savedLogins = runCatching { withContext(Dispatchers.IO) { components.logins.storage.value.count() } }.getOrNull()
    }

    val summaries = remember(tick, identities, regions, rules, extensions, sitesWithPermissions, savedLogins) {
        val presetIdentities = identities.count { components.personaManager.isPreset(it) }
        val presetRegions = regions.count { components.personaManager.isPreset(it) }
        val disabled = settings.disabledHostRewrites
        mapOf(
            SettingsEntry.HomePage to settings.homeUrl,
            SettingsEntry.Appearance to SettingsModel.appearance(settings.palette, settings.themeMode),
            SettingsEntry.Toolbar to SettingsModel.toolbar(settings.hideToolbarOnScroll),
            SettingsEntry.DefaultPersona to SettingsModel.defaultPersona(
                identities.firstOrNull { it.id == settings.defaultIdentityId }?.let { PersonaLabels.short(it.name) },
                regions.firstOrNull { it.id == settings.defaultRegionId }?.let { PersonaLabels.short(it.name) },
            ),
            SettingsEntry.IdentitiesRegions to SettingsModel.identitiesAndRegions(
                presetIdentities + presetRegions,
                identities.size - presetIdentities + regions.size - presetRegions,
            ),
            SettingsEntry.HostRewrites to SettingsModel.hostRewrites(UrlRewriteRules.extensionHostRewrites.count { it.from !in disabled }),
            SettingsEntry.Compatibility to SettingsModel.COMPATIBILITY,
            SettingsEntry.LoginsPasskeys to SettingsModel.logins(settings.loginAutofill, savedLogins),
            SettingsEntry.SitePermissions to SettingsModel.sitePermissions(sitesWithPermissions),
            SettingsEntry.SiteRules to RuleText.enabledSummary(rules),
            SettingsEntry.AppLinks to settings.appLinksMode.title,
            SettingsEntry.Addons to SettingsModel.addons(extensions.values.count { !it.isBuiltIn }),
            SettingsEntry.Developer to SettingsModel.DEVELOPER,
        )
    }

    SettingsRootContent(
        summaries = summaries,
        footer = SettingsModel.footer(
            BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.DEBUG,
            BuildConfig.GIT_SHA, BuildConfig.GIT_BRANCH, BuildConfig.BUILD_TIME,
        ),
        onClose = onClose,
        search = search,
        onSearch = onSearch,
        onOpen = { entry ->
            when (entry) {
                SettingsEntry.IdentitiesRegions -> components.rulesUi.show(RulesUiState.Screen.PersonaEditor)
                SettingsEntry.SitePermissions -> components.rulesUi.show(RulesUiState.Screen.SitePermissions)
                SettingsEntry.SiteRules -> components.rulesUi.show(RulesUiState.Screen.SiteRules)
                else -> entry.page?.let(onOpenPage)
            }
        },
    )
}

/** The root page's layout (stateless; previewable). [search] null = the title bar, else the search bar and its query. */
@Composable
internal fun SettingsRootContent(
    summaries: Map<SettingsEntry, String>,
    footer: List<String>,
    onClose: () -> Unit,
    onOpen: (SettingsEntry) -> Unit,
    search: String? = null,
    onSearch: (String?) -> Unit = {},
) {
    val c = HatchTheme.colors
    val query = search.orEmpty()
    val shown = SettingsModel.visible(summaries, query)

    Column(Modifier.fillMaxSize()) {
        if (search != null) {
            SearchBar(query, { onSearch(it) }, onCloseSearch = { onSearch(null) })
        } else {
            // DF-07: the root bar's trailing action is a 48 dp icon button, so the mockup's `padding:0 4px`.
            ScreenTopBar("Settings", onNav = onClose, endPadding = TopBarEndPaddingIcon) {
                IconButton(onClick = { onSearch("") }, modifier = Modifier.hatchFocusRing(CircleShape)) {
                    Icon(ScreenIcons.Search, contentDescription = "Search settings", tint = c.ink, modifier = Modifier.size(22.dp))
                }
            }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SettingsGroup.entries.forEach { group ->
                val rows = shown.filter { it.group == group }
                if (rows.isEmpty()) return@forEach
                GroupTitle(group.title)
                GroupedCard {
                    CardRows(rows) { entry ->
                        CardRow(title = entry.title, summary = summaries[entry], icon = entryIcon(entry), onClick = { onOpen(entry) })
                    }
                }
            }
            if (shown.isEmpty()) PageNote("No setting matches \"$query\".", Modifier.padding(top = 16.dp))
            MonoFooter(footer)
        }
    }
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onCloseSearch: () -> Unit) {
    val c = HatchTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .heightIn(min = 64.dp)
            .padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCloseSearch, modifier = Modifier.hatchFocusRing(CircleShape)) { Icon(ScreenIcons.Back, contentDescription = "Close search", tint = c.ink, modifier = Modifier.size(22.dp)) }
        Box(Modifier.weight(1f).padding(start = 4.dp)) {
            if (query.isEmpty()) Text("Search settings", style = MaterialTheme.typography.titleLarge, color = c.mu)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.copy(color = c.ink),
                cursorBrush = SolidColor(c.ac),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Search settings" },
            )
        }
    }
}

private fun entryIcon(e: SettingsEntry): ImageVector = when (e) {
    SettingsEntry.HomePage -> ScreenIcons.Home
    SettingsEntry.Appearance -> ScreenIcons.Palette
    SettingsEntry.Toolbar -> ScreenIcons.Toolbar
    SettingsEntry.DefaultPersona -> ScreenIcons.Monitor
    SettingsEntry.IdentitiesRegions -> ScreenIcons.Tune
    SettingsEntry.HostRewrites -> ScreenIcons.Swap
    SettingsEntry.Compatibility -> ScreenIcons.Tune
    SettingsEntry.LoginsPasskeys -> ScreenIcons.Key
    SettingsEntry.SitePermissions -> ScreenIcons.Shield
    SettingsEntry.SiteRules -> ScreenIcons.Code
    SettingsEntry.AppLinks -> ScreenIcons.Link
    SettingsEntry.Addons -> ScreenIcons.Puzzle
    SettingsEntry.Developer -> ScreenIcons.Terminal
}

/**
 * A Settings sub-page: the design's top bar (back pops the page) over a scrolling column with the screen padding and
 * the 12 dp gap between blocks.
 */
@Composable
internal fun SettingsPageScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title, onNav = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding() // UXP-13: the text fields (Home URL, add-on URL) stay above the keyboard
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Preview(name = "Settings · root", widthDp = 360, heightDp = 1320)
@Composable
internal fun SettingsRootPreview(@PreviewParameter(SurfacePreviewThemes::class) theme: PreviewTheme) {
    val summaries = mapOf(
        SettingsEntry.HomePage to "https://www.google.com/",
        SettingsEntry.Appearance to SettingsModel.appearance(theme.palette, app.hatch.ui.theme.ThemeMode.System),
        SettingsEntry.Toolbar to SettingsModel.toolbar(false),
        SettingsEntry.DefaultPersona to "Windows · Firefox 158 · Device",
        SettingsEntry.IdentitiesRegions to SettingsModel.identitiesAndRegions(10, 1),
        SettingsEntry.HostRewrites to SettingsModel.hostRewrites(6),
        SettingsEntry.Compatibility to SettingsModel.COMPATIBILITY,
        SettingsEntry.LoginsPasskeys to SettingsModel.logins(true, 23),
        SettingsEntry.SitePermissions to SettingsModel.sitePermissions(8),
        SettingsEntry.SiteRules to "2 of 4 on",
        SettingsEntry.AppLinks to "Stay in Hatch",
        SettingsEntry.Addons to SettingsModel.addons(2),
        SettingsEntry.Developer to SettingsModel.DEVELOPER,
    )
    SurfacePreviewFrame(theme) {
        SettingsRootContent(summaries, SettingsModel.footer("0.1.0", 22, false, "", "", ""), onClose = {}, onOpen = {})
    }
}
