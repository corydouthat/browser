package app.hatch.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hatch.Components
import app.hatch.applinks.AppLinksMode
import app.hatch.hostrewrites.HostRewriteFilter
import app.hatch.persona.UrlRewriteRules
import app.hatch.settings.AppSettings
import app.hatch.ui.surfaces.PreviewTheme
import app.hatch.ui.surfaces.SurfacePreviewFrame
import app.hatch.ui.surfaces.SurfacePreviewThemes
import app.hatch.ui.theme.HatchPalette
import app.hatch.ui.theme.HatchTheme
import app.hatch.ui.theme.HatchType
import app.hatch.ui.theme.ThemeMode
import app.hatch.ui.theme.hatchFocusRing
import kotlinx.coroutines.launch

/** Settings › Home page: the URL loaded into the first tab and behind the home screen's "Homepage" chip. */
@Composable
internal fun HomePageSettingsPage(settings: AppSettings, onBack: () -> Unit) {
    val tick by settings.changes.collectAsState()
    var home by remember(tick) { mutableStateOf(settings.homeUrl) }
    SettingsPageScaffold("Home page", onBack) {
        PageNote("Opens in the first tab and from the Homepage chip on a new tab.")
        FormField(
            "Home page URL", home, { home = it; settings.homeUrl = it },
            valueStyle = HatchType.monoLarge, keyboardType = KeyboardType.Uri, placeholder = AppSettings.DEFAULT_HOME,
        )
    }
}

/**
 * Settings › Appearance: the six palettes as swatch chips and System / Light / Dark. A palette applies live
 * (MainActivity re-reads it on `settings.changes`); a theme mode recreates the window (AppCompat night mode).
 */
@Composable
internal fun AppearanceSettingsPage(settings: AppSettings, onBack: () -> Unit) {
    val tick by settings.changes.collectAsState()
    val palette = remember(tick) { settings.palette }
    val mode = remember(tick) { settings.themeMode }
    SettingsPageScaffold("Appearance", onBack) {
        AppearanceContent(palette, mode, onPalette = { settings.palette = it }, onMode = { settings.themeMode = it })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppearanceContent(palette: HatchPalette, mode: ThemeMode, onPalette: (HatchPalette) -> Unit, onMode: (ThemeMode) -> Unit) {
    val c = HatchTheme.colors
    GroupTitle("Palette")
    GroupedCard {
        FlowRow(
            Modifier.fillMaxWidth().padding(12.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HatchPalette.entries.forEach { p ->
                val on = p == palette
                Box(Modifier.minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
                    Row(
                        Modifier
                            .heightIn(min = 36.dp)
                            .hatchFocusRing(RoundedCornerShape(18.dp))
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (on) c.ac else c.bg)
                            .selectable(selected = on, role = Role.RadioButton) { onPalette(p) }
                            .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            Modifier
                                .size(24.dp)
                                .border(2.dp, c.bg, CircleShape)
                                .clip(CircleShape)
                                .background(p.tokens(c.isDark).acc),
                        )
                        Text(
                            p.label,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                            color = if (on) c.oa else c.ink,
                        )
                    }
                }
            }
        }
    }
    GroupTitle("Theme")
    SegmentedTabs(ThemeMode.entries.map { it.label }, ThemeMode.entries.indexOf(mode), { onMode(ThemeMode.entries[it]) })
    PageNote("The toolbar takes the palette's tint in desktop mode; everything else stays neutral. Changing the theme redraws the window.")
}

/** Settings › Toolbar: scroll-to-hide. */
@Composable
internal fun ToolbarSettingsPage(settings: AppSettings, onBack: () -> Unit) {
    val tick by settings.changes.collectAsState()
    val hide = remember(tick) { settings.hideToolbarOnScroll }
    SettingsPageScaffold("Toolbar", onBack) {
        GroupedCard {
            SwitchCardRow(
                "Hide when scrolling",
                "Scroll-to-hide like Chrome and Firefox for Android. Off by default until validated on a device; the page gains the toolbar height while it is hidden.",
                hide,
                { settings.hideToolbarOnScroll = it },
            )
        }
    }
}

/** Settings › Default persona: the identity and region used on first start and when the saved selection is gone. */
@Composable
internal fun DefaultPersonaSettingsPage(components: Components, onBack: () -> Unit) {
    val settings = components.settings
    val tick by settings.changes.collectAsState()
    val identities by components.personaManager.identities.collectAsState()
    val regions by components.personaManager.regions.collectAsState()
    val identity = remember(tick) { settings.defaultIdentityId }
    val region = remember(tick) { settings.defaultRegionId }
    SettingsPageScaffold("Default persona", onBack) {
        PageNote("Used on first start and when the saved selection no longer exists.")
        GroupTitle("Identity")
        ChoiceCard(identities.map { it.id to it.name }, identity) { settings.defaultIdentityId = it }
        GroupTitle("Region")
        ChoiceCard(regions.map { it.id to it.name }, region) { settings.defaultRegionId = it }
    }
}

/** A card of single-choice rows (radio on the trailing side, the whole row selects). */
@Composable
internal fun ChoiceCard(options: List<Pair<String, String>>, selected: String, summaries: Map<String, String> = emptyMap(), onSelect: (String) -> Unit) {
    val c = HatchTheme.colors
    GroupedCard(Modifier.selectableGroup()) {
        CardRows(options) { (id, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .hatchFocusRing(CardRowFocusShape, inside = true)
                    .selectable(selected = id == selected, role = Role.RadioButton) { onSelect(id) }
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.layout.Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, color = c.ink)
                    summaries[id]?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.mu) }
                }
                RadioButton(selected = id == selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = c.ac, unselectedColor = c.mu))
            }
        }
    }
}

