package app.hatch.security

import app.hatch.applinks.AppLinksMode
import app.hatch.applinks.AppLinksPolicy
import app.hatch.hostrewrites.HostRewriteFilter
import app.hatch.logins.LoginExceptionCodec
import app.hatch.persona.HostRewrite
import app.hatch.rules.UserscriptImport
import app.hatch.ui.ExternalBrowser
import app.hatch.ui.rules.readCapped
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M1 security review (reviewer: security): guards over the pure security decisions of the M1 modules, written
 * adversarially (inputs chosen to slip past the checks). Each test names the invariant it locks; a failure means a
 * security property of the review regressed, not a cosmetic change.
 */
class SecurityReviewGuardsTest {
    // ---- rules import: TLS-only source, size cap, refusals, safe defaults ----------------------------------------

    @Test
    fun `userscript import only accepts https sources, whatever the spelling`() {
        assertTrue(UserscriptImport.isAllowedUrl("https://example.org/x.user.js"))
        assertTrue(UserscriptImport.isAllowedUrl("  HTTPS://example.org/x.user.js  "))
        for (bad in listOf(
            "http://example.org/x.user.js",
            "https://", "https:///x.user.js", "https://?q", "https://#f",
            "javascript:alert(1)", "file:///sdcard/x.user.js", "content://evil/x.user.js",
            "data:text/javascript,alert(1)", "moz-extension://abc/x.js", "ftp://example.org/x.user.js",
            "//example.org/x.user.js", "example.org/x.user.js", "",
        )) assertFalse(UserscriptImport.isAllowedUrl(bad), bad)
    }

    @Test
    fun `the download body cap is 1 MiB and is enforced on the stream, not only on Content-Length`() {
        assertEquals(1L shl 20, UserscriptImport.MAX_BYTES)
        val exact = ByteArray(UserscriptImport.MAX_BYTES.toInt()) { 'a'.code.toByte() }
        assertEquals(UserscriptImport.MAX_BYTES.toInt(), readCapped(ByteArrayInputStream(exact), UserscriptImport.MAX_BYTES).length)
        val over = ByteArray(UserscriptImport.MAX_BYTES.toInt() + 1) { 'a'.code.toByte() }
        assertThrows<IllegalStateException> { readCapped(ByteArrayInputStream(over), UserscriptImport.MAX_BYTES) }
    }

    @Test
    fun `an imported script never comes back enabled or in the MAIN world, whatever its header asks for`() {
        val text = """
            // ==UserScript==
            // @name       Sneaky
            // @match      *://*.example.com/*
            // @grant      unsafeWindow
            // @run-at     document-start
            // @world      MAIN
            // @enabled    true
            // ==/UserScript==
            unsafeWindow.pwned = 1;
        """.trimIndent()
        val r = UserscriptImport.fromText(text, "https://example.org/sneaky.user.js")
        assertTrue(r.ok, r.problems.toString())
        val rule = r.rule!!
        assertFalse(rule.enabled)
        assertEquals("ISOLATED", rule.world)
        assertEquals("document_start", rule.runAt)
        assertNull(rule.css)
        assertEquals("https://example.org/sneaky.user.js", rule.source)
    }

    @Test
    fun `remote code and cross-origin network grants are refused in every spelling the header or body can use`() {
        fun script(vararg headerLines: String, body: String = "console.log(1);") =
            "// ==UserScript==\n// @name t\n// @match *://*.example.com/*\n" + headerLines.joinToString("") { "// $it\n" } + "// ==/UserScript==\n$body"

        assertFalse(UserscriptImport.fromText(script("@require https://cdn.example/lib.js"), null).ok)
        assertFalse(UserscriptImport.fromText(script("@require   https://cdn.example/lib.js   "), null).ok)
        assertFalse(UserscriptImport.fromText(script("@grant GM_xmlhttpRequest"), null).ok)
        assertFalse(UserscriptImport.fromText(script("@grant GM.xmlHttpRequest"), null).ok)
        assertFalse(UserscriptImport.fromText(script("@grant none", body = "GM_xmlhttpRequest({url:'https://x'})"), null).ok)
        assertFalse(UserscriptImport.fromText(script("@grant none", body = "GM.xmlHttpRequest({url:'https://x'})"), null).ok)
        assertFalse(UserscriptImport.fromText(script("@grant GM_openInTab"), null).ok)
        assertFalse(UserscriptImport.fromText(script("@grant GM_setClipboard"), null).ok)
        assertFalse(UserscriptImport.fromText(script("@grant GM_download"), null).ok)
        assertFalse(UserscriptImport.fromText(script("@grant GM_cookie"), null).ok)
        // The allowed subset still passes.
        assertTrue(UserscriptImport.fromText(script("@grant GM_getValue", "@grant GM_setValue", "@grant GM_addStyle"), null).ok)
    }

