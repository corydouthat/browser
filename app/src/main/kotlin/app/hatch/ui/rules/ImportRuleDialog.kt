package app.hatch.ui.rules

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.hatch.Components
import app.hatch.rules.SiteRule
import app.hatch.rules.UserscriptImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.concept.fetch.Client
import mozilla.components.concept.fetch.Request
import mozilla.components.concept.fetch.isSuccess
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Import a `.user.js` / `.user.css` from an https URL (fetched with the engine's cookie-aware client) or from the
 * clipboard. The header preview (`UserscriptHeader.parse` via [UserscriptImport]) shows name, @match, @exclude,
 * @grant and @require before anything is saved; refusals (@require, GM_xmlhttpRequest, unsupported grants) block
 * the Add button with the reason.
 */
@Composable
fun ImportRuleDialog(
    components: Components,
    onDismiss: () -> Unit,
    onAdd: (SiteRule) -> Unit,
    onEdit: (SiteRule) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<UserscriptImport.Result?>(null) }

    fun preview(text: String, hintUrl: String?) {
        result = UserscriptImport.fromText(text, hintUrl)
        error = null
    }

    fun fetchUrl() {
        val u = url.trim()
        if (!UserscriptImport.isAllowedUrl(u)) { error = UserscriptImport.URL_REJECTED; return }
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { fetchText(components.client, u) }
            }
            busy = false
            r.onSuccess { preview(it, u) }.onFailure { error = "Download failed: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    fun pasteClipboard() {
        val text = clipboardText(context)
        if (text.isNullOrBlank()) error = "Clipboard is empty" else preview(text, null)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (result == null) "Import userscript / userstyle" else "Import preview") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 460.dp)) {
                val r = result
                if (r == null) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(".user.js / .user.css URL (https)") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Button(onClick = ::fetchUrl, enabled = !busy && url.isNotBlank()) { Text("Fetch") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = ::pasteClipboard, enabled = !busy) { Text("From clipboard") }
                        if (busy) { Spacer(Modifier.width(12.dp)); CircularProgressIndicator(Modifier.padding(top = 8.dp).width(20.dp)) }
                    }
                    Text(
                        "Allowed: @match / @include patterns, @run-at, and the GM subset (GM_addStyle, GM_get/setValue, GM_log, GM_info). " +
                            "Refused: @require, GM_xmlhttpRequest.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    PreviewBody(r)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            val r = result
            if (r?.rule != null && r.ok) {
                Button(onClick = { onAdd(r.rule) }) { Text("Add (disabled)") }
            } else if (r != null) {
                TextButton(onClick = { result = null }) { Text("Back") }
            }
        },
        dismissButton = {
            val r = result
            if (r?.rule != null && r.ok) {
                Row {
                    TextButton(onClick = { result = null }) { Text("Back") }
                    TextButton(onClick = { onEdit(r.rule) }) { Text("Edit…") }
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun PreviewBody(r: UserscriptImport.Result) {
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    Text(if (r.kind == UserscriptImport.Kind.USER_JS) "Userscript (.user.js)" else "User style (.user.css)", style = MaterialTheme.typography.labelLarge)
    r.rule?.let { rule ->
        Text("Name: ${rule.name}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        Text("Runs at ${rule.runAt.removePrefix("document_")} in the ${rule.world} world", style = MaterialTheme.typography.bodySmall)
    }
    r.meta?.let { m ->
        m.version?.let { Text("Version: $it", style = MaterialTheme.typography.bodySmall) }
        m.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    val matches = r.rule?.matches ?: r.meta?.matches.orEmpty()
    if (matches.isNotEmpty()) {
        Text("@match", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        matches.forEach { Text(it, style = mono) }
    }
    val excludes = r.rule?.excludeMatches ?: r.meta?.excludes.orEmpty()
    if (excludes.isNotEmpty()) {
        Text("@exclude", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        excludes.forEach { Text(it, style = mono) }
    }
    r.meta?.grants?.takeIf { it.isNotEmpty() }?.let { grants ->
        Text("@grant", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        grants.forEach { Text(it, style = mono) }
    }
    r.meta?.requires?.takeIf { it.isNotEmpty() }?.let { reqs ->
        Text("@require", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        reqs.forEach { Text(it, style = mono, color = MaterialTheme.colorScheme.error) }
    }
    if (r.problems.isNotEmpty()) {
        Text("Refused", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        r.problems.forEach { Text("• $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    if (r.warnings.isNotEmpty()) {
        Text("Notes", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        r.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
    val body = r.rule?.js ?: r.rule?.css
    if (body != null) {
        Text("Source (${body.length} chars)", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        Text(body.lineSequence().take(12).joinToString("\n"), style = mono, maxLines = 12)
    }
}

internal fun clipboardText(context: Context): String? {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

/**
 * GET [url] through [client]; https only (checked by the caller on the typed string and again here on the response,
 * SEC-3), body capped at [UserscriptImport.MAX_BYTES].
 *
 * concept-fetch defaults are `Redirect.FOLLOW` + `CookiePolicy.INCLUDE` (`Request.kt:50-51`), which let an
 * https -> http redirect be followed silently and sent the user's cookies for the script host with a public
 * download. `Redirect.MANUAL` maps to `GeckoWebExecutor.FETCH_FLAGS_NO_REDIRECTS` and `CookiePolicy.OMIT` to
 * `FETCH_FLAGS_ANONYMOUS` (`GeckoViewFetchClient.kt:66-74`): a 3xx comes back as such and is refused with a hint to
 * paste the final URL; a response whose own URL is not https is refused too.
 */
internal fun fetchText(client: Client, url: String): String {
    val request = Request(
        url = url,
        connectTimeout = 15L to TimeUnit.SECONDS,
        readTimeout = 30L to TimeUnit.SECONDS,
        redirect = Request.Redirect.MANUAL,
        cookiePolicy = Request.CookiePolicy.OMIT,
        useCaches = false,
    )
    client.fetch(request).use { response ->
        if (response.status in REDIRECT_RANGE) throw IllegalStateException(REDIRECT_REFUSED)
        if (!UserscriptImport.isAllowedUrl(response.url)) throw IllegalStateException(NOT_HTTPS_REFUSED)
        if (!response.isSuccess) throw IllegalStateException("HTTP ${response.status}")
        response.headers["Content-Length"]?.toLongOrNull()?.let { if (it > UserscriptImport.MAX_BYTES) throw IllegalStateException("file larger than 1 MB") }
        return response.body.useStream { readCapped(it, UserscriptImport.MAX_BYTES) }
    }
}

internal val REDIRECT_RANGE = 300..399
internal const val REDIRECT_REFUSED = "redirects are not followed, paste the final URL"
internal const val NOT_HTTPS_REFUSED = "the server answered from a non-https URL"

internal fun readCapped(stream: InputStream, max: Long): String {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val n = stream.read(buf)
        if (n < 0) break
        total += n
        if (total > max) throw IllegalStateException("file larger than 1 MB")
        out.write(buf, 0, n)
    }
    return out.toString(Charsets.UTF_8.name())
}
