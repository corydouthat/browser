package app.hatch.ui.tabs

import androidx.compose.material3.SnackbarDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.action.UndoAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.session.middleware.undo.UndoMiddleware
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UXP-03 / UXP-04: the undo snackbar every close path raises, and the window it has to fit in.
 */
class TabClosePolicyTest {
    @Test
    fun `every close path requests the same undo snackbar`() {
        val one = TabClosePolicy.undoRequest(1)
        assertEquals("Tab closed", one.message)
        assertEquals("Undo", one.actionLabel)
        assertEquals(SnackbarDuration.Short, one.duration)
        assertEquals("3 tabs closed", TabClosePolicy.undoRequest(3).message)
        assertEquals(TabClosePolicy.message(2), TabTrayState.closedMessage(2))
    }

    @Test
    fun `the snackbar is gone before the undo history is cleared`() {
        // M3 SnackbarDuration.toMillis: Short = 4000 ms (Long = 10000, Indefinite = Long.MAX_VALUE).
        assertTrue(TabClosePolicy.SNACKBAR_SHORT_MS < TabClosePolicy.UNDO_WINDOW_MS)
        assertEquals(SnackbarDuration.Short, TabClosePolicy.snackbarDuration)
    }

    /**
     * Documents the window on a real `BrowserStore` + `UndoMiddleware`: within `clearAfterMillis` an undo restores
     * the tab, after it `RestoreRecoverableTabs` is a no-op ("No recoverable tabs"), which is why the snackbar must
     * not outlive the window.
     */
    @Test
    fun `undo works inside the middleware window and is a no-op after it`() {
        fun storeWithOneTab(clearAfterMillis: Long): BrowserStore {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            return BrowserStore(
                initialState = BrowserState(tabs = listOf(createTab("https://a.example/", id = "a")), selectedTabId = "a"),
                middleware = listOf(UndoMiddleware(clearAfterMillis = clearAfterMillis, mainScope = scope, waitScope = scope)),
            )
        }

        // lib-state 158 `Store.dispatch` is fire-and-forget (the reducer runs on the store's own thread); the
        // assertions poll `state` instead of joining.
        fun BrowserStore.dispatchAndJoin(action: mozilla.components.browser.state.action.BrowserAction) {
            dispatch(action)
            Thread.sleep(50)
        }

        fun BrowserStore.awaitTabs(expected: Int): Int {
            val deadline = System.currentTimeMillis() + 2_000
            while (state.tabs.size != expected && System.currentTimeMillis() < deadline) Thread.sleep(10)
            return state.tabs.size
        }

        // Inside the window: restored.
        val fast = storeWithOneTab(clearAfterMillis = 5_000)
        fast.dispatchAndJoin(TabListAction.RemoveTabAction("a"))
        assertEquals(0, fast.awaitTabs(0))
        fast.dispatchAndJoin(UndoAction.RestoreRecoverableTabs)
        assertEquals(1, fast.awaitTabs(1), "undo inside the window restores the tab")

        // After the window: the history is cleared and undo does nothing.
        val slow = storeWithOneTab(clearAfterMillis = 50)
        slow.dispatchAndJoin(TabListAction.RemoveTabAction("a"))
        assertEquals(0, slow.awaitTabs(0))
        Thread.sleep(300)
        slow.dispatchAndJoin(UndoAction.RestoreRecoverableTabs)
        Thread.sleep(200)
        assertEquals(0, slow.state.tabs.size, "undo after the window is a silent no-op")
        assertTrue(slow.state.undoHistory.tabs.isEmpty())
    }
}
