package app.hatch.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.hatch.selfcheck.SelfCheckController
import kotlinx.coroutines.launch
import mozilla.components.concept.engine.EngineView

/** Self-check controls above the web content. The local server lives exactly as long as this bar is shown. */
@Composable
fun SelfCheckBar(controller: SelfCheckController, engineView: EngineView, onClose: () -> Unit) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    DisposableEffect(controller) {
        controller.open()
        onDispose { controller.close() }
    }

    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Button(onClick = { controller.run(personaOff = false) }, enabled = !state.running) { Text("Run") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { controller.run(personaOff = true) }, enabled = !state.running) { Text("Run with persona off") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val intent = controller.buildShareIntent(engineView)
                            if (intent != null) context.startActivity(intent)
                        }
                    },
                    enabled = state.latestReport != null,
                ) { Text("Share report") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onClose) { Text("Close") }
            }
            Text(state.summary ?: state.status, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            Text("Reports: ${controller.reportsDir.absolutePath}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}