    @Test
    fun `a userstyle import is CSS only and runs at document_start in the ISOLATED world`() {
        val css = "/* ==UserStyle==\n@name Dark\n==/UserStyle== */\n@-moz-document domain(example.com) { body { color: red } }"
        val r = UserscriptImport.fromText(css, "https://example.org/dark.user.css")
        assertTrue(r.ok, r.problems.toString())
        assertEquals(UserscriptImport.Kind.USER_CSS, r.kind)
        assertNull(r.rule!!.js)
        assertEquals(css, r.rule.css)
        assertEquals("ISOLATED", r.rule.world)
        assertFalse(r.rule.enabled)
        assertEquals(listOf("*://example.com/*", "*://*.example.com/*"), r.rule.matches)
    }

    @Test
    fun `a script body posing as a userstyle still goes through the userscript path when the URL says user_js`() {
        val text = "/* ==UserStyle== */\n// ==UserScript==\n// @name x\n// @match *://a.com/*\n// @require https://evil/x.js\n// ==/UserScript==\nalert(1)"
        val r = UserscriptImport.fromText(text, "https://example.org/x.user.js")
        assertEquals(UserscriptImport.Kind.USER_JS, r.kind)
        assertFalse(r.ok)
    }

    // ---- "Open in another browser": only web URLs leave the app ------------------------------------------------

    @Test
    fun `no local, extension or intent URL is ever handed to another app`() {
        for (bad in listOf(
            "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end",
            "content://com.android.providers.downloads.documents/document/1",
            "file:///data/data/app.hatch.browser/databases/logins.sqlite",
            "javascript:alert(document.cookie)",
            "about:config", "about:blank",
            "moz-extension://1234/popup.html",
            "data:text/html,<script>alert(1)</script>",
            "blob:https://example.org/uuid",
            "resource://gre/x", "chrome://browser/content/x",
            "HTTP://EXAMPLE.ORG", "https://", "http://a", "",
        )) assertFalse(ExternalBrowser.isShareableUrl(bad), bad)
        assertTrue(ExternalBrowser.isShareableUrl("https://a"))
    }

    // ---- app links: the default never leaves Hatch for a web link, and the stored key cannot widen it ----------

    @Test
    fun `app links default to staying in Hatch and an unknown stored key cannot escalate`() {
        assertEquals(AppLinksMode.STAY_IN_HATCH, AppLinksMode.DEFAULT)
        assertEquals(AppLinksPolicy(launchInApp = false, shouldPrompt = true), AppLinksPolicy.of(AppLinksMode.DEFAULT))
        for (key in listOf(null, "", "ALWAYS", "always ", "true", "1", "yes", "\u0000")) {
            assertEquals(AppLinksMode.STAY_IN_HATCH, AppLinksMode.fromKey(key), "key=$key")
        }
        assertEquals(AppLinksMode.ALWAYS, AppLinksMode.fromKey("always"))
        // Only the explicit "always" mode bypasses the prompt.
        assertTrue(AppLinksPolicy.of(AppLinksMode.ASK).shouldPrompt)
        assertFalse(AppLinksPolicy.of(AppLinksMode.ALWAYS).shouldPrompt)
    }

    // ---- host rewrites: the user can only switch table rows off; no target is user-editable ---------------------

    @Test
    fun `host rewrite settings can disable table rows but never add or retarget one`() {
        val table = listOf(HostRewrite("m.example.com", "www.example.com"), HostRewrite("/^m\\.wiki$/", "wiki"))
        // Toggling an unknown key is a no-op: a planted preference cannot introduce a redirect.
        assertEquals(emptySet(), HostRewriteFilter.toggle(table, emptySet(), "evil.example", enabled = false))
        assertEquals(emptySet(), HostRewriteFilter.toggle(table, setOf("stale.example"), "m.example.com", enabled = true))
        // `effective` is a pure subset of the table with the same targets, in table order.
        val disabled = setOf("m.example.com", "attacker.example")
        val effective = HostRewriteFilter.effective(table, disabled)
        assertEquals(listOf(table[1]), effective)
        assertTrue(effective.all { it in table })
        assertEquals(table, HostRewriteFilter.effective(table, setOf("attacker.example")))
    }

    // ---- login exceptions: hostile stored blobs never crash the save prompt or widen a decision -----------------

