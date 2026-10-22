package app.hatch.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.TestName
import app.hatch.crash.RecoveryScreenDarkPreview
import app.hatch.crash.RecoveryScreenLightPreview
import app.hatch.ui.HatchSnackbar
import app.hatch.ui.PersonaSheetPreview
import app.hatch.ui.SitePermissionsPreview
import app.hatch.ui.home.ContentCrashedState
import app.hatch.ui.home.HomePreview
import app.hatch.ui.logins.LoginChip
import app.hatch.ui.logins.LoginPickerLayout
import app.hatch.ui.logins.SavedLoginDetailPreview
import app.hatch.ui.logins.SavedLoginsPreview
import app.hatch.ui.menu.MainMenuPreview
import app.hatch.ui.persona.IdentityFormPreview
import app.hatch.ui.persona.PersonaEditorListPreview
import app.hatch.ui.rules.RuleEditorPreview
import app.hatch.ui.rules.SiteRulesPreview
import app.hatch.ui.settings.DeveloperPreview
import app.hatch.ui.settings.SettingsRootPreview
import app.hatch.ui.surfaces.PreviewTheme
import app.hatch.ui.tabs.TabTrayEmptyPreview
import app.hatch.ui.tabs.TabTrayPreview
import app.hatch.ui.tabs.TabTraySelectionPreview
import app.hatch.ui.theme.HatchDimens
import app.hatch.ui.theme.HatchPalette
import app.hatch.ui.theme.HatchShapes
import app.hatch.ui.theme.HatchTheme
import app.hatch.ui.theme.ThemeMode
import app.hatch.ui.theme.ThemePreview
import app.hatch.ui.toolbar.DisplayToolbar
import app.hatch.ui.toolbar.DisplayToolbarModel
import app.hatch.ui.find.FindRow
import app.hatch.ui.toolbar.EditToolbar
import app.hatch.ui.toolbar.ToolbarColors
import app.hatch.ui.toolbar.ToolbarState
import app.hatch.ui.toolbar.rememberToolbarColors
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.android.resources.ScreenRatio
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo

/**
 * Design renders: one snapshot per mockup (`data-screen-label` in docs/design/hatch-screens.dc.html) in Graphite light,
 * Graphite dark and Phosphor light, at 360 × 760 dp (density 2.625, Pixel-like). They reuse the redesigned
 * composables' own `@Preview` functions and stateless layouts with the mockups' sample data; the browser frames add
 * a 32 dp status strip in the toolbar tint (Paparazzi has no system bars) and a plain white stand-in for web content.
 *
 * Not part of the everyday unit-test run: app/build.gradle.kts applies the Paparazzi plugin and adds this source dir
 * only when a Paparazzi task runs or `-Phatch.snapshots=true` is set. The snapshot name is `<screen-slug>__<palette>-<mode>`.
 * "Browser · Page error" is Hatch's HTML error page (assets/errorpages), rendered by headless Firefox instead; this
 * class renders its browser frame (`browser-page-error-frame`) for the page to be composited into.
 *
 * DF-13 / A11Y-14: tall pages (Settings root, Developer) render at the mockups' 360 × 1320 dp; the editors include
 * their top bars; and an accessibility set renders key screens at 200 % font scale (`*__a11y-font200`), in RTL
 * (`*__a11y-rtl`) and in landscape 760 × 360 dp (`*__a11y-landscape`), all Graphite light.
 */
class DesignScreenshotTest {
    private lateinit var paparazzi: Paparazzi

    @BeforeEach
    fun setup(info: TestInfo) {
        // supportsRtl: layoutlib only honours an RTL DeviceConfig with it; useDeviceResolution: keep the 2.625x pixels
        // (Paparazzi otherwise scales every snapshot down to 1000 px).
        paparazzi = Paparazzi(
            deviceConfig = PHONE,
            theme = "android:Theme.Material.Light.NoActionBar",
            supportsRtl = true,
            useDeviceResolution = true,
        ).apply {
            setup(TestName(packageName = "app.hatch.screenshots", className = "Design", methodName = info.testMethod.get().name))
        }
    }

    @AfterEach
    fun teardown() = paparazzi.teardown()

    private fun shoot(slug: String, themes: List<PreviewTheme> = THEMES, device: DeviceConfig = PHONE, content: @Composable (PreviewTheme) -> Unit) {
        paparazzi.unsafeUpdateConfig(deviceConfig = device)
        themes.forEach { theme -> paparazzi.snapshot(name = "${slug}__${theme.fileSuffix()}") { content(theme) } }
    }

