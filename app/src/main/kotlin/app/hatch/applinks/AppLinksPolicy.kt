package app.hatch.applinks

/**
 * What Hatch does with a link that an installed app claims (`AppLinksFeature` / `AppLinksInterceptor`,
 * Android Components 158). Persisted in `AppSettings.appLinksMode` as [key].
 *
 * Fenix 158 `Settings.shouldOpenLinksInApp` / `shouldPromptOpenLinksInApp` (`utils/Settings.kt:2047-2065`) use
 * the same two switches; Hatch's default is the opposite of Fenix's ("never" instead of "ask") because the
 * whole point of the app is to stay on the website (m1-inventory risk 3).
 */
enum class AppLinksMode(val key: String, val title: String, val description: String) {
    /** Default. http(s) links always load in Hatch; `intent://`, `market:` and other non-web schemes still ask. */
    STAY_IN_HATCH(
        "stay",
        "Stay in Hatch",
        "Web links never jump to YouTube, Reddit or other apps. Links with a non-web scheme (intent://, market:) still ask.",
    ),

    /** Links an app claims show the "Open in app?" dialog first. */
    ASK("ask", "Ask", "Show \"Open in app?\" for every link an installed app claims."),

    /** Links an app claims open that app without asking. */
    /**
     * SEC-5: with `shouldPrompt = false` AC's `AppLinksFeature` launches the app for any navigation it intercepts, so a
     * page can open an installed app (Play Store via `market:`, any BROWSABLE activity) without a tap. Fenix parity.
     */
    ALWAYS(
        "always",
        "Always open in app",
        "Links an installed app claims open that app without asking. Pages can open apps without a tap in this mode.",
    ),
    ;

    companion object {
        val DEFAULT: AppLinksMode = STAY_IN_HATCH

        /** Tolerant lookup for the stored key (unknown / null → [DEFAULT]). */
        fun fromKey(key: String?): AppLinksMode = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * The two lambdas AC's app-links code takes, derived from an [AppLinksMode] (pure, JVM-tested).
 *
 * - `AppLinksFeature(launchInApp, shouldPrompt)` (`AppLinksFeature.kt:99,105` at FIREFOX_158_0_RELEASE):
 *   `shouldPrompt() == false` bypasses the redirect dialog (`shouldBypassPrompt`, `:182`); with
 *   `launchInApp() == false` the feature only ever sees intents for non-web schemes.
 * - `AppLinksInterceptor(launchInApp)` (`AppLinksInterceptor.kt:66`): `!launchInApp() && engineSupportsScheme`
 *   means "do not intercept" (`:109`, `:132`), and for a non-web scheme `handleRedirect` prefers the
 *   `fallbackUrl` (`:171-175`) before producing an `AppIntent` the feature then prompts for.
 */
data class AppLinksPolicy(val launchInApp: Boolean, val shouldPrompt: Boolean) {
    companion object {
        fun of(mode: AppLinksMode): AppLinksPolicy = when (mode) {
            AppLinksMode.STAY_IN_HATCH -> AppLinksPolicy(launchInApp = false, shouldPrompt = true)
            AppLinksMode.ASK -> AppLinksPolicy(launchInApp = true, shouldPrompt = true)
            AppLinksMode.ALWAYS -> AppLinksPolicy(launchInApp = true, shouldPrompt = false)
        }
    }
}
