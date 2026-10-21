package app.hatch.ui.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.hatch.ui.theme.HatchDimens
import app.hatch.ui.theme.HatchPalette
import app.hatch.ui.theme.HatchShapes
import app.hatch.ui.theme.hatchFocusRing
import mozilla.components.compose.browser.toolbar.concept.Action
import mozilla.components.compose.browser.toolbar.concept.Action.ActionButtonRes
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction.BrowserToolbarEvent
import mozilla.components.compose.browser.toolbar.ui.BrowserToolbarQuery
import mozilla.components.concept.toolbar.AutocompleteResult

/**
 * Edit mode (mockup "New tab · edit mode"): the same 56 dp row; the 44 dp pill gets a 2 dp primary inset outline, a
 * 16 dp search glyph, the ported [InlineAutocompleteTextField] ("Search or enter address") and the store's edit end
 * actions (the clear button while there is text, `ToolbarState.editEndActions`); tab counter and overflow stay.
 *
 * Fed from the `BrowserToolbarStore`'s `EditState` exactly like AC's `BrowserToolbar` feeds `BrowserEditToolbar`
 * (BrowserToolbar.kt:88-107 at FIREFOX_158_0_RELEASE): [onUrlEdit] → `SearchQueryUpdated`, [onUrlCommitted] →
 * `CommitUrl` (IME "Go" / Enter), action clicks → their events. The field takes focus and shows the keyboard when it
 * enters composition, i.e. when the toolbar enters edit mode.
 */
@Composable
fun EditToolbar(
    query: String,
    hint: String,
    suggestion: AutocompleteResult?,
    isQueryPrefilled: Boolean,
    endActions: List<Action>,
    tabCount: Int,
    colors: ToolbarColors,
    onUrlEdit: (BrowserToolbarQuery) -> Unit,
    onUrlCommitted: (String) -> Unit,
    onEvent: (BrowserToolbarEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fieldColors = InlineAutocompleteColors(
        text = colors.text,
        hint = colors.hint,
        cursor = colors.outline,
        autofillBackground = colors.outline.copy(alpha = AUTOFILL_ALPHA),
        selectionBackground = colors.outline.copy(alpha = SELECTION_ALPHA),
    )
    ToolbarRow(colors, modifier) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = HatchDimens.Pill)
                .background(colors.pill, HatchShapes.Pill)
                .border(OUTLINE, colors.outline, HatchShapes.Pill)
                .padding(start = 14.dp, end = if (endActions.isEmpty()) 14.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(ToolbarIcons.Search, contentDescription = null, tint = colors.hint, modifier = Modifier.size(16.dp))
            InlineAutocompleteTextField(
                query = query,
                hint = hint,
                suggestion = suggestion,
                showQueryAsPreselected = isQueryPrefilled,
                usePrivateModeQueries = false,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = fieldColors,
                onUrlEdit = onUrlEdit,
                onUrlCommitted = onUrlCommitted,
            )
            for (action in endActions) EditAction(action, colors, onEvent)
        }
        ToolbarEndActions(tabCount, colors, onEvent)
    }
}

/**
 * One store-provided edit action (only [ActionButtonRes] is used by Hatch: the clear button): a 36 dp circle (the
 * mockup) centred in a [EditActionTouch] box that is the clickable (A11Y-08): 48 dp wide and the pill's full 44 dp
 * high, so the target does not depend on hit expansion next to the text field (which would win a direct hit).
 */
@Composable
private fun EditAction(action: Action, colors: ToolbarColors, onEvent: (BrowserToolbarEvent) -> Unit) {
    if (action !is ActionButtonRes) return
    val event = action.onClick as? BrowserToolbarEvent ?: return
    val label = stringResource(action.contentDescription)
    Box(
        Modifier
            .size(width = EditActionTouch.Width, height = EditActionTouch.Height)
            .hatchFocusRing(CircleShape)
            .clip(CircleShape)
            .clickable(role = Role.Button) { onEvent(event) }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(action.drawableResId), contentDescription = null, tint = colors.hint, modifier = Modifier.size(20.dp))
        }
    }
}

private val OUTLINE = 2.dp

/** The clear button's touch box inside the 44 dp pill (A11Y-08). */
internal object EditActionTouch {
    val Width = 48.dp
    val Height = 44.dp
}

/** Inline-suggestion background: the primary at ~30 % (the design gives no token; readable under ink on every palette). */
internal const val AUTOFILL_ALPHA = 0.3f

/** Selected-text background: the primary at 40 %. */
internal const val SELECTION_ALPHA = 0.4f

// ---- previews ------------------------------------------------------------------------------------------------------

@Preview(name = "Edit · empty · Graphite light", widthDp = 360)
@Composable
internal fun EditEmptyGraphiteLight() = ToolbarPreviewFrame(HatchPalette.Graphite, dark = false, desktop = true) {
    EditToolbar("", "Search or enter address", null, false, emptyList(), 5, it, {}, {}, {})
}

@Preview(name = "Edit · empty · Graphite dark", widthDp = 360)
@Composable
internal fun EditEmptyGraphiteDark() = ToolbarPreviewFrame(HatchPalette.Graphite, dark = true, desktop = true) {
    EditToolbar("", "Search or enter address", null, false, emptyList(), 5, it, {}, {}, {})
}

private val previewSuggestion = AutocompleteResult(input = "wiki", text = "wikipedia.org", url = "https://wikipedia.org", source = "preview", totalItems = 1)

@Preview(name = "Edit · suggestion · Cobalt light", widthDp = 360)
@Composable
internal fun EditSuggestionCobaltLight() = ToolbarPreviewFrame(HatchPalette.Cobalt, dark = false, desktop = true) {
    EditToolbar("wiki", "Search or enter address", previewSuggestion, false, ToolbarState.editEndActions("wiki"), 5, it, {}, {}, {})
}

@Preview(name = "Edit · suggestion · phone · Raspberry dark", widthDp = 360)
@Composable
internal fun EditSuggestionRaspberryDark() = ToolbarPreviewFrame(HatchPalette.Raspberry, dark = true, desktop = false) {
    EditToolbar("wiki", "Search or enter address", previewSuggestion, false, ToolbarState.editEndActions("wiki"), 5, it, {}, {}, {})
}
