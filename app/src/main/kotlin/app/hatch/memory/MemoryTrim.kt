package app.hatch.memory

/**
 * ENG-01: what `HatchApplication.onTrimMemory` does with a `ComponentCallbacks2` level (pure; JVM-tested).
 *
 * Fenix `FenixApplication.onTrimMemory` (FIREFOX_158_0_RELEASE, :767-791) forwards every level, in the main
 * process only, to `BrowserIcons.onTrimMemory(level)` (favicon memory cache) and
 * `BrowserStore.dispatch(SystemAction.LowMemoryAction(level))`; AC's `TrimMemoryMiddleware` then suspends every
 * engine session except the selected tab and the three most recently used (`EngineMiddleware`, MIN_ACTIVE_TABS = 3)
 * for the levels `shouldCloseEngineSessions` accepts. Nothing else in GeckoView closes sessions under pressure.
 * The `:restart` trampoline process must never touch the store (it would initialise Gecko), and a trim that
 * arrives before the browser UI ever created the store has nothing to trim.
 */
object MemoryTrim {
    enum class Action { TrimIconCache, DispatchLowMemory }

    fun actionsFor(level: Int, isMainProcess: Boolean, storeInitialized: Boolean, iconsInitialized: Boolean): List<Action> {
        if (!isMainProcess || level < 0) return emptyList()
        val out = ArrayList<Action>(2)
        if (iconsInitialized) out += Action.TrimIconCache
        if (storeInitialized) out += Action.DispatchLowMemory
        return out
    }
}
