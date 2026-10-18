package app.hatch.ui.find

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.state.content.FindResultState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.ui.icons.R as iconsR

/** "3/17" from the last [FindResultState]; empty query → nothing; no match → "0/0". */
internal fun findCounter(query: String, results: List<FindResultState>): String {
    if (query.isEmpty()) return ""
    val last = results.lastOrNull() ?: return ""
    val active = if (last.numberOfMatches == 0) 0 else last.activeMatchOrdinal + 1
    return "$active/${last.numberOfMatches}"
}

/**
 * Hand-rolled find bar (feature-findinpage is View-based). Engine API: `EngineSession.findAll/findNext/
 * clearFindMatches` (concept-engine `EngineSession.kt:1022-1032`); results arrive as
 * `ContentAction.AddFindResultAction` → `tab.content.findResults`. Closing clears the engine highlights and the
 * store's results (what `FindInPageFeature.unbind` does).
 */
@Composable
fun FindInPageBar(
    store: BrowserStore,
    tab: TabSessionState?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val session = tab?.engineState?.engineSession
    val counter = findCounter(query, tab?.content?.findResults ?: emptyList())

    LaunchedEffect(Unit) { focus.requestFocus() }
    DisposableEffect(session) {
        onDispose {
            session?.clearFindMatches()
            tab?.id?.let { store.dispatch(ContentAction.ClearFindResultsAction(it)) }
        }
    }

    Surface(tonalElevation = 2.dp, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = query,
                onValueChange = { text ->
                    query = text
                    if (text.isEmpty()) {
                        session?.clearFindMatches()
                        tab?.id?.let { store.dispatch(ContentAction.ClearFindResultsAction(it)) }
                    } else {
                        session?.findAll(text)
                    }
                },
                modifier = Modifier.weight(1f).focusRequester(focus),
                singleLine = true,
                placeholder = { Text("Find in page") },
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { session?.findNext(forward = true) }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            Text(counter, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 4.dp))
            IconButton(onClick = { session?.findNext(forward = false) }, enabled = query.isNotEmpty()) {
                Icon(painterResource(iconsR.drawable.mozac_ic_chevron_up_24), contentDescription = "Previous match")
            }
            IconButton(onClick = { session?.findNext(forward = true) }, enabled = query.isNotEmpty()) {
                Icon(painterResource(iconsR.drawable.mozac_ic_chevron_down_24), contentDescription = "Next match")
            }
            IconButton(onClick = onClose) {
                Icon(painterResource(iconsR.drawable.mozac_ic_cross_24), contentDescription = "Close find bar")
            }
        }
    }
}
