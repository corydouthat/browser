package app.hatch.ui.tabs

import kotlin.math.roundToInt

/**
 * Pure multi-select state of the tab tray (JVM-tested). Long-press or "Select tabs" enters select mode; tapping
 * toggles. Select mode is a flag of its own (UXP-07, Fenix `TabsTrayState.Mode.Select(emptySet())`): it starts
 * with nothing selected and survives an empty selection; only the user (back / top-bar X) or a bulk action leaves it.
 */
data class TabTrayState(
    val selectMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
) {
    val selectedCount: Int get() = selectedIds.size

    /** Enters select mode; [firstId] (a long-pressed card) is pre-selected, "Select tabs" passes nothing. */
    fun enterSelect(firstId: String? = null): TabTrayState =
        TabTrayState(selectMode = true, selectedIds = if (firstId == null) emptySet() else setOf(firstId))

    fun toggle(id: String): TabTrayState {
        if (!selectMode) return enterSelect(id)
        val next = if (id in selectedIds) selectedIds - id else selectedIds + id
        return copy(selectedIds = next)
    }

    fun exitSelect(): TabTrayState = TabTrayState()

    /** Drops ids that no longer exist (tabs closed elsewhere); stays in select mode. */
    fun retain(existingIds: Collection<String>): TabTrayState {
        if (!selectMode) return this
        return copy(selectedIds = selectedIds.intersect(existingIds.toSet()))
    }

    companion object {
        /** Grid columns by available width (dp): phone portrait 2, phone landscape / small tablet 3, wide 4. */
        fun columnsFor(widthDp: Int): Int = when {
            widthDp < 600 -> 2
            widthDp < 840 -> 3
            else -> 4
        }

        /** Grid geometry of `TabTrayScreen`: 12 dp outer padding on both sides, 12 dp between columns. */
        const val GRID_PADDING_DP = 12
        const val GRID_SPACING_DP = 12

        /** Width of one card in dp for the tray's grid (PERF-08: the thumbnail request size follows the card). */
        fun cardWidthDp(maxWidthDp: Float, columns: Int): Float {
            if (columns <= 0) return 0f
            val usable = maxWidthDp - 2 * GRID_PADDING_DP - GRID_SPACING_DP * (columns - 1)
            return (usable / columns).coerceAtLeast(0f)
        }

        /** The thumbnail request size in px for one card: the card width at [density], never below 1 px. */
        fun thumbnailSizePx(maxWidthDp: Float, columns: Int, density: Float): Int =
            (cardWidthDp(maxWidthDp, columns) * density).roundToInt().coerceAtLeast(1)

        /** Snackbar text after closing tabs (shared with the toolbar / Ctrl+W path through `TabClosePolicy`). */
        fun closedMessage(count: Int): String = TabClosePolicy.message(count)
    }
}
