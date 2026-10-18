package app.hatch.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.hatch.BuildConfig
import app.hatch.Components
import app.hatch.applinks.AppLinksMode
import app.hatch.engine.GeckoPrefs
import app.hatch.hostrewrites.HostRewriteFilter
import app.hatch.persona.PersonaToPrefs
import app.hatch.persona.UrlRewriteRules
import app.hatch.rules.RulesUiState
import androidx.compose.foundation.clickable
import androidx.compose.material3.RadioButton
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.WebExtensionAction
import mozilla.components.browser.state.state.extension.WebExtensionPromptRequest
import mozilla.components.concept.engine.webextension.EnableSource
import mozilla.components.concept.engine.webextension.PermissionPromptResponse
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.components.support.webextensions.WebExtensionSupport

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    components: Components,
    onClose: () -> Unit,
    onRestart: () -> Unit,
    snackbar: SnackbarHostState,
) {
    val settings = components.settings
    val scope = rememberCoroutineScope()
    val tick by settings.changes.collectAsState()
    var remoteDebugging by remember(tick) { mutableStateOf(settings.remoteDebugging) }
    var tzShim by remember(tick) { mutableStateOf(settings.tzShim) }
    var keepTouch by remember(tick) { mutableStateOf(settings.keepTouchEvents) }
    var defaultIdentity by remember(tick) { mutableStateOf(settings.defaultIdentityId) }
    var defaultRegion by remember(tick) { mutableStateOf(settings.defaultRegionId) }
    var home by remember(tick) { mutableStateOf(settings.homeUrl) }
    val identities by components.personaManager.identities.collectAsState()
    val regions by components.personaManager.regions.collectAsState()

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                modifier = Modifier.statusBarsPadding(),
            )
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
                    .imePadding(), // UXP-13: the bottom text fields (Home URL, add-on URL) stay above the keyboard
            ) {
                Section("Engine")
                SwitchRow(
                    title = "Remote debugging",
                    subtitle = "USB devtools via desktop Firefox about:debugging. Applied live; restart to be sure.",
                    checked = remoteDebugging,
                ) { on ->
                    remoteDebugging = on
                    settings.remoteDebugging = on
                    components.engine.settings.remoteDebuggingEnabled = on
                    scope.launch {
                        val r = snackbar.showSnackbar("Remote debugging ${if (on) "on" else "off"} (restart recommended)", actionLabel = "Restart")
                        if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) onRestart()
                    }
                }
                SwitchRow(
                    title = "Time-zone JS shim (fallback)",
                    subtitle = "Patch Date/Intl in pages from a precomputed offset table. Only if TZ does not reach content.",
                    checked = tzShim,
                ) { on ->
                    tzShim = on
                    settings.tzShim = on
                    scope.launch { components.personaManager.resendShim(); components.sessionUseCases.reload() }
                }
                SwitchRow(
                    title = "Keep touch events (sites that need gestures)",
                    subtitle = "Leaves dom.w3c_touch_events.enabled on for desktop personas so maps, drawing tools and carousels get real touch input; document.createEvent(\"TouchEvent\") becomes a tell.",
                    checked = keepTouch,
                ) { on ->
                    keepTouch = on
                    settings.keepTouchEvents = on
                    scope.launch { components.personaManager.reapplyPrefs(); components.sessionUseCases.reload() }
                }

                Section("Toolbar")
                var hideToolbar by remember(tick) { mutableStateOf(settings.hideToolbarOnScroll) }
                SwitchRow(
                    title = "Hide toolbar when scrolling",
                    subtitle = "Scroll-to-hide like Chrome/Firefox for Android. Off by default until validated on a device; the page gains the toolbar height while it is hidden.",
                    checked = hideToolbar,
                ) { on -> hideToolbar = on; settings.hideToolbarOnScroll = on }

                Section("App links")
                Text("What happens when an installed app (YouTube, Reddit, Maps…) claims a link.", style = MaterialTheme.typography.bodySmall)
                val appLinksMode = remember(tick) { settings.appLinksMode }
                AppLinksMode.entries.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth().clickable { settings.appLinksMode = mode }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = appLinksMode == mode, onClick = { settings.appLinksMode = mode })
                        Column(Modifier.weight(1f)) {
                            Text(mode.title, style = MaterialTheme.typography.bodyLarge)
                            Text(mode.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Section("Host rewrites (mobile → desktop)")
                Text(
                    "While a desktop persona is active, main-frame requests to these mobile hosts are redirected to the desktop host (10 s per-tab loop guard). Switch one off if a site needs its mobile host.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val disabledHosts = remember(tick) { settings.disabledHostRewrites }
                UrlRewriteRules.extensionHostRewrites.forEach { rule ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            HostRewriteFilter.label(rule),
                            style = if (HostRewriteFilter.isRegex(rule)) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = rule.from !in disabledHosts,
                            onCheckedChange = { on ->
                                settings.disabledHostRewrites = HostRewriteFilter.toggle(UrlRewriteRules.extensionHostRewrites, settings.disabledHostRewrites, rule.from, on)
                                components.bridge.sendHostRewrites(components.effectiveHostRewrites())
                            },
                        )
                    }
                }
                Text(
                    "Switches apply to followed links and to typed or pasted URLs alike. The generic strip is a catch-all: to keep a mobile host that it matches, switch it off too. " +
                        "Typed URLs also get Amazon /gp/aw/ and mf_useformat / app=m query rewrites (address bar only).",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Section("Default persona")
                Text("Used on first start and when the saved selection no longer exists.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Picker(
                        label = "Identity",
                        options = identities.map { it.id to it.name },
                        selected = defaultIdentity,
                        modifier = Modifier.weight(1f),
                    ) { defaultIdentity = it; settings.defaultIdentityId = it }
                    Spacer(Modifier.width(8.dp))
                    Picker(
                        label = "Region",
                        options = regions.map { it.id to it.name },
                        selected = defaultRegion,
                        modifier = Modifier.weight(1f),
                    ) { defaultRegion = it; settings.defaultRegionId = it }
                }
                OutlinedTextField(
                    value = home,
                    onValueChange = { home = it; settings.homeUrl = it },
                    label = { Text("Home URL (new tabs)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedButton(onClick = { components.rulesUi.show(RulesUiState.Screen.PersonaEditor) }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Personas: identities & regions…")
                }

                // hatch:credentials begin — in-app logins store (docs/research/google-signin-autofill.md Path 2)
                Section("Logins & passkeys")
                var loginAutofill by remember(tick) { mutableStateOf(settings.loginAutofill) }
                var saveLogins by remember(tick) { mutableStateOf(settings.saveLogins) }
                SwitchRow(
                    title = "Autofill saved logins",
                    subtitle = "Hatch's own store (not Google Password Manager). One match fills silently; several show a picker above the keyboard.",
                    checked = loginAutofill,
                ) { on ->
                    loginAutofill = on
                    settings.loginAutofill = on
                    components.engine.settings.loginAutofillEnabled = on
                }
                SwitchRow(
                    title = "Offer to save logins",
                    subtitle = "Ask after a sign-in form is submitted. \"Never save\" answers are kept per site.",
                    checked = saveLogins,
                ) { on -> saveLogins = on; settings.saveLogins = on }
                val neverSave by components.logins.exceptions.origins.collectAsState()
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { components.loginsUi.savedLoginsOpen = true }) { Text("Saved logins…") }
                    Spacer(Modifier.width(8.dp))
                    if (neverSave.isNotEmpty()) {
                        TextButton(onClick = { components.logins.exceptions.clear() }) { Text("Forget ${neverSave.size} \"never save\" site(s)") }
                    }
                }
                Text(
                    "Passkeys: wired through Android Credential Manager / Google Play services, but Google only serves " +
                        "allow-listed browsers (request pending for the release signature; debug builds never). " +
                        "Until then use \"Open in another browser\" for passkey logins.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                // hatch:credentials end

                Section("Sites")
                Text("Per-site userscripts / userstyles and the permissions sites were granted.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { components.rulesUi.show(RulesUiState.Screen.SiteRules) }) { Text("Site rules…") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { components.rulesUi.show(RulesUiState.Screen.SitePermissions) }) { Text("Site permissions…") }
                }

                Section("Add-ons")
                AddonsSection(components, snackbar)

                Section("Engine prefs (read back from Gecko)")
                EnginePrefsSection()

                Section("Bridge extension")
                val bridgeStatus by components.bridge.status.collectAsState()
                val logs by components.bridge.logs.collectAsState()
                Text("Status: $bridgeStatus", style = MaterialTheme.typography.bodyMedium)
                logs.takeLast(8).forEach { l ->
                    Text("[${l.level}] ${l.message}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }

                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                val footer = buildString {
                    append("Hatch · v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
                    if (BuildConfig.DEBUG) append(" · ${BuildConfig.GIT_SHA} @ ${BuildConfig.GIT_BRANCH} · ${BuildConfig.BUILD_TIME}")
                }
                Text(footer, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Picker(label: String, options: List<Pair<String, String>>, selected: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: " + (options.firstOrNull { it.first == selected }?.second ?: selected), maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onSelect(id) })
            }
        }
    }
}

@Composable
private fun AddonsSection(components: Components, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val store = components.store
    val extensions by store.observeAsComposableState { it.extensions }
    val prompt by store.observeAsComposableState { it.webExtensionPromptRequest }
    var url by remember { mutableStateOf("") }
    var installing by remember { mutableStateOf(false) }

    Text(
        "Install a Mozilla-signed add-on from its .xpi URL (e.g. the uBlock Origin link on addons.mozilla.org).",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("Install add-on from URL") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    Button(
        onClick = {
            val u = url.trim()
            if (u.isEmpty()) return@Button
            if (!AddonUrl.isAllowed(u)) {
                scope.launch { snackbar.showSnackbar(AddonUrl.REJECTED_MESSAGE) }
                return@Button
            }
            installing = true
            components.engine.installWebExtension(
                url = u,
                onSuccess = { ext ->
                    installing = false
                    scope.launch { snackbar.showSnackbar("Installed ${ext.getMetadata()?.name ?: ext.id}") }
                },
                onError = { e ->
                    installing = false
                    scope.launch { snackbar.showSnackbar("Install failed: ${e.message}") }
                },
            )
        },
        enabled = !installing && url.isNotBlank(),
        modifier = Modifier.padding(top = 6.dp),
    ) { Text(if (installing) "Installing…" else "Install") }

    val installed = extensions.values.filter { !it.isBuiltIn }.sortedBy { it.name ?: it.id }
    if (installed.isEmpty()) {
        Text("No add-ons installed.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
    installed.forEach { st ->
        val ext: WebExtension? = WebExtensionSupport.installedExtensions[st.id]
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(st.name ?: st.id, style = MaterialTheme.typography.bodyLarge)
                Text(st.id + (ext?.getMetadata()?.version?.let { " · v$it" } ?: ""), style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = st.enabled,
                enabled = ext != null,
                onCheckedChange = { on ->
                    ext ?: return@Switch
                    if (on) {
                        components.engine.enableWebExtension(ext, EnableSource.USER, onSuccess = {}, onError = { e ->
                            scope.launch { snackbar.showSnackbar("Enable failed: ${e.message}") }
                        })
                    } else {
                        components.engine.disableWebExtension(ext, EnableSource.USER, onSuccess = {}, onError = { e ->
                            scope.launch { snackbar.showSnackbar("Disable failed: ${e.message}") }
                        })
                    }
                },
            )
            TextButton(
                onClick = {
                    ext ?: return@TextButton
                    components.engine.uninstallWebExtension(ext, onSuccess = {
                        scope.launch { snackbar.showSnackbar("Uninstalled ${st.name ?: st.id}") }
                    }, onError = { _, e ->
                        scope.launch { snackbar.showSnackbar("Uninstall failed: ${e.message}") }
                    })
                },
                enabled = ext != null,
            ) { Text("Uninstall") }
        }
    }

    // Install / optional-permission prompts arrive through the store (WebExtensionSupport's delegate).
    when (val p = prompt) {
        is WebExtensionPromptRequest.AfterInstallation.Permissions.Required -> PermissionDialog(
            title = "Install ${p.extension.getMetadata()?.name ?: p.extension.id}?",
            permissions = p.permissions + p.origins + p.dataCollectionPermissions,
            onResult = { granted ->
                p.onConfirm(PermissionPromptResponse(isPermissionsGranted = granted, isPrivateModeGranted = false))
                store.dispatch(WebExtensionAction.ConsumePromptRequestWebExtensionAction)
            },
        )
        is WebExtensionPromptRequest.AfterInstallation.Permissions.Optional -> PermissionDialog(
            title = "${p.extension.getMetadata()?.name ?: p.extension.id} requests permissions",
            permissions = p.permissions + p.origins + p.dataCollectionPermissions,
            onResult = { granted ->
                p.onConfirm(granted)
                store.dispatch(WebExtensionAction.ConsumePromptRequestWebExtensionAction)
            },
        )
        is WebExtensionPromptRequest.AfterInstallation.PostInstallation -> LaunchedEffect(p) {
            store.dispatch(WebExtensionAction.ConsumePromptRequestWebExtensionAction)
            snackbar.showSnackbar("Add-on ready: ${p.extension.getMetadata()?.name ?: p.extension.id}")
        }
        is WebExtensionPromptRequest.BeforeInstallation.InstallationFailed -> LaunchedEffect(p) {
            store.dispatch(WebExtensionAction.ConsumePromptRequestWebExtensionAction)
            snackbar.showSnackbar("Install failed: ${p.exception.javaClass.simpleName} ${p.exception.message ?: ""}")
        }
        is WebExtensionPromptRequest.InstallationRequested -> LaunchedEffect(p) {
            // A page asked to install (e.g. an AMO "Add to Firefox" button): same install path, same https gate.
            store.dispatch(WebExtensionAction.ConsumePromptRequestWebExtensionAction)
            if (!AddonUrl.isAllowed(p.url)) {
                snackbar.showSnackbar(AddonUrl.REJECTED_MESSAGE)
            } else {
                components.engine.installWebExtension(p.url, onSuccess = {}, onError = { e ->
                    scope.launch { snackbar.showSnackbar("Install failed: ${e.message}") }
                })
            }
        }
        null -> Unit
    }
}

@Composable
private fun PermissionDialog(title: String, permissions: List<String>, onResult: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onResult(false) },
        title = { Text(title) },
        text = {
            Column {
                Text("It requests:")
                permissions.ifEmpty { listOf("(no special permissions)") }.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { Button(onClick = { onResult(true) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = { onResult(false) }) { Text("Cancel") } },
    )
}

@Composable
private fun EnginePrefsSection() {
    var rows by remember { mutableStateOf<List<GeckoPrefs.PrefRow>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        runCatching { GeckoPrefs.read(PersonaToPrefs.allPrefs) }
            .onSuccess { rows = it; error = null }
            .onFailure { error = it.message ?: it.toString() }
    }
    Text("What Gecko currently holds for every persona-owned pref (user value wins over default).", style = MaterialTheme.typography.bodySmall)
    error?.let { Text("read failed: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    rows.forEach { r ->
        Text(
            "${r.name} = ${r.value ?: "<unset>"}${if (r.userSet) "  [user]" else "  [default]"}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
    TextButton(onClick = { refresh++ }) { Text("Refresh") }
}
