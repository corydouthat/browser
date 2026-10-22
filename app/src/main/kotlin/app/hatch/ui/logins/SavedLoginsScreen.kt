package app.hatch.ui.logins

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.hatch.Components
import app.hatch.logins.LoginDisplay
import app.hatch.ui.overlayPane
import app.hatch.ui.settings.CardDivider
import app.hatch.ui.settings.CardRowFocusShape
import app.hatch.ui.settings.FabClearance
import app.hatch.ui.settings.FormField
import app.hatch.ui.settings.GroupedCard
import app.hatch.ui.settings.HatchFab
import app.hatch.ui.settings.PageNote
import app.hatch.ui.settings.PrimaryPill
import app.hatch.ui.settings.ScreenIcons
import app.hatch.ui.settings.ScreenTopBar
import app.hatch.ui.settings.TopBarTextAction
import app.hatch.ui.surfaces.PreviewTheme
import app.hatch.ui.surfaces.SurfacePreviewFrame
import app.hatch.ui.surfaces.SurfacePreviewThemes
import app.hatch.ui.theme.HatchTheme
import app.hatch.ui.theme.HatchType
import app.hatch.ui.theme.hatchFocusRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.concept.storage.Login
import mozilla.components.concept.storage.LoginEntry

/**
 * Saved logins (mockups "Saved logins" and "Saved login detail"): search, the list with letter avatars, add, and a
 * detail page per login (username and password with copy and reveal, last-used / saved dates, edit, delete) over
 * `components.logins.storage`. The whole screen sits behind [DeviceAuth] (biometric or device credential), like
 * Fenix's `SavedLoginsAuthFragment`; once unlocked, the eye reveals a password in place (per guid, in
 * [app.hatch.logins.SavedLoginsLock]). Copies are marked sensitive on API 33+ so the clipboard preview hides them.
 *
 * SEC-1 (Fenix `SecureFragment` + `SavedLoginsFragment`): while this screen is composed the Activity window carries
 * `FLAG_SECURE` (no screenshots, screen recording or Recents thumbnail of a revealed password; cleared on dispose so
 * the browser itself stays capturable), and `Lifecycle.Event.ON_STOP` re-locks through [app.hatch.logins.SavedLoginsLock]:
 * the gate returns, revealed passwords are hidden and the loaded list is dropped. The detail page is part of this
 * screen, so both rules cover it.
 */
