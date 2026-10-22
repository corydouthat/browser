package app.hatch.ui.settings

import app.hatch.ui.SettingsPage
import app.hatch.ui.theme.HatchPalette
import app.hatch.ui.theme.ThemeMode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsModelTest {
    @Test
    fun `root rows follow the mockup's groups and order`() {
        val byGroup = SettingsEntry.entries.groupBy { it.group }.mapValues { (_, v) -> v.map { it.title } }
        assertEquals(listOf("Home page", "Appearance", "Toolbar"), byGroup[SettingsGroup.General])
        assertEquals(listOf("Default persona", "Identities & regions", "Host rewrites", "Compatibility"), byGroup[SettingsGroup.DesktopPersona])
        assertEquals(listOf("Logins & passkeys", "Site permissions", "Site rules", "App links"), byGroup[SettingsGroup.PrivacySites])
        assertEquals(listOf("Add-ons", "Developer"), byGroup[SettingsGroup.More])
        assertEquals(listOf("General", "Desktop persona", "Privacy & sites", "More"), SettingsGroup.entries.map { it.title })
    }

    @Test
    fun `every settings page is reachable from exactly one row, module rows open overlays`() {
        val pages = SettingsEntry.entries.mapNotNull { it.page }
        assertEquals(SettingsPage.entries.toSet(), pages.toSet())
        assertEquals(pages.size, pages.toSet().size)
        assertEquals(
            setOf(SettingsEntry.IdentitiesRegions, SettingsEntry.SitePermissions, SettingsEntry.SiteRules),
            SettingsEntry.entries.filter { it.page == null }.toSet(),
        )
    }

    @Test
    fun `search matches titles, summaries and keywords, every word`() {
        val summaries = mapOf(SettingsEntry.AppLinks to "Stay in Hatch", SettingsEntry.SiteRules to "2 of 4 on")
        assertEquals(SettingsEntry.entries, SettingsModel.visible(summaries, "  "))
        assertEquals(listOf(SettingsEntry.Appearance), SettingsModel.visible(summaries, "dark"))
        assertEquals(listOf(SettingsEntry.AppLinks), SettingsModel.visible(summaries, "stay hatch"))
        assertTrue(SettingsEntry.Developer in SettingsModel.visible(summaries, "ERUDA"))
        assertEquals(emptyList(), SettingsModel.visible(summaries, "nothing-matches-this"))
    }

    @Test
    fun `summaries read like the mockup`() {
        assertEquals("Graphite · System theme", SettingsModel.appearance(HatchPalette.Graphite, ThemeMode.System))
        assertEquals("Phosphor · Dark theme", SettingsModel.appearance(HatchPalette.Phosphor, ThemeMode.Dark))
        assertEquals("Hide when scrolling: off", SettingsModel.toolbar(false))
        assertEquals("Windows · Firefox 158 · United States", SettingsModel.defaultPersona("Windows · Firefox 158", "United States"))
        assertEquals("4 presets · 1 custom", SettingsModel.identitiesAndRegions(4, 1))
        assertEquals("Mobile → desktop for 6 hosts", SettingsModel.hostRewrites(6))
        assertEquals("Mobile → desktop for 1 host", SettingsModel.hostRewrites(1))
        assertEquals("Autofill on · 23 saved", SettingsModel.logins(true, 23))
        assertEquals("Autofill off", SettingsModel.logins(false, null))
        assertEquals("8 sites", SettingsModel.sitePermissions(8))
        assertEquals("1 site", SettingsModel.sitePermissions(1))
        assertEquals("2 installed", SettingsModel.addons(2))
        assertEquals("None installed", SettingsModel.addons(0))
        assertEquals(listOf("Hatch · v0.1.0 (build 22)"), SettingsModel.footer("0.1.0", 22, false, "abc", "main", "t"))
        assertEquals(listOf("Hatch · v0.1.0 (build 22)", "abc-dirty @ main · 2026-10-21"), SettingsModel.footer("0.1.0", 22, true, "abc-dirty", "main", "2026-10-21"))
    }

    @Test
    fun `bridge status line`() {
        assertEquals(BridgeStatus(true, "Connected · v0.3 · 0 errors"), BridgeStatus.of("connected v0.3", 0))
        assertEquals(BridgeStatus(true, "Connected · 1 error"), BridgeStatus.of("connected", 1))
        assertEquals(BridgeStatus(false, "Background port disconnected · 0 errors"), BridgeStatus.of("background port disconnected", 0))
        assertEquals(BridgeStatus(false, "Install failed: boom · 2 errors"), BridgeStatus.of("install failed: boom", 2))
    }
}
