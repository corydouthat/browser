package app.hatch.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.hatch.ui.toolbar.ToolbarState
import mozilla.components.browser.state.state.SecurityInfo
import mozilla.components.browser.state.state.TabSessionState

/** Rows shown in the site-info dialog (pure, so the composable stays thin). */
internal fun siteInfoRows(url: String, info: SecurityInfo): List<Pair<String, String>> {
    val host = info.host.ifBlank { OpenHost.of(url) ?: url }
    val rows = mutableListOf("Site" to host)
    rows += "Connection" to when (ToolbarState.securityBadge(info)) {
        ToolbarState.SecurityBadge.Secure -> "Secure (HTTPS, certificate valid)"
        ToolbarState.SecurityBadge.Insecure -> "Not secure"
        ToolbarState.SecurityBadge.Unknown -> "Unknown"
    }
    if (info.issuer.isNotBlank()) rows += "Verified by" to info.issuer
    info.certificate?.let { cert ->
        rows += "Certificate" to cert.subjectX500Principal.name.substringAfter("CN=").substringBefore(",")
        rows += "Expires" to cert.notAfter.toString()
    }
    return rows
}

internal object OpenHost {
    fun of(url: String): String? {
        val i = url.indexOf("://")
        if (i <= 0) return null
        val rest = url.substring(i + 3)
        val end = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
        return rest.substring(0, end).takeIf { it.isNotEmpty() }
    }
}

/** Tap on the toolbar's security badge: host, connection state, issuer, certificate; link to site permissions. */
@Composable
fun SiteInfoDialog(tab: TabSessionState, onSitePermissions: () -> Unit, onDismiss: () -> Unit) {
    val rows = siteInfoRows(tab.content.url, tab.content.securityInfo)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tab.content.title.ifBlank { rows.first().second }, maxLines = 2) },
        text = {
            Column {
                rows.forEach { (k, v) ->
                    Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(tab.content.url, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = { onDismiss(); onSitePermissions() }) { Text("Site permissions") } },
    )
}
