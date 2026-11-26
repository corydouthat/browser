package app.hatch.ui

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import app.hatch.engine.GeckoPrefs
import app.hatch.persona.Presets
import app.hatch.ui.settings.DeveloperRows
import app.hatch.ui.settings.ScreenIcons
import app.hatch.ui.surfaces.PersonaLabels
import app.hatch.ui.surfaces.SurfaceIcons
import app.hatch.ui.tabs.TabCardDimens
import app.hatch.ui.tabs.TabCardText
import app.hatch.ui.theme.HatchDimens
import app.hatch.ui.toolbar.EditActionTouch
import app.hatch.ui.toolbar.ToolbarTouchTargets
import app.hatch.ui.toolbar.progressSemantics
import org.junit.jupiter.api.Test
import java.awt.Font
import java.awt.font.FontRenderContext
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression guards for the design-pass review findings (DF-*, A11Y-*, RS-*). Compose screens cannot be rendered on
 * this JVM, so layout and wiring rules are checked on the sources, and pure helpers directly.
 */
class DesignReviewGuardsTest {
    private fun file(relative: String): File =
        listOf(File(relative), File("app/$relative")).firstOrNull { it.exists() } ?: error("missing $relative from ${File(".").absolutePath}")

    private fun source(relative: String): String = file("src/main/kotlin/app/hatch/$relative").readText()

