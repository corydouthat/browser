package app.hatch.logins

import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sanity over the hand-written manifest (m1-inventory risks 4/7/10 and the passkey permissions): the file is
 * parsed as XML, so a typo in a permission or a dropped `<data>` fails here instead of on the phone.
 */
class ManifestIntentFiltersTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private val doc by lazy {
        val candidates = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
        val file = candidates.firstOrNull { it.isFile } ?: error("manifest not found from ${File(".").absolutePath}")
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
    }

    private fun elements(parent: Element, tag: String): List<Element> {
        val nodes = parent.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.attr(name: String): String = getAttributeNS(android, name)

    private val permissions by lazy { elements(doc.documentElement, "uses-permission").map { it.attr("name") }.toSet() }

    private val mainActivity by lazy {
        elements(doc.documentElement, "activity").first { it.attr("name") == ".MainActivity" }
    }

    /** One filter = (actions, categories, schemes, mimeTypes). */
    private data class Filter(val actions: Set<String>, val categories: Set<String>, val schemes: Set<String>, val mimeTypes: Set<String>)

    private val filters by lazy {
        elements(mainActivity, "intent-filter").map { f ->
            Filter(
                actions = elements(f, "action").map { it.attr("name") }.toSet(),
                categories = elements(f, "category").map { it.attr("name") }.toSet(),
                schemes = elements(f, "data").map { it.attr("scheme") }.filter { it.isNotEmpty() }.toSet(),
                mimeTypes = elements(f, "data").map { it.attr("mimeType") }.filter { it.isNotEmpty() }.toSet(),
            )
        }
    }

    @Test
    fun `passkey, notification, foreground-service and biometric permissions are declared`() {
        for (p in listOf(
            "android.permission.CREDENTIAL_MANAGER_SET_ORIGIN",
            "android.permission.CREDENTIAL_MANAGER_QUERY_CANDIDATE_CREDENTIALS",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.USE_BIOMETRIC",
        )) assertTrue(p in permissions, p)
    }

    @Test
    fun `web, typed-web, pdf, share and app-browser filters exist and nothing else reaches the engine`() {
        val view = "android.intent.action.VIEW"
        val browsable = setOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE")
        fun has(pred: (Filter) -> Boolean) = assertTrue(filters.any(pred), "missing filter; have: $filters")
        has { view in it.actions && it.schemes == setOf("http", "https") && it.mimeTypes.isEmpty() && browsable.all { c -> c in it.categories } }
        has { view in it.actions && it.schemes == setOf("http", "https") && it.mimeTypes == setOf("text/html", "text/plain", "application/xhtml+xml") }
        has { view in it.actions && it.schemes == setOf("content") && it.mimeTypes == setOf("application/pdf") }
        // SEC-4: no filter lists file:, and content: is accepted for PDFs only (Fenix 158's external surface).
        assertTrue(filters.none { "file" in it.schemes }, "no file: filter; have: $filters")
        filters.filter { "content" in it.schemes }.forEach { f -> assertEquals(setOf("application/pdf"), f.mimeTypes, "$f") }
        has { "android.intent.action.SEND" in it.actions && it.mimeTypes == setOf("text/plain") }
        has { "android.intent.action.MAIN" in it.actions && "android.intent.category.APP_BROWSER" in it.categories }
        has { "android.intent.action.MAIN" in it.actions && "android.intent.category.LAUNCHER" in it.categories }
    }

    @Test
    fun `every VIEW filter is BROWSABLE and DEFAULT`() {
        filters.filter { "android.intent.action.VIEW" in it.actions }.forEach { f ->
            assertTrue("android.intent.category.DEFAULT" in f.categories && "android.intent.category.BROWSABLE" in f.categories, "$f")
        }
    }

    @Test
    fun `download service is a dataSync foreground service`() {
        val service = elements(doc.documentElement, "service").first { it.attr("name") == ".downloads.HatchDownloadService" }
        assertEquals("dataSync", service.attr("foregroundServiceType"))
        assertEquals("false", service.attr("exported"))
    }
}
