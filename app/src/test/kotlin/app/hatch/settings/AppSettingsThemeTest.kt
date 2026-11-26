package app.hatch.settings

import android.content.ContextWrapper
import android.content.SharedPreferences
import app.hatch.ui.theme.HatchPalette
import app.hatch.ui.theme.ThemeMode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Settings › Appearance persistence: defaults, round trip, unknown stored values, and the [AppSettings.changes] tick. */
class AppSettingsThemeTest {
    @Test
    fun `defaults are Graphite and System`() {
        val s = AppSettings(FakeContext())
        assertEquals(HatchPalette.Graphite, s.palette)
        assertEquals(ThemeMode.System, s.themeMode)
    }

    @Test
    fun `palette and mode persist under their keys and tick changes`() {
        val ctx = FakeContext()
        val s = AppSettings(ctx)
        val before = s.changes.value
        s.palette = HatchPalette.Cobalt
        s.themeMode = ThemeMode.Dark
        assertEquals("cobalt", ctx.prefs.map[AppSettings.KEY_PALETTE])
        assertEquals("dark", ctx.prefs.map[AppSettings.KEY_THEME_MODE])
        assertTrue(s.changes.value >= before + 2, "each write bumps changes")
        val reopened = AppSettings(ctx)
        assertEquals(HatchPalette.Cobalt, reopened.palette)
        assertEquals(ThemeMode.Dark, reopened.themeMode)
    }

    @Test
    fun `unknown stored values fall back to the defaults`() {
        val ctx = FakeContext()
        ctx.prefs.map[AppSettings.KEY_PALETTE] = "sepia"
        ctx.prefs.map[AppSettings.KEY_THEME_MODE] = "auto"
        val s = AppSettings(ctx)
        assertEquals(HatchPalette.Graphite, s.palette)
        assertEquals(ThemeMode.System, s.themeMode)
    }

    private class FakeContext : ContextWrapper(null) {
        val prefs = FakePrefs()

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    /** In-memory SharedPreferences: enough of the contract for AppSettings (get/put string, listeners on apply). */
    private class FakePrefs : SharedPreferences {
        val map = mutableMapOf<String, Any?>()
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = key in map
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners += l
        }
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners -= l
        }

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
                pending[key] = values?.toSet()
                return this
            }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun remove(key: String): SharedPreferences.Editor {
                pending[key] = null
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) map.clear()
                pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
                pending.keys.forEach { k -> listeners.forEach { it.onSharedPreferenceChanged(this@FakePrefs, k) } }
                pending.clear()
            }
        }
    }
}