    private fun uiSources(): List<Pair<String, String>> =
        file("src/main/kotlin/app/hatch/ui").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.path.substringAfter("app/hatch/") to it.readText() }.toList()

    // ---- A11Y-02: every Hatch-drawn focus target shows the 2 dp ring -------------------------------------------------

    @Test
    fun `every clickable, selectable or toggleable in ui has a hatchFocusRing in its modifier chain`() {
        val target = Regex("""\.(clickable|selectable|toggleable|combinedClickable)\s*[({]""")
        // The site-info scrim is a click-to-dismiss area without indication, not a control.
        val allowed = listOf("onClickLabel = \"Close site info\"")
        val misses = uiSources().filterNot { it.first.endsWith("theme/FocusRing.kt") }.flatMap { (path, text) ->
            val lines = text.lines()
            lines.indices.filter { i ->
                val l = lines[i].trim()
                !l.startsWith("*") && !l.startsWith("//") && !l.startsWith("import") && target.containsMatchIn(l) &&
                    allowed.none { it in l } && lines.subList(maxOf(0, i - 6), i + 1).none { "hatchFocusRing" in it }
            }.map { "$path:${it + 1}: ${lines[it].trim()}" }
        }
        assertTrue(misses.isEmpty(), "focus targets without a ring:\n" + misses.joinToString("\n"))
    }

    @Test
    fun `M3 icon buttons in the redesigned screens carry the ring too`() {
        val misses = uiSources().flatMap { (path, text) ->
            text.lines().withIndex().filter { (_, l) -> "IconButton(onClick" in l && "hatchFocusRing" !in l }.map { "$path:${it.index + 1}" }
        }
        assertTrue(misses.isEmpty(), "IconButton without a ring: $misses")
    }

    // ---- A11Y-01: switch rows are toggleable ---------------------------------------------------------------------------

    @Test
    fun `no switch row uses selectable (TalkBack would say Selected instead of On or Off)`() {
        val misses = uiSources().flatMap { (path, text) ->
            Regex("""selectable\([^)]*Role\.Switch""").findAll(text).map { "$path: ${it.value}" }.toList()
        }
        assertTrue(misses.isEmpty(), "selectable + Role.Switch: $misses")
        assertTrue("toggleable(value = checked" in source("ui/settings/ScreenKit.kt"))
    }

    // ---- A11Y-03 / A11Y-04: overlays own focus and the cutout ----------------------------------------------------------

    @Test
    fun `the browser is hidden from TalkBack and focus while an overlay covers it`() {
        assertFalse(OverlayPane.browserCovered(Overlay.None, savedLoginsOpen = false, rulesOpen = false))
        assertTrue(OverlayPane.browserCovered(Overlay.Settings, savedLoginsOpen = false, rulesOpen = false))
        assertTrue(OverlayPane.browserCovered(Overlay.Tabs, savedLoginsOpen = false, rulesOpen = false))
        assertTrue(OverlayPane.browserCovered(Overlay.None, savedLoginsOpen = true, rulesOpen = false))
        assertTrue(OverlayPane.browserCovered(Overlay.None, savedLoginsOpen = false, rulesOpen = true))
        assertEquals(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, OverlayPane.engineAccessibility(true))
        assertEquals(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, OverlayPane.engineAccessibility(false))
        val app = source("ui/HatchApp.kt")
        val wrap = app.indexOf(".coveredByOverlay(covered)")
        assertTrue(wrap in 0 until app.indexOf("BrowserScreen("), "BrowserScreen is composed inside the covered wrapper")
    }

    @Test
    fun `every full-screen overlay root is an overlayPane and the top bars pad the safe-drawing insets`() {
        for (f in listOf("ui/SettingsScreen.kt", "ui/tabs/TabTrayScreen.kt", "ui/logins/SavedLoginsScreen.kt", "ui/rules/RulesScaffold.kt", "ui/SiteInfoCard.kt")) {
            assertTrue(".overlayPane(" in source(f), "$f must use Modifier.overlayPane")
        }
        val kit = source("ui/settings/ScreenKit.kt")
        assertTrue("WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)" in kit)
        assertFalse(".statusBarsPadding()" in kit)
        assertFalse(".statusBarsPadding()" in source("ui/settings/SettingsRoot.kt"))
    }

    // ---- RS-04 / RS-05: wiring ------------------------------------------------------------------------------------------

    @Test
    fun `add-on prompts are observed once, by the host HatchApp always composes`() {
        val observe = "observeAsComposableState { it.webExtensionPromptRequest }"
        val observers = uiSources().filter { observe in it.second }.map { it.first }
        assertEquals(listOf("ui/settings/AddonsPage.kt"), observers)
        val page = source("ui/settings/AddonsPage.kt")
        assertEquals(1, Regex(Regex.escape(observe)).findAll(page).count())
        assertTrue(page.indexOf(observe) > page.indexOf("fun AddonPromptHost("), "only AddonPromptHost observes it")
        val app = source("ui/HatchApp.kt")
        val host = app.indexOf("AddonPromptHost(components, snackbar)")
        assertTrue(host > app.indexOf("when (uiState.overlay)"), "composed outside the overlay switch")
        assertTrue(host < app.indexOf("SnackbarHost(snackbar"), "and under the snackbar host")
    }

    @Test
    fun `the Settings search has no BackHandler of its own`() {
        uiSources().filter { it.first.startsWith("ui/settings/") }.forEach { (path, text) -> assertFalse("BackHandler(" in text, path) }
    }

    // ---- DF-01 / DF-02: the documented pill deltas --------------------------------------------------------------------

    @Test
    fun `toolbar touch-target deltas are 16 dp and 12 dp and docs say so`() {
        assertEquals(16, ToolbarTouchTargets.PILL_NARROWER_DP)
        assertEquals(12, ToolbarTouchTargets.FIND_PILL_NARROWER_DP)
        val limitations = file("../docs/limitations.md").readText()
        val row = limitations.lines().first { it.startsWith("| In-pill touch targets") }
        assertTrue("16 dp" in row && "12 dp" in row, "docs/limitations.md must quote both deltas: $row")
        assertFalse("8 dp wider" in source("ui/toolbar/DisplayToolbar.kt"))
    }

    // ---- DF-03 / DF-04: sheets --------------------------------------------------------------------------------------------

    @Test
    fun `both bottom sheets use the mockups' 50 percent scrim and the persona sheet keeps 18 dp under its handle`() {
        assertEquals(0.5f, HatchDimens.SHEET_SCRIM_ALPHA)
        for (f in listOf("ui/menu/MainMenuSheet.kt", "ui/PersonaSheet.kt")) {
            assertTrue("scrimColor = Color.Black.copy(alpha = HatchDimens.SHEET_SCRIM_ALPHA)" in source(f), f)
        }
        assertEquals(18.dp, PersonaSheetDimens.HandleToTitle)
    }

    // ---- DF-05 / RS-06: Developer prefs ---------------------------------------------------------------------------------

    @Test
    fun `engine pref rows mark user values and keep the mockup's pref names whole at 360 dp`() {
        assertEquals("privacy.resistFingerprinting" to "false", DeveloperRows.of(GeckoPrefs.PrefRow("privacy.resistFingerprinting", "false", userSet = false, type = "bool")))
        assertEquals("dom.w3c_touch_events.enabled" to "0*", DeveloperRows.of(GeckoPrefs.PrefRow("dom.w3c_touch_events.enabled", "0", userSet = true, type = "int")))
        assertEquals("x" to "<unset>", DeveloperRows.of(GeckoPrefs.PrefRow("x", null, userSet = false, type = "string")))
        assertEquals("a, 0, user value", DeveloperRows.spoken("a", "0*"))
        assertEquals("a, false, default", DeveloperRows.spoken("a", "false"))
        // 360 dp screen − 2 × 16 screen padding − 2 × 16 card padding = 296 dp for key + 12 dp gap + value, mono 11.5 sp.
        val mono = Font.createFont(Font.TRUETYPE_FONT, file("src/main/res/font/geist_mono_medium.ttf")).deriveFont(11.5f)
        val frc = FontRenderContext(null, true, true)
        fun width(s: String) = mono.getStringBounds(s, frc).width
        val mockup = listOf(
            "general.useragent.override" to "set*", "privacy.resistFingerprinting" to "false",
            "dom.w3c_touch_events.enabled" to "0*", "intl.accept_languages" to "en-GB, en*", "javascript.options.wasm" to "true",
        )
        mockup.forEach { (k, v) -> assertTrue(width(k) + 12 + width(v) <= 296.0, "$k = $v does not fit: ${width(k) + 12 + width(v)} dp") }
        assertFalse("TopBarTextAction(\"Refresh\"" in source("ui/settings/DeveloperPage.kt"))
    }

    // ---- DF-06: persona editor titles -------------------------------------------------------------------------------------

    @Test
    fun `persona editor rows use the one-line names and keep the parenthetical as a note`() {
        (Presets.identities().map { it.name } + Presets.regions().map { it.name }).forEach { name ->
            val title = PersonaLabels.short(name)
            assertFalse('(' in title, "$name → $title")
            if (title != name.trim()) assertTrue(!PersonaLabels.note(name).isNullOrBlank(), "$name keeps its note")
        }
        assertEquals("no spoofing", PersonaLabels.note("Real device (no spoofing)"))
        assertNull(PersonaLabels.note("Windows · Firefox 158"))
        assertTrue("PersonaLabels.short(it.name)" in source("ui/persona/PersonaEditorScreen.kt"))
    }

    // ---- A11Y-08: touch boxes ---------------------------------------------------------------------------------------------

    @Test
    fun `the tray close and the edit clear button are 48 dp wide clickables`() {
        assertEquals(48.dp, TabCardDimens.CloseTouchWidth)
        assertEquals(TabCardDimens.Header, 40.dp)
        assertEquals(48.dp, EditActionTouch.Width)
        assertEquals(HatchDimens.Pill, EditActionTouch.Height)
        assertEquals("Close tab, GitHub", TabCardText.closeLabel("GitHub", "https://github.com"))
        assertEquals("Close tab, https://a.example", TabCardText.closeLabel(" ", "https://a.example"))
    }

    // ---- A11Y-09: RTL mirroring -------------------------------------------------------------------------------------------

    @Test
    fun `directional glyphs mirror in RTL and symbols do not`() {
        assertTrue(ScreenIcons.Back.autoMirror && ScreenIcons.ChevronRight.autoMirror)
        assertTrue(SurfaceIcons.Back.autoMirror && SurfaceIcons.Forward.autoMirror && SurfaceIcons.ChevronRight.autoMirror)
        assertFalse(ScreenIcons.Monitor.autoMirror || ScreenIcons.ChevronDown.autoMirror || SurfaceIcons.Lock.autoMirror || ScreenIcons.Search.autoMirror)
    }

    // ---- A11Y-10 / A11Y-12: toolbar strings and semantics -------------------------------------------------------------------

    @Test
    fun `the tab counter label is a plural`() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file("src/main/res/values/strings_chrome.xml"))
        val plurals = doc.getElementsByTagName("plurals")
        val counter = (0 until plurals.length).map { plurals.item(it) }.single { it.attributes.getNamedItem("name").nodeValue == "toolbar_tabs_count" }
        val items = (0 until counter.childNodes.length).map { counter.childNodes.item(it) }.filter { it.nodeName == "item" }
            .associate { it.attributes.getNamedItem("quantity").nodeValue to it.textContent }
        assertEquals("%1\$d open tab", items["one"])
        assertEquals("%1\$d open tabs", items["other"])
        assertTrue("pluralStringResource(R.plurals.toolbar_tabs_count, tabCount, tabCount)" in source("ui/toolbar/DisplayToolbar.kt"))
    }

    @Test
    fun `the progress line is a progress bar, not a bare percentage`() {
        val cfg = SemanticsConfiguration()
        progressSemantics(cfg, 0.45f, "Loading page")
        assertEquals(ProgressBarRangeInfo(0.45f, 0f..1f), cfg[SemanticsProperties.ProgressBarRangeInfo])
        assertEquals(listOf("Loading page"), cfg[SemanticsProperties.ContentDescription])
        assertFalse("\"\$progress%\"" in source("ui/toolbar/DisplayToolbar.kt"))
    }

    // ---- WC-07: the keyboard resizes the web content once ---------------------------------------------------------------

    @Test
    fun `the browser root pads for the keyboard's target inset, never the per-frame animated one`() {
        val code = source("ui/BrowserScreen.kt").lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        assertFalse(Regex("""WindowInsets\.ime\b(?!AnimationTarget)""").containsMatchIn(code), "BrowserScreen uses the animated WindowInsets.ime")
        assertTrue("navigationBars.union(imeTarget)" in code && "WindowInsets.imeAnimationTarget" in code)
    }
}
