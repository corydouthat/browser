package app.hatch.crash

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.hatch.MainActivity
import app.hatch.R
import app.hatch.ui.theme.HatchPalette
import app.hatch.ui.theme.HatchShapes
import app.hatch.ui.theme.HatchStaticTheme
import app.hatch.ui.theme.HatchTheme
import app.hatch.ui.theme.HatchType
import app.hatch.ui.theme.ThemeMode

/**
 * Safe-mode screen shown by `MainActivity` when [CrashCapture.safeMode] is set (mockup "Crash recovery"), and the
 * same screen in report mode ([reportIntent]) for a crash after startup, opened from the "Hatch closed
 * unexpectedly" snackbar: then the primary button only closes it and "Clear Hatch data" is not offered.
 * Deliberately minimal: [HatchStaticTheme] (Graphite following the system light / dark flag; no settings read),
 * no Gecko, no store, no storage; the only state it reads is the text [CrashCapture] loaded before anything else ran.
 */
class CrashRecoveryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val reportMode = intent.hasExtra(EXTRA_REPORT)
        val report = if (reportMode) intent.getStringExtra(EXTRA_REPORT) else CrashCapture.lastReport
        val text = buildString {
            appendLine("app: ${CrashCapture.appLine()}")
            appendLine("device: ${CrashCapture.deviceLine()}")
            appendLine()
            append(report ?: CrashReport.NO_JAVA_TRACE)
        }
        setContent {
            HatchStaticTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = HatchTheme.colors.bg, contentColor = HatchTheme.colors.ink) {
                    RecoveryScreen(
                        text = text,
                        hasTrace = report != null,
                        reportMode = reportMode,
                        onShare = { share(text) },
                        onCopy = { copy(text) },
                        onTryAgain = { if (reportMode) finish() else tryAgain() },
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

    companion object {
        private const val EXTRA_REPORT = "app.hatch.crash.REPORT"

        /** Report mode: shows [report] (a crash after startup) with Share / Copy and a Close button. */
        fun reportIntent(context: Context, report: String): Intent =
            Intent(context, CrashRecoveryActivity::class.java).putExtra(EXTRA_REPORT, report)
    }
}

@Composable
private fun RecoveryScreen(
    text: String,
    hasTrace: Boolean,
    reportMode: Boolean = false,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onTryAgain: () -> Unit,
    onClearData: () -> Unit,
) {
    val c = HatchTheme.colors
    var confirmClear by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 48.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.hatch_mark),
            contentDescription = "Hatch",
            modifier = Modifier.size(48.dp).clip(HatchShapes.Mark),
        )
        Text(
            if (reportMode) "Hatch closed unexpectedly" else "Hatch did not start last time",
            style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = (-0.01).em),
            color = c.ink,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            if (reportMode) {
                "Hatch stopped while you were browsing. Share the report below so it can be fixed."
            } else if (hasTrace) {
                "Something failed during startup. You can try again, or share the report below so it can be fixed."
            } else {
                "Something failed during startup, but no Java crash was captured. You can try again, or share the report below so it can be fixed."
            },
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.5.sp),
            color = c.mu,
        )
        RecoveryButton(if (reportMode) "Close" else "Try again", onTryAgain, Modifier.fillMaxWidth().padding(top = 4.dp), height = 48.dp, container = c.ac, content = c.oa, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RecoveryButton("Share", onShare, Modifier.weight(1f), height = 44.dp, container = c.s1, content = c.ink)
            RecoveryButton("Copy", onCopy, Modifier.weight(1f), height = 44.dp, container = c.s1, content = c.ink)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = TraceMaxHeight)
                .clip(RoundedCornerShape(14.dp))
                .background(c.s1)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Text(text = text, style = HatchType.mono.copy(fontSize = 10.5.sp, lineHeight = 16.8.sp), color = c.ink, softWrap = false)
        }
        if (!reportMode) {
            RecoveryButton("Clear Hatch data…", { confirmClear = true }, Modifier.fillMaxWidth(), height = 44.dp, container = Color.Transparent, content = c.dg)
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = c.sh,
            title = { Text("Clear all Hatch data?") },
            text = { Text("Tabs, settings, site rules, custom personas and saved logins are deleted. Hatch closes; open it again afterwards.") },
            confirmButton = { TextButton(onClick = onClearData) { Text("Clear and close", color = c.dg) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = c.acT) } },
        )
    }
}

/**
 * The screen's pill buttons, drawn here rather than with the Settings kit so the crash screen depends on nothing but
 * the static theme: fully rounded, [height] high (never under the 48 dp touch target), label in labelLarge.
 */
@Composable
private fun RecoveryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    height: androidx.compose.ui.unit.Dp,
    container: Color,
    content: Color,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelLarge,
) {
    Box(modifier.minimumInteractiveComponentSize(), contentAlignment = Alignment.Center, propagateMinConstraints = true) {
        Box(
            Modifier
                .heightIn(min = height)
                .clip(RoundedCornerShape(height / 2))
                .background(container)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = style, color = content)
        }
    }
}

private val TraceMaxHeight = 320.dp

@Preview(name = "Crash recovery · light", widthDp = 360, heightDp = 760)
@Composable
internal fun RecoveryScreenLightPreview() {
    HatchTheme(palette = HatchPalette.Graphite, mode = ThemeMode.Light) { RecoveryPreviewBody() }
}

@Preview(name = "Crash recovery · dark", widthDp = 360, heightDp = 760)
@Composable
internal fun RecoveryScreenDarkPreview() {
    HatchTheme(palette = HatchPalette.Graphite, mode = ThemeMode.Dark) { RecoveryPreviewBody() }
}

@Composable
private fun RecoveryPreviewBody() {
    val trace = listOf(
        "java.lang.IllegalStateException: Gecko runtime",
        "  at app.hatch.engine.Engine.start(Engine.kt:88)",
        "  at app.hatch.HatchApp.onCreate(HatchApp.kt:41)",
        "  at android.app.Instrumentation.callApp…",
        "Caused by: o.m.geckoview.GeckoRuntime…",
    ).joinToString("\n")
    Surface(color = HatchTheme.colors.bg) {
        RecoveryScreen(text = trace, hasTrace = true, onShare = {}, onCopy = {}, onTryAgain = {}, onClearData = {})
    }
}