    /** One accessibility variant ([variant] = font200 / rtl / landscape) in Graphite light. */
    private fun shootA11y(slug: String, variant: String, device: DeviceConfig, rtl: Boolean = false, content: @Composable (PreviewTheme) -> Unit) {
        paparazzi.unsafeUpdateConfig(deviceConfig = device)
        val t = PreviewTheme(HatchPalette.Graphite, dark = false)
        paparazzi.snapshot(name = "${slug}__a11y-$variant") {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) { content(t) }
        }
    }

    @Test fun browserFindUndo() = shoot("browser-find-undo") { t ->
        BrowserFrame(t, desktop = false, chrome = { colors ->
            FindRow("invoice", "3/12", colors, {}, {}, {}, {}, requestFocus = false)
        }) {
            Box(Modifier.fillMaxSize()) {
                FakePage("Invoices", listOf("Invoice #4410 · Sep", "Invoice #4411 · Sep", "Invoice #4412 · Oct", "Invoice #4413 · Oct", "Credit note #88"))
                Box(Modifier.align(Alignment.BottomCenter).padding(12.dp)) { HatchSnackbar(FakeSnackbar("Tab closed", "Undo")) }
            }
        }
    }

    @Test fun browserLoginPicker() = shoot("browser-login-picker") { t ->
        BrowserFrame(t, desktop = true, chrome = { colors -> Display(colors, "secure.chase.com", desktop = true) }) {
            Box(Modifier.fillMaxSize()) {
                FakePage("Sign in", listOf("Username", "Password"))
                LoginPickerLayout(
                    chips = listOf(LoginChip("alex.r", "chase.com"), LoginChip("travel-acct", "chase.com")),
                    onSelect = {},
                    onManage = {},
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    @Test fun browserContentCrashed() = shoot("browser-content-crashed") { t ->
        BrowserFrame(t, desktop = false, chrome = { colors -> Display(colors, "docs.google.com", desktop = false) }) {
            ContentCrashedState(onRestore = {}, onCloseTab = {})
        }
    }

    @Test fun newTab() = shoot("new-tab") { t ->
        BrowserFrame(t, desktop = true, chrome = { colors ->
            EditToolbar(
                query = "", hint = "Search or enter address", suggestion = null, isQueryPrefilled = false,
                endActions = emptyList(), tabCount = 5, colors = colors, onUrlEdit = {}, onUrlCommitted = {}, onEvent = {},
            )
        }) {
            HomePreview(t)
        }
    }

    @Test fun tabTray() = shoot("tab-tray") { TabTrayPreview(it) }

    @Test fun tabTraySelection() = shoot("tab-tray-selection") { TabTraySelectionPreview(it) }

    @Test fun tabTrayEmpty() = shoot("tab-tray-empty") { TabTrayEmptyPreview(it) }

    @Test fun mainMenu() = shoot("main-menu") { t ->
        SheetOver(t, url = "console.aws.amazon.com") { MainMenuPreview(t) }
    }

    @Test fun personaSheet() = shoot("persona-sheet") { t ->
        SheetOver(t, url = "console.aws.amazon.com") { PersonaSheetPreview(t) }
    }

    @Test fun personaEditor() = shoot("persona-editor") { PersonaEditorListPreview(it) }

    @Test fun personaEditorCustomForm() = shoot("persona-editor-custom-form") { IdentityFormPreview(it) }

    @Test fun siteInfo() = shoot("site-info") { t ->
        Box(Modifier.fillMaxSize()) {
            BrowserFrame(t, desktop = true, chrome = { colors -> Display(colors, "secure.chase.com", desktop = true) }) {
                FakePage("Accounts", listOf("Checking ··· 4410", "Savings ··· 0921", "Sapphire ··· 7713"))
            }
            // The card's own preview frame draws the scrim from `top` (status strip + toolbar) down.
            SiteInfoOverlay(t)
        }
    }

    @Test fun settings() = shoot("settings", device = TALL) { SettingsRootPreview(it) }

    @Test fun settingsDeveloper() = shoot("settings-developer", device = TALL) { DeveloperPreview(it) }

    /** The frame the headless-Firefox render of hatch_error.html is composited into (content area left blank). */
    @Test fun browserPageErrorFrame() = shoot("browser-page-error-frame") { t ->
        BrowserFrame(t, desktop = true, chrome = { colors -> Display(colors, "maps.google.com", desktop = true) }) {
            Box(Modifier.fillMaxSize().background(HatchTheme.colors.bg))
        }
    }

    // ---- A11Y-14: 200 % font scale, RTL and landscape ------------------------------------------------------------------

    @Test fun a11yFontScale() {
        val big = phone(fontScale = 2f)
        shootA11y("new-tab", "font200", big) { t ->
            BrowserFrame(t, desktop = true, chrome = { colors ->
                EditToolbar(
                    query = "", hint = "Search or enter address", suggestion = null, isQueryPrefilled = false,
                    endActions = emptyList(), tabCount = 5, colors = colors, onUrlEdit = {}, onUrlCommitted = {}, onEvent = {},
                )
            }) { HomePreview(t) }
        }
        shootA11y("main-menu", "font200", big) { t -> SheetOver(t, url = "console.aws.amazon.com") { MainMenuPreview(t) } }
        shootA11y("tab-tray", "font200", big) { TabTrayPreview(it) }
        shootA11y("settings", "font200", phone(heightDp = 1320, fontScale = 2f)) { SettingsRootPreview(it) }
        shootA11y("persona-sheet", "font200", big) { t -> SheetOver(t, url = "console.aws.amazon.com") { PersonaSheetPreview(t) } }
        shootA11y("site-info", "font200", big) { t ->
            Box(Modifier.fillMaxSize()) {
                BrowserFrame(t, desktop = true, chrome = { colors -> Display(colors, "secure.chase.com", desktop = true) }) {
                    FakePage("Accounts", listOf("Checking ··· 4410"))
                }
                SiteInfoOverlay(t)
            }
        }
        shootA11y("persona-editor", "font200", big) { PersonaEditorListPreview(it) }
        shootA11y("site-rule-editor", "font200", big) { RuleEditorPreview(it) }
    }

    @Test fun a11yRtl() {
        shootA11y("settings", "rtl", phone(heightDp = 1320, rtl = true), rtl = true) { SettingsRootPreview(it) }
        shootA11y("saved-login-detail", "rtl", phone(rtl = true), rtl = true) { SavedLoginDetailPreview(it) }
    }

    @Test fun a11yLandscape() {
        val wide = phone(widthDp = 760, heightDp = 360, landscape = true)
        shootA11y("settings", "landscape", wide) { SettingsRootPreview(it) }
        shootA11y("tab-tray", "landscape", wide) { TabTrayPreview(it) }
    }

    @Test fun siteRules() = shoot("site-rules") { SiteRulesPreview(it) }

    @Test fun siteRuleEditor() = shoot("site-rule-editor") { RuleEditorPreview(it) }

    @Test fun sitePermissions() = shoot("site-permissions") { SitePermissionsPreview(it) }

    @Test fun savedLogins() = shoot("saved-logins") { SavedLoginsPreview(it) }

    @Test fun savedLoginDetail() = shoot("saved-login-detail") { SavedLoginDetailPreview(it) }

    /** Static Graphite by design (the recovery screen reads no settings), so light and dark only. */
    @Test fun crashRecovery() {
        paparazzi.snapshot(name = "crash-recovery__graphite-light") { RecoveryScreenLightPreview() }
        paparazzi.snapshot(name = "crash-recovery__graphite-dark") { RecoveryScreenDarkPreview() }
    }

    /** The "Spec" board: every palette's roles, the type scale and stock M3 controls (ThemePreview). */
    @Test fun spec() {
        paparazzi.snapshot(name = "spec__graphite-light") { ThemePreview(dark = false) }
        paparazzi.snapshot(name = "spec__graphite-dark") { ThemePreview(dark = true) }
    }

    companion object {
        /** [widthDp] × [heightDp] at 420 dpi (density 2.625). */
        fun phone(widthDp: Int = 360, heightDp: Int = 760, fontScale: Float = 1f, rtl: Boolean = false, landscape: Boolean = false) = DeviceConfig(
            screenHeight = (heightDp * 2.625).toInt(),
            screenWidth = (widthDp * 2.625).toInt(),
            xdpi = 420,
            ydpi = 420,
            orientation = if (landscape) ScreenOrientation.LANDSCAPE else ScreenOrientation.PORTRAIT,
            density = Density.create(420),
            fontScale = fontScale,
            layoutDirection = if (rtl) com.android.resources.LayoutDirection.RTL else com.android.resources.LayoutDirection.LTR,
            ratio = ScreenRatio.LONG,
            released = "October 15, 2020",
        )

        /** 360 × 760 dp at 420 dpi (density 2.625): 945 × 1995 px. */
        val PHONE = phone()

        /** The tall mockups (Settings root, Developer): 360 × 1320 dp. */
        val TALL = phone(heightDp = 1320)

        val THEMES = listOf(
            PreviewTheme(HatchPalette.Graphite, dark = false),
            PreviewTheme(HatchPalette.Graphite, dark = true),
            PreviewTheme(HatchPalette.Phosphor, dark = false),
        )

        fun PreviewTheme.fileSuffix(): String = "${palette.key}-${if (dark) "dark" else "light"}"
    }
}

/** The mockups' 32 dp status strip. */
private val StatusStrip = 32.dp

/** The toolbar row height + strip, where the site-info card anchors. */
private val ChromeHeight = StatusStrip + 56.dp

/** Browser frame: status strip + chrome row in the persona tint (as BrowserScreen draws them), then the content. */
@Composable
private fun BrowserFrame(
    t: PreviewTheme,
    desktop: Boolean,
    chrome: @Composable (ToolbarColors) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    HatchTheme(palette = t.palette, mode = if (t.dark) ThemeMode.Dark else ThemeMode.Light) {
        val colors = rememberToolbarColors(desktop)
        Column(Modifier.fillMaxSize().background(HatchTheme.colors.bg)) {
            Column(Modifier.fillMaxWidth().background(colors.background)) {
                Spacer(Modifier.height(StatusStrip))
                chrome(colors)
            }
            content()
        }
    }
}

@Composable
private fun Display(colors: ToolbarColors, host: String, desktop: Boolean) {
    DisplayToolbar(
        model = DisplayToolbarModel(host, ToolbarState.ChipSecurity.Lock, desktop = desktop, progress = null, tabCount = 4),
        colors = colors,
        onEvent = {},
    )
}

/** A plain stand-in for web content (not Hatch UI): white page, dark text, as most sites render. */
@Composable
private fun FakePage(title: String, lines: List<String>) {
    Column(Modifier.fillMaxSize().background(Color.White).padding(20.dp)) {
        Text(title, color = Color(0xFF202124), fontSize = 22.sp)
        Spacer(Modifier.height(16.dp))
        lines.forEach {
            Text(it, color = Color(0xFF3C4043), fontSize = 15.sp, modifier = Modifier.padding(vertical = 10.dp))
        }
    }
}

/** A bottom sheet's content over the browser and the sheets' 50 % scrim (ModalBottomSheet draws in its own window). */
@Composable
private fun SheetOver(t: PreviewTheme, url: String, sheet: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        BrowserFrame(t, desktop = true, chrome = { colors -> Display(colors, url, desktop = true) }) {
            FakePage("Console Home", listOf("Recently visited", "EC2", "S3", "CloudWatch"))
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = HatchDimens.SHEET_SCRIM_ALPHA)))
        HatchTheme(palette = t.palette, mode = if (t.dark) ThemeMode.Dark else ThemeMode.Light) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                shape = HatchShapes.Sheet,
                color = HatchTheme.colors.sh,
            ) { sheet() }
        }
    }
}

/** The site-info card preview, whose frame paints the surface; drawn here without that background. */
@Composable
private fun SiteInfoOverlay(t: PreviewTheme) {
    HatchTheme(palette = t.palette, mode = if (t.dark) ThemeMode.Dark else ThemeMode.Light) {
        app.hatch.ui.SiteInfoLayout(
            summary = app.hatch.ui.SiteInfoSummary("secure.chase.com", ToolbarState.SecurityBadge.Secure, "Connection is secure"),
            rows = listOf("Issued by" to "DigiCert EV RSA CA G2", "Issued to" to "JPMorgan Chase and Co.", "Expires" to "2027-03-14"),
            personaLabel = app.hatch.ui.surfaces.PersonaLabels.full(app.hatch.persona.Presets.default()),
            desktop = true,
            top = ChromeHeight,
            onChangePersona = {},
            onSitePermissions = {},
            onDismiss = {},
        )
    }
}

private class FakeSnackbar(message: String, action: String) : SnackbarData {
    override val visuals: SnackbarVisuals = object : SnackbarVisuals {
        override val message = message
        override val actionLabel: String = action
        override val withDismissAction = false
        override val duration = SnackbarDuration.Short
    }
    override fun performAction() = Unit
    override fun dismiss() = Unit
}