/** Settings › Host rewrites: the extension's mobile → desktop host table, one switch per rule. */
@Composable
internal fun HostRewritesSettingsPage(components: Components, onBack: () -> Unit) {
    val settings = components.settings
    val tick by settings.changes.collectAsState()
    val disabled = remember(tick) { settings.disabledHostRewrites }
    val c = HatchTheme.colors
    SettingsPageScaffold("Host rewrites", onBack) {
        PageNote(
            "While a desktop persona is active, main-frame requests to these mobile hosts are redirected to the desktop host " +
                "(10 s per-tab loop guard). Switch one off if a site needs its mobile host.",
        )
        GroupedCard {
            CardRows(UrlRewriteRules.extensionHostRewrites) { rule ->
                val on = rule.from !in disabled
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .hatchFocusRing(CardRowFocusShape, inside = true)
                        // A11Y-01: toggleable (On/Off), not selectable ("Selected").
                        .toggleable(value = on, role = Role.Switch) { want ->
                            settings.disabledHostRewrites = HostRewriteFilter.toggle(UrlRewriteRules.extensionHostRewrites, settings.disabledHostRewrites, rule.from, want)
                            components.bridge.sendHostRewrites(components.effectiveHostRewrites())
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        HostRewriteFilter.label(rule),
                        style = if (HostRewriteFilter.isRegex(rule)) MaterialTheme.typography.bodyLarge else HatchType.monoMedium,
                        color = c.ink,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.Switch(checked = on, onCheckedChange = null, colors = hatchSwitchColors())
                }
            }
        }
        PageNote(
            "Switches apply to followed links and to typed or pasted URLs alike. The generic strip is a catch-all: to keep a mobile host " +
                "that it matches, switch it off too. Typed URLs also get Amazon /gp/aw/ and mf_useformat / app=m query rewrites (address bar only).",
        )
    }
}

/** Settings › Compatibility: keep touch events for gesture sites, and the time-zone JS shim fallback. */
@Composable
internal fun CompatibilitySettingsPage(components: Components, onBack: () -> Unit) {
    val settings = components.settings
    val scope = rememberCoroutineScope()
    val tick by settings.changes.collectAsState()
    val keepTouch = remember(tick) { settings.keepTouchEvents }
    val tzShim = remember(tick) { settings.tzShim }
    SettingsPageScaffold("Compatibility", onBack) {
        GroupedCard {
            SwitchCardRow(
                "Keep touch events",
                "For sites that need gestures (maps, drawing tools, carousels): leaves dom.w3c_touch_events.enabled on for desktop personas. " +
                    "document.createEvent(\"TouchEvent\") becomes a tell.",
                keepTouch,
                { on ->
                    settings.keepTouchEvents = on
                    scope.launch { components.personaManager.reapplyPrefs(); components.sessionUseCases.reload() }
                },
            )
            CardDivider()
            SwitchCardRow(
                "Time-zone shim (fallback)",
                "Patch Date / Intl in pages from a precomputed offset table. Only if TZ does not reach content.",
                tzShim,
                { on ->
                    settings.tzShim = on
                    scope.launch { components.personaManager.resendShim(); components.sessionUseCases.reload() }
                },
            )
        }
    }
}

