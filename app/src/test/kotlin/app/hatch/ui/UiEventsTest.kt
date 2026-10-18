package app.hatch.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** UXP-15: the bus buffers [UiEvents.BUFFER] events without a collector and a non-suspending collector drops none. */
class UiEventsTest {
    /**
     * Documents the stall UXP-15 removed: a collector that suspends inside a handler (the old Snackbar handler
     * waited for the snackbar to be dismissed) lets 16 further events queue and then every later shortcut is dropped.
     * (With no collector at all a SharedFlow drops silently and `tryEmit` is always true.)
     */
    @Test
    fun `a collector stuck in a handler queues 16 events and drops the next`() {
        val bus = UiEvents()
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        scope.launch { bus.events.collect { awaitCancellation() } }
        assertTrue(bus.emit(UiEvent.Reload), "delivered to the collector, which now hangs")
        repeat(UiEvents.BUFFER) { assertTrue(bus.emit(UiEvent.Reload), "event $it fits the buffer") }
        assertFalse(bus.emit(UiEvent.Reload), "the buffer is full: the shortcut is lost")
        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun `a non-suspending collector receives every event in order`() {
        val bus = UiEvents()
        val seen = mutableListOf<UiEvent>()
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        scope.launch { bus.events.collect { event -> seen.add(event) } }
        val sent: List<UiEvent> = (1..40).map { UiEvent.SelectTabIndex(it) }
        sent.forEach { assertTrue(bus.emit(it)) }
        assertEquals(sent, seen.toList())
        scope.coroutineContext[Job]?.cancel()
    }
}