@Composable
fun SavedLoginsScreen(components: Components, onClose: () -> Unit, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val ui = components.loginsUi
    val lock = ui.lock
    val unlocked = lock.unlocked
    var authDenied by remember { mutableStateOf(false) }
    val auth = rememberDeviceAuth { ok -> if (ok) lock.unlock() else authDenied = true }
    val logins = lock.logins
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val revealed = lock.revealed

    val window = context.findActivity()?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, lock) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) lock.onStop() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            lock.onStop() // leaving the screen forgets the list and the reveals as well
            ui.detailGuid = null
        }
    }

    fun reload() {
        scope.launch {
            loading = true
            error = null
            runCatching { withContext(Dispatchers.IO) { components.logins.storage.value.list() } }
                .onSuccess { if (lock.unlocked) lock.logins = LoginDisplay.sorted(it) }
                .onFailure { error = "Could not open the logins database: ${it.message}" }
            loading = false
        }
    }

    LaunchedEffect(unlocked) {
        if (unlocked) {
            reload()
        } else {
            auth.authenticate("Unlock saved logins", "Confirm it is you to see passwords") { ok ->
                if (ok) lock.unlock() else authDenied = true
            }
        }
    }

    // DF-11: registrable domains per host for the titles and avatar letters ("secure.chase.com" → "chase.com").
    var registrable by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(logins) {
        val hosts = logins.mapNotNull { LoginFormat.lookupHost(it.origin) }.distinct().filter { it !in registrable }
        if (hosts.isEmpty()) return@LaunchedEffect
        val psl = components.publicSuffixList
        val found = hosts.mapNotNull { h -> runCatching { psl.getPublicSuffixPlusOne(h).await() }.getOrNull()?.let { h to it } }
        registrable = registrable + found
    }

    fun copy(label: String, value: String, message: String) {
        copySensitive(context, label, value)
        scope.launch { snackbar.showSnackbar(message) }
    }

    val detail = if (unlocked) ui.detailGuid?.let { g -> logins.firstOrNull { it.guid == g } } else null
    val c = HatchTheme.colors

    // A11Y-03 / A11Y-04: an opaque surface over the browser; the scaffold takes focus on open and clears the cutout.
    Surface(Modifier.fillMaxSize(), color = c.bg, contentColor = c.ink) {
        Scaffold(
            modifier = Modifier.overlayPane("Saved logins"),
            topBar = {
                if (detail != null) {
                    ScreenTopBar("", onNav = { ui.detailGuid = null }, actions = { TopBarTextAction("Edit", onClick = { lock.beginEdit(detail.guid) }) })
                } else {
                    ScreenTopBar("Saved logins", onNav = onClose)
                }
            },
            floatingActionButton = {
                if (unlocked && detail == null) HatchFab("Add login", onClick = { lock.beginAdd() })
            },
            containerColor = c.bg,
            contentColor = c.ink,
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).navigationBarsPadding()) {
                when {
                    !unlocked -> LockedContent(authDenied, auth.method()) {
                        authDenied = false
                        auth.authenticate("Unlock saved logins", "Confirm it is you to see passwords") { ok ->
                            if (ok) lock.unlock() else authDenied = true
                        }
                    }
                    detail != null -> LoginDetailContent(
                        login = detail,
                        registrable = registrable,
                        revealed = detail.guid in revealed,
                        now = System.currentTimeMillis(),
                        onToggleReveal = { lock.toggleReveal(detail.guid) },
                        onCopyUsername = { copy("username", detail.username, "Username copied") },
                        onCopyPassword = { copy("password", detail.password, "Password copied") },
                        onDelete = { lock.beginDelete(detail.guid) },
                    )
                    else -> LoginListContent(
                        logins = logins,
                        registrable = registrable,
                        query = query,
                        onQuery = { query = it },
                        loading = loading,
                        error = error,
                        onOpen = { ui.detailGuid = it.guid },
                    )
                }
            }
        }
    }

    // RS-01: every dialog is composed only while unlocked, from state SavedLoginsLock clears on ON_STOP; the Edit
    // dialog reads the login (and its password) from the freshly loaded list, never from state kept across a lock.
    if (!unlocked) return
    lock.deleting()?.let { login ->
        AlertDialog(
            onDismissRequest = { lock.closeDialogs() },
            containerColor = c.sh,
            title = { Text("Delete login?") },
            text = { Text("${login.username.ifEmpty { "(no username)" }} at ${LoginDisplay.hostLabel(login.origin)} will be removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    lock.closeDialogs()
                    scope.launch {
                        val ok = runCatching { withContext(Dispatchers.IO) { components.logins.storage.value.delete(login.guid) } }.getOrDefault(false)
                        if (ok && ui.detailGuid == login.guid) ui.detailGuid = null
                        snackbar.showSnackbar(if (ok) "Login deleted" else "Delete failed")
                        reload()
                    }
                }) { Text("Delete", color = c.dg) }
            },
            dismissButton = { TextButton(onClick = { lock.closeDialogs() }) { Text("Cancel", color = c.acT) } },
        )
    }

    if (lock.adding) {
        AddLoginDialog(
            onDismiss = { lock.closeDialogs() },
            onSave = { origin, username, password ->
                lock.closeDialogs()
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            components.logins.storage.value.add(LoginEntry(origin = origin, username = username, password = password))
                        }
                    }
                    snackbar.showSnackbar(result.fold({ "Login saved" }, { "Save failed: ${it.message}" }))
                    reload()
                }
            },
        )
    }

    lock.editing()?.let { login ->
        EditLoginDialog(
            login = login,
            onDismiss = { lock.closeDialogs() },
            onSave = { username, password ->
                lock.closeDialogs()
                scope.launch {
                    // `LoginsStorage.update(guid, entry)` (concept-storage LoginsStorage.kt:209): the entry keeps the
                    // login's origin, form action / realm and field names; only username and password change.
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            components.logins.storage.value.update(login.guid, login.toEntry().copy(username = username, password = password))
                        }
                    }
                    snackbar.showSnackbar(result.fold({ "Login updated" }, { "Update failed: ${it.message}" }))
                    reload()
                }
            },
        )
    }
}

