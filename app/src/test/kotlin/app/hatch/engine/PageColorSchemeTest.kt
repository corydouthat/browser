package app.hatch.engine

import android.content.res.Configuration
import mozilla.components.concept.engine.mediaquery.PreferredColorScheme
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RS-02: pages see the system's dark setting, explicitly, so the Activity's AppCompat uiMode override never reaches them. */
class PageColorSchemeTest {
    @Test
    fun `system night bits map to an explicit scheme, never System`() {
        assertEquals(PreferredColorScheme.Dark, PageColorScheme.of(Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL))
        assertEquals(PreferredColorScheme.Light, PageColorScheme.of(Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL))
        assertEquals(PreferredColorScheme.Light, PageColorScheme.of(Configuration.UI_MODE_NIGHT_UNDEFINED))
    }

    @Test
    fun `the engine is created with the scheme installed and nothing else sets it`() {
        val root = listOf(File("src/main/kotlin/app/hatch"), File("app/src/main/kotlin/app/hatch")).first { it.isDirectory }
        val components = File(root, "Components.kt").readText()
        assertTrue("PageColorScheme.install(context, it)" in components)
        val setters = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""preferredColorScheme\s*=""").containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertEquals(listOf("PageColorScheme.kt"), setters)
    }
}
