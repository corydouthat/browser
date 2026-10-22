package app.hatch.engine

import app.hatch.persona.PrefSet
import app.hatch.persona.PrefValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.browser.engine.gecko.await
import org.mozilla.geckoview.ExperimentalGeckoViewApi
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoPreferenceController.GeckoPreference
import org.mozilla.geckoview.GeckoPreferenceController.SetGeckoPreference

/**
 * The ONLY file that touches the experimental `GeckoPreferenceController` (FIREFOX_158_0_RELEASE:
 * `setGeckoPrefs(List<SetGeckoPreference<?>>)`, `clearGeckoUserPref(String)`, `getGeckoPrefs(List<String>)`;
 * all `@HandlerThread` = Gecko UI thread, hence `withContext(Dispatchers.Main)`).
 *
 * There is no yaml fallback: `geckoview-config.yaml` prefs land on the DEFAULT branch (`PersonaToPrefs.yaml`), so
 * persona prefs must go through here, on the user branch (persisted in the profile's `prefs.js`).
 */
// ExperimentalGeckoViewApi is an androidx RequiresOptIn marker (Java), so Android lint only honours the
// androidx OptIn annotation here; Kotlin's own @OptIn does not satisfy UnsafeOptInUsageError.
@androidx.annotation.OptIn(ExperimentalGeckoViewApi::class)
object GeckoPrefs {
    /** One readable row of the "Engine prefs" truth view. */
    data class PrefRow(val name: String, val value: String?, val userSet: Boolean, val type: String)

    /**
     * Sets every pref in [prefs].set on the user branch, then clears every pref in [prefs].clear.
     * Returns the per-pref "isSet" map Gecko reports for the set half (cleared prefs are not in it).
     */
    suspend fun apply(prefs: PrefSet): Map<String, Boolean> = withContext(Dispatchers.Main) {
        val list: List<SetGeckoPreference<*>> = prefs.set.map { (name, value) ->
            when (value) {
                is PrefValue.Str -> SetGeckoPreference.setStringPref(name, value.v, GeckoPreferenceController.PREF_BRANCH_USER)
                is PrefValue.Num -> SetGeckoPreference.setIntPref(name, value.v, GeckoPreferenceController.PREF_BRANCH_USER)
                is PrefValue.Bool -> SetGeckoPreference.setBoolPref(name, value.v, GeckoPreferenceController.PREF_BRANCH_USER)
            }
        }
        val result: Map<String, Boolean> =
            if (list.isEmpty()) emptyMap() else GeckoPreferenceController.setGeckoPrefs(list).await() ?: emptyMap()
        for (name in prefs.clear) {
            GeckoPreferenceController.clearGeckoUserPref(name).await()
        }
        result
    }

    /** Reads [names] back from Gecko (default + user value) for the Settings truth view. */
    suspend fun read(names: List<String>): List<PrefRow> = withContext(Dispatchers.Main) {
        if (names.isEmpty()) return@withContext emptyList()
        val prefs: List<GeckoPreference<*>> = GeckoPreferenceController.getGeckoPrefs(names).await() ?: emptyList()
        prefs.map { p ->
            PrefRow(
                name = p.pref,
                value = p.getValue()?.toString(),
                userSet = p.hasUserChangedValue,
                type = when (p.type) {
                    GeckoPreferenceController.PREF_TYPE_STRING -> "string"
                    GeckoPreferenceController.PREF_TYPE_INT -> "int"
                    GeckoPreferenceController.PREF_TYPE_BOOL -> "bool"
                    else -> "invalid"
                },
            )
        }
    }
}