@Composable
private fun LockedContent(denied: Boolean, method: DeviceAuth.Method, onRetry: () -> Unit) {
    val c = HatchTheme.colors
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(ScreenIcons.Key, contentDescription = null, tint = c.mu, modifier = Modifier.size(32.dp))
        Text("Locked", style = MaterialTheme.typography.titleLarge, color = c.ink)
        Text(
            when (method) {
                DeviceAuth.Method.NONE -> "This phone has no screen lock; the list opens without a check."
                else -> if (denied) "Authentication was cancelled or failed." else "Confirm your screen lock or fingerprint to see saved passwords."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = c.mu,
        )
        PrimaryPill(if (denied) "Try again" else "Unlock", onClick = onRetry, height = 48.dp)
    }
}

/** The list page: search pill, grouped card of logins (avatar, site, username), the FLAG_SECURE note. */
@Composable
internal fun LoginListContent(
    logins: List<Login>,
    registrable: Map<String, String>,
    query: String,
    onQuery: (String) -> Unit,
    loading: Boolean,
    error: String?,
    onOpen: (Login) -> Unit,
) {
    val c = HatchTheme.colors
    val shown = LoginDisplay.filter(logins, query)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp)) {
        item(key = "search") { SearchPill(query, onQuery, "Search ${logins.size} login${if (logins.size == 1) "" else "s"}") }
        error?.let { item(key = "error") { Text(it, color = c.dg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp)) } }
        if (loading && logins.isEmpty()) {
            item(key = "loading") { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.ac) } }
        }
        if (!loading && error == null && shown.isEmpty()) {
            item(key = "empty") {
                PageNote(if (logins.isEmpty()) "No saved logins yet. Hatch offers to save a login when you sign in to a site." else "No matches.", Modifier.padding(vertical = 8.dp))
            }
        }
        if (shown.isNotEmpty()) {
            item(key = "list") {
                GroupedCard {
                    shown.forEachIndexed { i, login ->
                        if (i > 0) CardDivider()
                        LoginRow(login, registrable.forOrigin(login.origin)) { onOpen(login) }
                    }
                }
            }
        }
        item(key = "note") {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(ScreenIcons.HiddenNote, contentDescription = null, tint = c.mu, modifier = Modifier.size(14.dp))
                Text("Hidden from screenshots and recents", style = MaterialTheme.typography.bodySmall, color = c.mu)
            }
        }
        item(key = "clearance") { FabClearance() }
    }
}

@Composable
private fun SearchPill(query: String, onQuery: (String) -> Unit, placeholder: String) {
    val c = HatchTheme.colors
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink),
        cursorBrush = SolidColor(c.ac),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(c.s1).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(ScreenIcons.Search, contentDescription = null, tint = c.mu, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = c.mu)
                    inner()
                }
            }
        },
    )
}

/** The PSL answer for [origin]'s host, if looked up yet. */
internal fun Map<String, String>.forOrigin(origin: String): String? = LoginFormat.lookupHost(origin)?.let { this[it] }

/** The 36 dp r10 (list) / 48 dp r14 (detail) letter avatar in the host's colour; the letter is the title's. */
@Composable
internal fun LoginAvatar(origin: String, size: Int, registrable: String? = null) {
    val big = size >= 48
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(if (big) 14.dp else 10.dp))
            .background(Color(LoginFormat.avatarColor(origin))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            LoginFormat.avatarLetter(origin, registrable),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = if (big) 20.sp else 15.sp, fontWeight = FontWeight.Bold),
            color = Color.White,
        )
    }
}

