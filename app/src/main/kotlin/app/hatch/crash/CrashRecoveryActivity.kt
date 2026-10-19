package app.hatch.crash

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.hatch.MainActivity

/**
 * Safe-mode screen shown by `MainActivity` when [CrashCapture.safeMode] is set. Deliberately minimal: plain
 * Material 3 (no dynamic colour lookup, no Hatch theme), no Gecko, no store, no storage; the only state it reads
 * is the text [CrashCapture] loaded before anything else ran.
 */
class CrashRecoveryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = CrashCapture.lastReport
        val text = buildString {
            appendLine("app: ${CrashCapture.appLine()}")
            appendLine("device: ${CrashCapture.deviceLine()}")
            appendLine()
            append(report ?: CrashReport.NO_JAVA_TRACE)
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RecoveryScreen(
                        text = text,
                        hasTrace = report != null,
                        onShare = { share(text) },
                        onCopy = { copy(text) },
                        onTryAgain = { tryAgain() },
                        onClearData = { clearData() },
                    )
                }
            }
        }
    }

    private fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Hatch crash report")
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Share crash report"))
    }

    private fun copy(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Hatch crash report", text))
    }

    private fun tryAgain() {
        CrashCapture.clear(this)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private fun clearData() {
        // Wipes files, prefs, databases and caches and kills the process; Android relaunches nothing, the user
        // opens Hatch again. The last resort when corrupted state (session file, logins DB) crashes every start.
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.clearApplicationUserData()
    }
}

@Composable
private fun RecoveryScreen(
    text: String,
    hasTrace: Boolean,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onTryAgain: () -> Unit,
    onClearData: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Hatch did not start last time", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (hasTrace) {
                "The crash below was captured. Share or copy it, then try again. If every start fails, clearing " +
                    "Hatch's data resets tabs, settings, rules and saved logins."
            } else {
                "No Java crash was captured. Try again; if every start fails, clearing Hatch's data resets tabs, " +
                    "settings, rules and saved logins."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onShare) { Text("Share") }
            OutlinedButton(onClick = onCopy) { Text("Copy") }
            OutlinedButton(onClick = onTryAgain) { Text("Try again") }
        }
        TextButton(onClick = { confirmClear = true }) { Text("Clear Hatch data…") }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()),
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all Hatch data?") },
            text = { Text("Tabs, settings, site rules, custom personas and saved logins are deleted. Hatch closes; open it again afterwards.") },
            confirmButton = { TextButton(onClick = onClearData) { Text("Clear and close") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