/**
 * Settings › Logins & passkeys: Hatch's own logins store (docs/research/google-signin-autofill.md Path 2): autofill and
 * offer-to-save switches, Saved logins, the never-save list and the passkey status.
 */
@Composable
internal fun LoginsSettingsPage(components: Components, onBack: () -> Unit) {
    val settings = components.settings
    val tick by settings.changes.collectAsState()
    val autofill = remember(tick) { settings.loginAutofill }
    val save = remember(tick) { settings.saveLogins }
    val neverSave by components.logins.exceptions.origins.collectAsState()
    SettingsPageScaffold("Logins & passkeys", onBack) {
        GroupedCard {
            SwitchCardRow(
                "Autofill saved logins",
                "Hatch's own store (not Google Password Manager). One match fills silently; several show a picker above the keyboard.",
                autofill,
                { on ->
                    settings.loginAutofill = on
                    components.engine.settings.loginAutofillEnabled = on
                },
            )
            CardDivider()
            SwitchCardRow(
                "Offer to save logins",
                "Ask after a sign-in form is submitted. \"Never save\" answers are kept per site.",
                save,
                { settings.saveLogins = it },
            )
        }
        GroupedCard {
            CardRow("Saved logins", summary = "Behind your screen lock", icon = ScreenIcons.Key, onClick = { components.loginsUi.savedLoginsOpen = true }) {
                Icon(ScreenIcons.ChevronRight, contentDescription = null, tint = HatchTheme.colors.mu, modifier = Modifier.size(20.dp))
            }
            if (neverSave.isNotEmpty()) {
                CardDivider()
                CardRow(
                    "Never-save sites",
                    summary = "${neverSave.size} site${if (neverSave.size == 1) "" else "s"}",
                    icon = ScreenIcons.Shield,
                ) {
                    TextPill("Forget", onClick = { components.logins.exceptions.clear() }, height = 36.dp)
                }
            }
        }
        PageNote(
            "Passkeys: wired through Android Credential Manager / Google Play services, but Google only serves allow-listed browsers " +
                "(request pending for the release signature; debug builds never). Until then use \"Open in another browser\" for passkey logins.",
        )
    }
}

/** Settings › App links: what a link an installed app claims does. */
@Composable
internal fun AppLinksSettingsPage(settings: AppSettings, onBack: () -> Unit) {
    val tick by settings.changes.collectAsState()
    val mode = remember(tick) { settings.appLinksMode }
    SettingsPageScaffold("App links", onBack) {
        PageNote("What happens when an installed app (YouTube, Reddit, Maps…) claims a link.")
        ChoiceCard(
            AppLinksMode.entries.map { it.key to it.title },
            mode.key,
            summaries = AppLinksMode.entries.associate { it.key to it.description },
        ) { key -> settings.appLinksMode = AppLinksMode.fromKey(key) }
    }
}

@Preview(name = "Settings · Appearance", widthDp = 360, heightDp = 640)
@Composable
internal fun AppearancePreview(@PreviewParameter(SurfacePreviewThemes::class) theme: PreviewTheme) {
    SurfacePreviewFrame(theme) {
        androidx.compose.foundation.layout.Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppearanceContent(theme.palette, ThemeMode.System, {}, {})
        }
    }
}

@Preview(name = "Settings · App links choices", widthDp = 360, heightDp = 480)
@Composable
internal fun ChoiceCardPreview(@PreviewParameter(SurfacePreviewThemes::class) theme: PreviewTheme) {
    SurfacePreviewFrame(theme) {
        Box(Modifier.padding(16.dp).background(Color.Transparent)) {
            ChoiceCard(AppLinksMode.entries.map { it.key to it.title }, AppLinksMode.DEFAULT.key, AppLinksMode.entries.associate { it.key to it.description }) {}
        }
    }
}
