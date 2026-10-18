package app.hatch.memory

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** ENG-01: what onTrimMemory forwards, and when it must stay silent. */
class MemoryTrimTest {
    private val background = 40 // ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
    private val runningLow = 10 // ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW

    @Test
    fun `the main process trims the icon cache and dispatches LowMemoryAction`() {
        assertEquals(
            listOf(MemoryTrim.Action.TrimIconCache, MemoryTrim.Action.DispatchLowMemory),
            MemoryTrim.actionsFor(background, isMainProcess = true, storeInitialized = true, iconsInitialized = true),
        )
        assertEquals(
            listOf(MemoryTrim.Action.TrimIconCache, MemoryTrim.Action.DispatchLowMemory),
            MemoryTrim.actionsFor(runningLow, isMainProcess = true, storeInitialized = true, iconsInitialized = true),
        )
    }

    @Test
    fun `the restart process never touches the store or the icons`() {
        assertEquals(emptyList(), MemoryTrim.actionsFor(background, isMainProcess = false, storeInitialized = true, iconsInitialized = true))
    }

    @Test
    fun `nothing is created just to be trimmed`() {
        assertEquals(emptyList(), MemoryTrim.actionsFor(background, isMainProcess = true, storeInitialized = false, iconsInitialized = false))
        assertEquals(listOf(MemoryTrim.Action.TrimIconCache), MemoryTrim.actionsFor(background, isMainProcess = true, storeInitialized = false, iconsInitialized = true))
        assertEquals(listOf(MemoryTrim.Action.DispatchLowMemory), MemoryTrim.actionsFor(background, isMainProcess = true, storeInitialized = true, iconsInitialized = false))
    }
}
