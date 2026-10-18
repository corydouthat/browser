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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.hatch.Components
import app.hatch.logins.LoginDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.concept.storage.Login
import mozilla.components.concept.storage.LoginEntry

/**
 * Saved logins: list / search / reveal / copy / delete / add over `components.logins.storage`. The whole
 * screen sits behind [DeviceAuth] (biometric or device credential), like Fenix's `SavedLoginsAuthFragment`;
 * once unlocked, the eye icon reveals a password in place. Copies are marked sensitive on API 33+ so the
 * clipboard preview hides them.
 *
 * SEC-1 (Fenix `SecureFragment` + `SavedLoginsFragment`): while this screen is composed the Activity window carries
 * `FLAG_SECURE` (no screenshots, screen recording or Recents thumbnail of a revealed password; cleared on dispose so
 * the browser itself stays capturable), and `Lifecycle.Event.ON_STOP` re-locks through [SavedLoginsLock]: the gate
 * returns, revealed passwords are hidden and the loaded list is dropped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedLoginsScreen(components: Components, onClose: () -> Unit, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lock = components.loginsUi.lock
    val unlocked = lock.unlocked
    var authDenied by remember { mutableStateOf(false) }
    val auth = rememberDeviceAuth { ok -> if (ok) lock.unlock() else authDenied = true }
    val logins = lock.logins
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val revealed = lock.revealed
    var toDelete by remember { mutableStateOf<Login?>(null) }
    var adding by remember { mutableStateOf(false) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved logins") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                modifier = Modifier.statusBarsPadding(),
            )
        },
        floatingActionButton = {
            if (unlocked) FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Add login") }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).navigationBarsPadding()) {
            when {
                !unlocked -> LockedContent(authDenied, auth.method()) {
                    authDenied = false
                    auth.authenticate("Unlock saved logins", "Confirm it is you to see passwords") { ok ->
                        if (ok) lock.unlock() else authDenied = true
                    }
                }
                else -> Column(Modifier.fillMaxSize()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search site or username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                    if (loading) {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    val shown = LoginDisplay.filter(logins, query)
                    if (!loading && error == null && shown.isEmpty()) {
                        Text(
                            if (logins.isEmpty()) "No saved logins yet. Hatch offers to save a login when you sign in to a site." else "No matches.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { it.guid }) { login ->
                            LoginItem(
                                login = login,
                                revealed = login.guid in revealed,
                                onToggleReveal = { lock.toggleReveal(login.guid) },
                                onCopyUsername = { copySensitive(context, "username", login.username); scope.launch { snackbar.showSnackbar("Username copied") } },
                                onCopyPassword = { copySensitive(context, "password", login.password); scope.launch { snackbar.showSnackbar("Password copied") } },
                                onDelete = { toDelete = login },
                            )
                            HorizontalDivider()
                        }
                        item { Spacer(Modifier.height(88.dp)) }
                    }
                }
            }
        }
    }

    toDelete?.let { login ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete login?") },
            text = { Text("${login.username.ifEmpty { "(no username)" }} at ${LoginDisplay.hostLabel(login.origin)} will be removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    scope.launch {
                        val ok = runCatching { withContext(Dispatchers.IO) { components.logins.storage.value.delete(login.guid) } }.getOrDefault(false)
                        snackbar.showSnackbar(if (ok) "Login deleted" else "Delete failed")
                        reload()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }

    if (adding) {
        AddLoginDialog(
            onDismiss = { adding = false },
            onSave = { origin, username, password ->
                adding = false
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
}

@Composable
private fun LockedContent(denied: Boolean, method: DeviceAuth.Method, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Locked", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            when (method) {
                DeviceAuth.Method.NONE -> "This phone has no screen lock; the list opens without a check."
                else -> if (denied) "Authentication was cancelled or failed." else "Confirm your screen lock or fingerprint to see saved passwords."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text(if (denied) "Try again" else "Unlock") }
    }
}

@Composable
private fun LoginItem(
    login: Login,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    onCopyUsername: () -> Unit,
    onCopyPassword: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(LoginDisplay.hostLabel(login.origin), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(login.username.ifEmpty { "(no username)" }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onCopyUsername, enabled = login.username.isNotEmpty()) { Icon(Icons.Filled.ContentCopy, "Copy username") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (revealed) login.password else "•".repeat(login.password.length.coerceIn(6, 16)),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onToggleReveal) {
                Icon(if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (revealed) "Hide password" else "Show password")
            }
            IconButton(onClick = onCopyPassword) { Icon(Icons.Filled.ContentCopy, "Copy password") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete login") }
        }
    }
}

@Composable
private fun AddLoginDialog(onDismiss: () -> Unit, onSave: (origin: String, username: String, password: String) -> Unit) {
    var site by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val origin = LoginDisplay.originForNewLogin(site)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add login") },
        text = {
            Column {
                OutlinedTextField(
                    value = site,
                    onValueChange = { site = it },
                    label = { Text("Site (https://example.com)") },
                    singleLine = true,
                    isError = site.isNotBlank() && origin == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (showPassword) "Hide" else "Show")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = origin != null && password.isNotEmpty(), onClick = { onSave(origin!!, username.trim(), password) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

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