    @Test
    fun `hostile never-save blobs decode to an empty set and schemes stay distinct`() {
        for (blob in listOf("{}", "[1,2]", "[[\"https://a\"]]", "\"https://a\"", "[null]", "{\"origins\":[\"https://a\"]}", "\u0000", "[" + "[".repeat(200))) {
            assertEquals(emptySet(), LoginExceptionCodec.decode(blob), blob)
        }
        assertEquals(setOf("https://a.example"), LoginExceptionCodec.decode("[\"https://a.example/\", \"\", \"   \"]"))
        // "Never save" for the https site does not silence the http one (and vice versa).
        val https = LoginExceptionCodec.normalizeOrigin("https://bank.example/login")
        val http = LoginExceptionCodec.normalizeOrigin("http://bank.example/login")
        assertEquals("https://bank.example", https)
        assertEquals("http://bank.example", http)
        assertFalse(https == http)
    }

    // ---- manifest: exported surface and backup posture ---------------------------------------------------------

    private val android = "http://schemas.android.com/apk/res/android"

    private val manifest by lazy {
        val file = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).firstOrNull { it.isFile }
            ?: error("manifest not found from ${File(".").absolutePath}")
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
    }

    private fun elements(tag: String): List<Element> {
        val nodes = manifest.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.attr(name: String): String = getAttributeNS(android, name)

    @Test
    fun `MainActivity is the only exported component and nothing is debuggable`() {
        val exported = (elements("activity") + elements("service") + elements("provider") + elements("receiver"))
            .filter { it.attr("exported") == "true" }
            .map { it.attr("name") }
        assertEquals(listOf(".MainActivity"), exported)
        val application = elements("application").single()
        assertEquals("", application.attr("debuggable"))
        assertEquals("false", application.attr("allowBackup"))
    }

    // SEC-2: Android 12+ reads dataExtractionRules for cloud backup and device-to-device transfer; every domain is
    // excluded under both, exactly Fenix's file.
    @Test
    fun `nothing under the data directory is backed up or transferred to another device`() {
        val application = elements("application").single()
        assertEquals("@xml/data_extraction_rules", application.attr("dataExtractionRules"))
        assertEquals("false", application.attr("fullBackupContent"))
        val rulesFile = listOf(File("src/main/res/xml/data_extraction_rules.xml"), File("app/src/main/res/xml/data_extraction_rules.xml")).first { it.isFile }
        val rules = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(rulesFile)
        for (section in listOf("cloud-backup", "device-transfer")) {
            val node = rules.getElementsByTagName(section).item(0) as Element
            val excludes = node.getElementsByTagName("exclude")
            val domains = (0 until excludes.length).map { (excludes.item(it) as Element).getAttribute("domain") }.toSet()
            assertEquals(setOf("root", "file", "database", "sharedpref", "external"), domains, section)
            assertEquals(0, node.getElementsByTagName("include").length, "$section must not include anything")
        }
    }

    // PERF-06: androidx.work's automatic initializer is removed (nothing enqueues work; HatchApplication provides the
    // configuration on demand).
    @Test
    fun `the WorkManager auto-initializer is removed from the startup provider`() {
        val provider = elements("provider").first { it.attr("name") == "androidx.startup.InitializationProvider" }
        val meta = (0 until provider.getElementsByTagName("meta-data").length)
            .map { provider.getElementsByTagName("meta-data").item(it) as Element }
            .first { it.attr("name") == "androidx.work.WorkManagerInitializer" }
        assertEquals("remove", meta.getAttributeNS("http://schemas.android.com/tools", "node"))
        assertEquals("merge", provider.getAttributeNS("http://schemas.android.com/tools", "node"))
    }

    // UXP-09: a theme / locale / density change recreates the Activity (Fenix's configChanges list), so the system-bar
    // contrast, the engine's first-paint cover and AC's DialogFragments follow the new theme.
    @Test
    fun `MainActivity recreates on uiMode, locale and density changes`() {
        val main = elements("activity").first { it.attr("name") == ".MainActivity" }
        val changes = main.attr("configChanges").split('|').toSet()
        for (handledInPlace in listOf("uiMode", "locale", "density")) assertFalse(handledInPlace in changes, handledInPlace)
        assertEquals(setOf("keyboard", "keyboardHidden", "mcc", "mnc", "orientation", "screenSize", "layoutDirection", "smallestScreenSize", "screenLayout"), changes)
    }

    @Test
    fun `no component declares an intent filter for a scheme the engine must never receive from outside`() {
        // The VIEW filters may list http/https/content/file; nothing may register javascript:, intent:, data:, blob:
        // or moz-extension: (an outside app could otherwise inject a script URL straight into a tab).
        val schemes = elements("data").map { it.attr("scheme") }.filter { it.isNotEmpty() }.toSet()
        for (bad in listOf("javascript", "intent", "data", "blob", "moz-extension", "about", "resource", "chrome")) {
            assertFalse(bad in schemes, bad)
        }
    }
}
