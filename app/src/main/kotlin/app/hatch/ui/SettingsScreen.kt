package app.hatch.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.hatch.Components
import app.hatch.ui.settings.AddonsSettingsPage
import app.hatch.ui.settings.AppLinksSettingsPage
import app.hatch.ui.settings.AppearanceSettingsPage
import app.hatch.ui.settings.CompatibilitySettingsPage
import app.hatch.ui.settings.DefaultPersonaSettingsPage
import app.hatch.ui.settings.DeveloperSettingsPage
import app.hatch.ui.settings.HomePageSettingsPage
import app.hatch.ui.settings.HostRewritesSettingsPage
import app.hatch.ui.settings.LoginsSettingsPage
import app.hatch.ui.settings.SettingsRootPage
import app.hatch.ui.settings.ToolbarSettingsPage
import app.hatch.ui.theme.HatchTheme

/**
 * Settings (design section 04, "Settings, reorganised"): the root page (`ui/settings/SettingsRoot.kt`) or the
 * [page] on top of `AppUiState.settingsStack` (`ui/settings/SettingsPages.kt`, `AddonsPage.kt`, `DeveloperPage.kt`).
 * Every page's top-bar back calls [onBack] (pops the page; closes Settings from the root), the same path as the system
 * back. [snackbar] is HatchApp's shared state: nothing under `ui/settings` hosts it.
 *
 * The Developer page's Eruda row is the "Eruda in menu" switch (`AppSettings.erudaInMenu`, mockup "Settings ·
 * Developer"); toggling Eruda on the current tab stays with the menu item and Ctrl+Shift+I.
 */
@Composable
fun SettingsScreen(
    components: Components,
    onClose: () -> Unit,
    onRestart: () -> Unit,
    snackbar: SnackbarHostState,
    // hatch:toolbar begin — the seam for the Settings redesign (screens author renders the pages): the page on top of
    // AppUiState.settingsStack (null = root), push a page, back (pops a page, closes from the root), and the
    // Developer page's Self-check action (closes Settings first, then runs).
    page: SettingsPage?,
    onOpenPage: (SettingsPage) -> Unit,
    onBack: () -> Unit,
    onSelfCheck: () -> Unit,
    // hatch:toolbar end
    // RS-05: the root page's search ([AppUiState.settingsSearch]; null = not searching), so back and Esc close it alike.
    search: String? = null,
    onSearch: (String?) -> Unit = {},
) {
    val c = HatchTheme.colors
    Surface(Modifier.fillMaxSize(), color = c.bg, contentColor = c.ink) {
        // A11Y-03 / A11Y-04: focus and TalkBack enter Settings when it opens; content clears a landscape cutout.
        Box(Modifier.fillMaxSize().overlayPane("Settings")) {
            SettingsPageContent(components, page, onClose, onRestart, snackbar, onOpenPage, onBack, onSelfCheck, search, onSearch)
        }
    }
}

@Composable
private fun SettingsPageContent(
    components: Components,
    page: SettingsPage?,
    onClose: () -> Unit,
    onRestart: () -> Unit,
    snackbar: SnackbarHostState,
    onOpenPage: (SettingsPage) -> Unit,
    onBack: () -> Unit,
    onSelfCheck: () -> Unit,
    search: String?,
    onSearch: (String?) -> Unit,
) {
    when (page) {
        null -> SettingsRootPage(components, onClose = onClose, onOpenPage = onOpenPage, search = search, onSearch = onSearch)
        SettingsPage.HomePage -> HomePageSettingsPage(components.settings, onBack)
        SettingsPage.Appearance -> AppearanceSettingsPage(components.settings, onBack)
        SettingsPage.Toolbar -> ToolbarSettingsPage(components.settings, onBack)
        SettingsPage.DefaultPersona -> DefaultPersonaSettingsPage(components, onBack)
        SettingsPage.HostRewrites -> HostRewritesSettingsPage(components, onBack)
        SettingsPage.Compatibility -> CompatibilitySettingsPage(components, onBack)
        SettingsPage.LoginsPasskeys -> LoginsSettingsPage(components, onBack)
        SettingsPage.AppLinks -> AppLinksSettingsPage(components.settings, onBack)
        SettingsPage.Addons -> AddonsSettingsPage(components, snackbar, onBack)
        SettingsPage.Developer -> DeveloperSettingsPage(components, snackbar, onBack, onRestart, onSelfCheck)
    }
}
