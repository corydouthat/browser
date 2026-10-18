package app.hatch.ui.logins

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hatch.logins.LoginDisplay
import app.hatch.logins.LoginPickerState
import mozilla.components.concept.storage.Login

/**
 * Compose rendering of [LoginPickerState]: the bar GeckoView's `onLoginSelect` prompt turns into when more
 * than one saved login matches the form (a single match is filled silently by the engine when
 * `loginAutofillEnabled` is on). Sits at the bottom of the browser, above the keyboard.
 */
@Composable
fun LoginPickerBar(state: LoginPickerState, modifier: Modifier = Modifier) {
    if (!state.isPromptDisplayed) return
    Surface(
        modifier = modifier.fillMaxWidth().imePadding().navigationBarsPadding(),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { state.expanded = !state.expanded }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Key, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = if (state.logins.size == 1) "Use saved login" else "Use saved login (${state.logins.size})",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { state.expanded = !state.expanded }) {
                    Icon(
                        if (state.expanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                        contentDescription = if (state.expanded) "Collapse" else "Expand",
                    )
                }
            }
            if (state.expanded) {
                HorizontalDivider()
                LazyColumn(Modifier.heightIn(max = 220.dp)) {
                    items(state.logins, key = { it.guid }) { login -> LoginRow(login) { state.select(login) } }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                    TextButton(onClick = { state.manage() }) { Text("Manage logins") }
                }
            }
        }
    }
}

@Composable
private fun LoginRow(login: Login, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(login.username.ifEmpty { "(no username)" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(LoginDisplay.hostLabel(login.origin), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
