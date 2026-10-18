package app.hatch.ui.tabs

import androidx.compose.material3.SnackbarDuration

/**
 * One close path for tabs (UXP-03 / UXP-04): the tray, the tab counter's long-press menu and Ctrl+W all remove
 * tabs through `HatchApp.closeTabs`, which shows the undo snackbar described here on the app-wide host.
 *
 * The undo window has two halves that must agree: `UndoMiddleware(clearAfterMillis = UNDO_WINDOW_MS)` keeps the
 * closed tabs recoverable, and the snackbar must be gone before that history is cleared, or "Undo" would be offered
 * when it can no longer work (Fenix dismisses its snackbar at the undo delay, `utils/Undo.kt`). Material 3 shows a
 * [SnackbarDuration.Short] snackbar for 4 s (`SnackbarDuration.toMillis`: Short = 4000, Long = 10000), so the
 * middleware keeps the tabs for [UNDO_WINDOW_MS] = 5 s.
 */
object TabClosePolicy {
    /** How long `UndoMiddleware` keeps closed tabs recoverable. */
    const val UNDO_WINDOW_MS: Long = 5_000L

    /** What M3 shows a [SnackbarDuration.Short] snackbar for; must stay below [UNDO_WINDOW_MS]. */
    const val SNACKBAR_SHORT_MS: Long = 4_000L

    val snackbarDuration: SnackbarDuration = SnackbarDuration.Short

    const val UNDO_LABEL = "Undo"

    /** Snackbar text after closing [count] tabs. */
    fun message(count: Int): String = if (count == 1) "Tab closed" else "$count tabs closed"

    /** The snackbar every close path raises (pure, so the recorder test can assert it). */
    data class UndoRequest(val message: String, val actionLabel: String, val duration: SnackbarDuration)

    fun undoRequest(count: Int): UndoRequest = UndoRequest(message(count), UNDO_LABEL, snackbarDuration)
}
