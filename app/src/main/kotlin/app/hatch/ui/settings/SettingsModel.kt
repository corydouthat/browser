package app.hatch.ui.settings

import app.hatch.ui.SettingsPage
import app.hatch.ui.theme.HatchPalette
import app.hatch.ui.theme.ThemeMode

/** The four groups of the Settings root page (mockup "Settings · root"), in order. */
enum class SettingsGroup(val title: String) {
    General("General"),
    DesktopPersona("Desktop persona"),
    PrivacySites("Privacy & sites"),
    More("More"),
}

/**
 * The rows of the Settings root page, in display order. [page] is the sub-page a row pushes on the Settings stack;
 * null means the row opens another module's screen instead (persona editor, site permissions, site rules: the
 * rules overlays draw above Settings and back returns here). [keywords] widen the Settings search beyond the
 * title and the live summary.
 */
enum class SettingsEntry(val group: SettingsGroup, val title: String, val page: SettingsPage?, val keywords: String) {
    HomePage(SettingsGroup.General, "Home page", SettingsPage.HomePage, "homepage start url new tab"),
    Appearance(SettingsGroup.General, "Appearance", SettingsPage.Appearance, "theme palette colour color dark light"),
    Toolbar(SettingsGroup.General, "Toolbar", SettingsPage.Toolbar, "address bar scroll hide"),
    DefaultPersona(SettingsGroup.DesktopPersona, "Default persona", SettingsPage.DefaultPersona, "identity region first start"),
    IdentitiesRegions(SettingsGroup.DesktopPersona, "Identities & regions", null, "persona editor custom preset user agent time zone"),
    HostRewrites(SettingsGroup.DesktopPersona, "Host rewrites", SettingsPage.HostRewrites, "mobile desktop redirect m."),
    Compatibility(SettingsGroup.DesktopPersona, "Compatibility", SettingsPage.Compatibility, "touch gestures time zone shim"),
    LoginsPasskeys(SettingsGroup.PrivacySites, "Logins & passkeys", SettingsPage.LoginsPasskeys, "passwords autofill saved logins credentials"),
    SitePermissions(SettingsGroup.PrivacySites, "Site permissions", null, "camera microphone location notifications"),
    SiteRules(SettingsGroup.PrivacySites, "Site rules", null, "userscripts userstyles javascript css"),
    AppLinks(SettingsGroup.PrivacySites, "App links", SettingsPage.AppLinks, "open in app youtube reddit"),
    Addons(SettingsGroup.More, "Add-ons", SettingsPage.Addons, "extensions ublock xpi"),
    Developer(SettingsGroup.More, "Developer", SettingsPage.Developer, "remote debugging eruda self-check bridge engine prefs"),
}

/** The live one-line summaries under each root row, and the root search (pure, JVM-tested). */
object SettingsModel {
    /** Entries in [SettingsEntry] order whose title, summary or keywords contain every word of [query]. */
    fun visible(summaries: Map<SettingsEntry, String>, query: String): List<SettingsEntry> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return SettingsEntry.entries
        return SettingsEntry.entries.filter { e ->
            val hay = "${e.title} ${summaries[e].orEmpty()} ${e.keywords} ${e.group.title}".lowercase()
            words.all { it in hay }
        }
    }

    /** "Graphite · System theme". */
    fun appearance(palette: HatchPalette, mode: ThemeMode): String = "${palette.label} · ${mode.label} theme"

    fun toolbar(hideOnScroll: Boolean): String = "Hide when scrolling: ${if (hideOnScroll) "on" else "off"}"

    /** "Windows · Firefox 158 · United States" from the short identity / region names. */
    fun defaultPersona(identityName: String?, regionName: String?): String =
        listOfNotNull(identityName, regionName).joinToString(" · ").ifEmpty { "Not set" }

    fun identitiesAndRegions(presets: Int, custom: Int): String = "$presets presets · $custom custom"

    fun hostRewrites(enabled: Int): String = "Mobile → desktop for $enabled host${if (enabled == 1) "" else "s"}"

    const val COMPATIBILITY = "Touch events, time-zone shim"
    const val DEVELOPER = "Debugging, engine, diagnostics"

    /** "Autofill on · 23 saved"; the count is left out until the store answered (or when it failed). */
    fun logins(autofill: Boolean, saved: Long?): String =
        "Autofill ${if (autofill) "on" else "off"}" + (saved?.let { " · $it saved" } ?: "")

    fun sitePermissions(sites: Int?): String = when (sites) {
        null -> "Per-site decisions"
        0 -> "No sites"
        1 -> "1 site"
        else -> "$sites sites"
    }

    fun addons(installed: Int): String = if (installed == 0) "None installed" else "$installed installed"

    /** The build footer: "Hatch · v0.1.0 (build 22)", plus sha @ branch · time on debug builds. */
    fun footer(versionName: String, versionCode: Int, debug: Boolean, sha: String, branch: String, buildTime: String): List<String> {
        val first = "Hatch · v$versionName (build $versionCode)"
        return if (debug) listOf(first, "$sha @ $branch · $buildTime") else listOf(first)
    }
}

/**
 * The Developer page's bridge card: [ok] picks the status dot (status green vs error), [line] is the mono status line
 * ("Connected · v0.3 · 0 errors"). [status] is `BridgeExtension.status` ("connected v0.3", "installing", "install
 * failed: …", "background port disconnected"); [errors] counts log lines at level "error".
 */
data class BridgeStatus(val ok: Boolean, val line: String) {
    companion object {
        fun of(status: String, errors: Int): BridgeStatus {
            val s = status.trim()
            val connected = s.startsWith("connected")
            val errorText = "$errors error${if (errors == 1) "" else "s"}"
            val head = if (connected) {
                val version = s.removePrefix("connected").trim().takeIf { it.isNotEmpty() }
                listOfNotNull("Connected", version).joinToString(" · ")
            } else {
                s.replaceFirstChar { it.uppercaseChar() }.ifEmpty { "Unknown" }
            }
            return BridgeStatus(ok = connected, line = "$head · $errorText")
        }
    }
}
