package app.hatch.ui.find

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.hatch.R
import app.hatch.ui.theme.HatchDimens
import app.hatch.ui.theme.HatchPalette
import app.hatch.ui.theme.HatchShapes
import app.hatch.ui.theme.HatchType
import app.hatch.ui.toolbar.ToolbarColors
import app.hatch.ui.toolbar.ToolbarIconButton
import app.hatch.ui.toolbar.ToolbarIcons
import app.hatch.ui.toolbar.ToolbarPreviewFrame
import app.hatch.ui.toolbar.ToolbarRow
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.state.content.FindResultState
import mozilla.components.browser.state.store.BrowserStore

/** "3/17" from the last [FindResultState]; empty query → nothing; no match → "0/0". */
internal fun findCounter(query: String, results: List<FindResultState>): String {
    if (query.isEmpty()) return ""
    val last = results.lastOrNull() ?: return ""
    val active = if (last.numberOfMatches == 0) 0 else last.activeMatchOrdinal + 1
    return "$active/${last.numberOfMatches}"
}

/**
 * Hand-rolled find bar (feature-findinpage is View-based), drawn in the toolbar row instead of the toolbar (mockup
 * "Browser · Find + undo"). Engine API: `EngineSession.findAll/findNext/clearFindMatches` (concept-engine
 * `EngineSession.kt:1022-1032`); results arrive as `ContentAction.AddFindResultAction` → `tab.content.findResults`.
 * Closing clears the engine highlights and the store's results (what `FindInPageFeature.unbind` does).
 */
@Composable
fun FindInPageBar(
    store: BrowserStore,
    tab: TabSessionState?,
    colors: ToolbarColors,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val session = tab?.engineState?.engineSession
    val counter = findCounter(query, tab?.content?.findResults ?: emptyList())

    DisposableEffect(session) {
        onDispose {
            session?.clearFindMatches()
            tab?.id?.let { store.dispatch(ContentAction.ClearFindResultsAction(it)) }
        }
    }

    FindRow(
        query = query,
        counter = counter,
        colors = colors,
        onQueryChange = { text ->
            query = text
            if (text.isEmpty()) {
                session?.clearFindMatches()
                tab?.id?.let { store.dispatch(ContentAction.ClearFindResultsAction(it)) }
            } else {
                session?.findAll(text)
            }
        },
        onPrevious = { session?.findNext(forward = false) },
        onNext = { session?.findNext(forward = true) },
        onClose = onClose,
        modifier = modifier,
    )
}

/**
 * The find row's layout, stateless: pill (the toolbar's pill colour with a 2 dp primary outline) holding the query
 * field (bodyLarge) and the mono "3/12" counter, then up / down / close as 44 dp buttons (48 dp touch targets, so
 * the pill is `ToolbarTouchTargets.FIND_PILL_NARROWER_DP` = 12 dp narrower than the mockup's 210 dp at 360 dp; DF-02).
 * The row keeps the toolbar's tint and shows the `ln` hairline in phone mode, as in the mockup.
 */
@Composable
internal fun FindRow(
    query: String,
    counter: String,
    colors: ToolbarColors,
    onQueryChange: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    requestFocus: Boolean = true,
) {
    val focus = remember { FocusRequester() }
    if (requestFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    val hint = stringResource(R.string.find_hint)
    val counterLabel = stringResource(R.string.find_counter, counter)
    ToolbarRow(colors, modifier, showDivider = true) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = HatchDimens.Pill)
                .background(colors.pill, HatchShapes.Pill)
                .border(2.dp, colors.outline, HatchShapes.Pill)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.text)
            CompositionLocalProvider(
                LocalTextSelectionColors provides TextSelectionColors(colors.outline, colors.outline.copy(alpha = 0.4f)),
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focus)
                        .semantics { contentDescription = hint },
                    singleLine = true,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(colors.outline),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onNext() }),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Text(hint, style = textStyle.copy(color = colors.hint), maxLines = 1)
                            inner()
                        }
                    },
                )
            }
            if (counter.isNotEmpty()) {
                Text(
                    counter,
                    style = HatchType.mono,
                    color = colors.hint,
                    maxLines = 1,
                    modifier = Modifier.semantics {
                        contentDescription = counterLabel
                        liveRegion = LiveRegionMode.Polite
                    },
                )
            }
        }
        val enabled = query.isNotEmpty()
        ToolbarIconButton(ToolbarIcons.ChevronUp, stringResource(R.string.find_previous), colors.content, onPrevious, enabled = enabled)
        ToolbarIconButton(ToolbarIcons.ChevronDown, stringResource(R.string.find_next), colors.content, onNext, enabled = enabled)
        ToolbarIconButton(ToolbarIcons.Close, stringResource(R.string.find_close), colors.content, onClose)
    }
}

// ---- previews ------------------------------------------------------------------------------------------------------

@Preview(name = "Find · phone · Graphite light", widthDp = 360)
@Composable
internal fun FindGraphiteLight() = ToolbarPreviewFrame(HatchPalette.Graphite, dark = false, desktop = false) {
    FindRow("invoice", "3/12", it, {}, {}, {}, {}, requestFocus = false)
}

@Preview(name = "Find · phone · Graphite dark", widthDp = 360)
@Composable
internal fun FindGraphiteDark() = ToolbarPreviewFrame(HatchPalette.Graphite, dark = true, desktop = false) {
    FindRow("invoice", "3/12", it, {}, {}, {}, {}, requestFocus = false)
}

@Preview(name = "Find · desktop · Amber light · empty", widthDp = 360)
@Composable
internal fun FindAmberLight() = ToolbarPreviewFrame(HatchPalette.Amber, dark = false, desktop = true) {
    FindRow("", "", it, {}, {}, {}, {}, requestFocus = false)
}