@Composable
private fun LoginRow(login: Login, registrable: String?, onClick: () -> Unit) {
    val c = HatchTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .hatchFocusRing(CardRowFocusShape, inside = true)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LoginAvatar(login.origin, 36, registrable)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(LoginFormat.title(login.origin, registrable), style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(login.username.ifEmpty { "(no username)" }, style = MaterialTheme.typography.bodyMedium, color = c.mu, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AddLoginDialog(onDismiss: () -> Unit, onSave: (origin: String, username: String, password: String) -> Unit) {
    var site by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val origin = LoginDisplay.originForNewLogin(site)
    val c = HatchTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.sh,
        title = { Text("Add login") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FormField(
                    "Site", site, { site = it }, keyboardType = KeyboardType.Uri, placeholder = "https://example.com",
                    error = if (site.isNotBlank() && origin == null) "Use a site address such as https://example.com" else null,
                )
                FormField("Username", username, { username = it })
                PasswordField(password) { password = it }
            }
        },
        confirmButton = {
            TextButton(enabled = origin != null && password.isNotEmpty(), onClick = { onSave(origin!!, username.trim(), password) }) { Text("Save", color = c.acT) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.acT) } },
    )
}

@Composable
private fun EditLoginDialog(login: Login, onDismiss: () -> Unit, onSave: (username: String, password: String) -> Unit) {
    var username by remember(login.guid) { mutableStateOf(login.username) }
    var password by remember(login.guid) { mutableStateOf(login.password) }
    val c = HatchTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.sh,
        title = { Text("Edit login") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(LoginDisplay.hostLabel(login.origin), style = MaterialTheme.typography.bodyMedium, color = c.mu)
                FormField("Username", username, { username = it })
                PasswordField(password) { password = it }
            }
        },
        confirmButton = {
            TextButton(enabled = password.isNotEmpty(), onClick = { onSave(username.trim(), password) }) { Text("Save", color = c.acT) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.acT) } },
    )
}

/** A password field with its own show / hide toggle (dialog-local; the list's reveals stay in SavedLoginsLock). */
@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    val c = HatchTheme.colors
    FormField(
        "Password", value, onChange,
        valueStyle = HatchType.monoLarge,
        keyboardType = KeyboardType.Password,
        trailing = {
            IconButton(onClick = { show = !show }, modifier = Modifier.hatchFocusRing(CircleShape)) {
                Icon(if (show) ScreenIcons.EyeOff else ScreenIcons.Eye, contentDescription = if (show) "Hide password" else "Show password", tint = c.mu, modifier = Modifier.size(20.dp))
            }
        },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
    )
}

/** What the public-suffix list answers for the preview hosts. */
internal val PreviewRegistrable = mapOf(
    "secure.chase.com" to "chase.com",
    "console.aws.amazon.com" to "amazon.com",
    "booking.com" to "booking.com",
    "github.com" to "github.com",
    "united.com" to "united.com",
)

/** The Activity hosting this composition (the window that gets `FLAG_SECURE`). */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun copySensitive(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, value)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    clipboard.setPrimaryClip(clip)
}

@Preview(name = "Saved logins", widthDp = 360, heightDp = 760)
@Composable
internal fun SavedLoginsPreview(@PreviewParameter(SurfacePreviewThemes::class) theme: PreviewTheme) {
    val logins = listOf(
        Login("1", "alex.r", "x", "https://secure.chase.com"),
        Login("2", "cory@example.com", "x", "https://console.aws.amazon.com"),
        Login("3", "travel-acct", "x", "https://www.booking.com"),
        Login("4", "corydouthat", "x", "https://github.com"),
        Login("5", "MP-4471", "x", "https://www.united.com"),
    )
    SurfacePreviewFrame(theme) {
        Column {
            ScreenTopBar("Saved logins", onNav = {})
            Box {
                LoginListContent(logins, PreviewRegistrable, "", {}, false, null, {})
                HatchFab("Add login", onClick = {}, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
            }
        }
    }
}
