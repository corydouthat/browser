package app.hatch.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.hatch.Components
import app.hatch.ui.AddonUrl
import app.hatch.ui.theme.HatchTheme
import app.hatch.ui.theme.HatchType
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.WebExtensionAction
import mozilla.components.browser.state.state.extension.WebExtensionPromptRequest
import mozilla.components.concept.engine.webextension.EnableSource
import mozilla.components.concept.engine.webextension.PermissionPromptResponse
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.components.support.webextensions.WebExtensionSupport

/**
 * Settings › Add-ons (`UiEvent.OpenAddons` deep-links here): install a Mozilla-signed add-on from its https `.xpi` URL,
 * enable / disable / uninstall the installed ones. Behaviour is the M1 AddonsSection's, moved here unchanged; messages
 * go to the app-wide snackbar ([snackbar] is HatchApp's state, hosted there). The install / permission prompts are
 * not on this page: [AddonPromptHost] answers them wherever the user is (RS-04).
 */
@Composable
internal fun AddonsSettingsPage(components: Components, snackbar: SnackbarHostState, onBack: () -> Unit) {
    SettingsPageScaffold("Add-ons", onBack) {
        AddonsSection(components, snackbar)
    }
}

@Composable
private fun AddonsSection(components: Components, snackbar: SnackbarHostState) {
    val c = HatchTheme.colors
    val scope = rememberCoroutineScope()
    val store = components.store
    val extensions by store.observeAsComposableState { it.extensions }
    var url by remember { mutableStateOf("") }
    var installing by remember { mutableStateOf(false) }

    PageNote("Install a Mozilla-signed add-on from its .xpi URL (e.g. the uBlock Origin link on addons.mozilla.org).")
    FormField(
        "Install add-on from URL", url, { url = it },
        valueStyle = HatchType.monoMedium, keyboardType = KeyboardType.Uri, placeholder = "https://addons.mozilla.org/…/addon.xpi",
    )
    PrimaryPill(
        if (installing) "Installing…" else "Install",
        enabled = !installing && url.isNotBlank(),
        height = 40.dp,
        horizontalPadding = 18.dp,
        onClick = {
            val u = url.trim()
            if (u.isEmpty()) return@PrimaryPill
            if (!AddonUrl.isAllowed(u)) {
                scope.launch { snackbar.showSnackbar(AddonUrl.REJECTED_MESSAGE) }
                return@PrimaryPill
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
    )

    val installed = extensions.values.filter { !it.isBuiltIn }.sortedBy { it.name ?: it.id }
    GroupTitle("Installed")
    if (installed.isEmpty()) {
        PageNote("No add-ons installed.")
    } else {
        GroupedCard {
            CardRows(installed) { st ->
                val ext: WebExtension? = WebExtensionSupport.installedExtensions[st.id]
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(st.name ?: st.id, style = MaterialTheme.typography.bodyLarge, color = c.ink)
                        Text(st.id + (ext?.getMetadata()?.version?.let { " · v$it" } ?: ""), style = HatchType.mono, color = c.mu)
                    }
                    Switch(
                        checked = st.enabled,
                        enabled = ext != null,
                        colors = hatchSwitchColors(),
                        modifier = Modifier.semantics { contentDescription = "Enable ${st.name ?: st.id}" },
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
                    ) { Text("Uninstall", color = c.dg) }
                }
            }
        }
    }
}

/**
 * The install / optional-permission / post-install / failure prompts that arrive through the store
 * (WebExtensionSupport's delegate), answered wherever the user is (RS-04). M1 composed this inside the Add-ons
 * section, so a prompt raised while another Settings page (or no Settings) was open stayed pending in the store and
 * the install hung until the Add-ons page was next opened. [app.hatch.ui.HatchApp] composes this once, above every
 * overlay; it is the only observer of `webExtensionPromptRequest`.
 */
@Composable
internal fun AddonPromptHost(components: Components, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val store = components.store
    val prompt by store.observeAsComposableState { it.webExtensionPromptRequest }
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
    val c = HatchTheme.colors
    AlertDialog(
        onDismissRequest = { onResult(false) },
        containerColor = c.sh,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("It requests:", style = MaterialTheme.typography.bodyLarge)
                permissions.ifEmpty { listOf("(no special permissions)") }.forEach { Text("• $it", style = HatchType.mono, color = c.mu) }
            }
        },
        confirmButton = { PrimaryPill("Add", onClick = { onResult(true) }, height = 40.dp, horizontalPadding = 18.dp) },
        dismissButton = { TextButton(onClick = { onResult(false) }) { Text("Cancel", color = c.acT) } },
    )
}
