package app.hatch.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hatch.persona.Persona
import app.hatch.ui.toolbar.ToolbarState
import kotlinx.coroutines.launch
import mozilla.components.ui.icons.R as iconsR

/** One row of the main menu list. */
data class MenuItem(val label: String, val icon: Int, val onClick: () -> Unit, val enabled: Boolean = true)

/** The ordered menu (pure; `MainMenuSheet` renders it). The last four items are handled by other modules. */
fun mainMenuItems(
    onNewTab: () -> Unit,
    onFindInPage: () -> Unit,
    onOpenInBrowser: () -> Unit,
    openInBrowserEnabled: Boolean,
    onAddons: () -> Unit,
    onSavedLogins: () -> Unit,
    onSiteRules: () -> Unit,
    onSitePermissions: () -> Unit,
    onSelfCheck: () -> Unit,
    onEruda: () -> Unit,
    onPersona: () -> Unit,
    onSettings: () -> Unit,
): List<MenuItem> = listOf(
    MenuItem("New tab", iconsR.drawable.mozac_ic_plus_24, onNewTab),
    MenuItem("Find in page", iconsR.drawable.mozac_ic_find_in_page_24, onFindInPage),
    MenuItem("Open in another browser", iconsR.drawable.mozac_ic_external_link_24, onOpenInBrowser, openInBrowserEnabled),
    MenuItem("Add-ons", iconsR.drawable.mozac_ic_extension_24, onAddons),
    MenuItem("Saved logins", iconsR.drawable.mozac_ic_login_24, onSavedLogins),
    MenuItem("Site rules", iconsR.drawable.mozac_ic_tool_24, onSiteRules),
    MenuItem("Site permissions", iconsR.drawable.mozac_ic_permissions_24, onSitePermissions),
    MenuItem("Self-check", iconsR.drawable.mozac_ic_shield_checkmark_24, onSelfCheck),
    MenuItem("Eruda devtools", iconsR.drawable.mozac_ic_debug_drawer_24, onEruda),
    MenuItem("Persona…", iconsR.drawable.mozac_ic_avatar_circle_24, onPersona),
    MenuItem("Settings", iconsR.drawable.mozac_ic_settings_24, onSettings),
)

/**
 * Main menu: header (persona chip + desktop switch), quick-action row (back, forward, share, reload) and the item
 * list. Every item animates the sheet closed first so the next overlay (settings, tray, dialog) opens cleanly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainMenuSheet(
    persona: Persona,
    canGoBack: Boolean,
    canGoForward: Boolean,
    loading: Boolean,
    items: List<MenuItem>,
    onDismiss: () -> Unit,
    onPersona: () -> Unit,
    onToggleDesktop: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onShare: () -> Unit,
    onReload: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    /**
     * UXP-12: animate the sheet closed, then remove it and run the action (M3 guidance for `ModalBottomSheet`:
     * `scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }`). Flipping the state first would drop
     * the dialog window without the hide animation.
     */
    fun dismissThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = { dismissThen(onPersona) },
                    label = {
                        Text(
                            ToolbarState.personaLabel(persona.identity.name, persona.region.name),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = { Icon(painterResource(iconsR.drawable.mozac_ic_avatar_circle_24), null, Modifier.size(18.dp)) },
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                Text("Desktop", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Switch(checked = persona.desktop, onCheckedChange = { onToggleDesktop() })
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                QuickAction(iconsR.drawable.mozac_ic_back_24, "Back", canGoBack) { dismissThen(onBack) }
                QuickAction(iconsR.drawable.mozac_ic_forward_24, "Forward", canGoForward) { dismissThen(onForward) }
                QuickAction(iconsR.drawable.mozac_ic_share_android_24, "Share", true) { dismissThen(onShare) }
                QuickAction(
                    if (loading) iconsR.drawable.mozac_ic_cross_24 else iconsR.drawable.mozac_ic_arrow_clockwise_24,
                    if (loading) "Stop" else "Reload",
                    true,
                ) { dismissThen(onReload) }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            items.forEach { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = item.enabled) { dismissThen(item.onClick) }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(item.icon),
                        contentDescription = null,
                        tint = if (item.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                    Spacer(Modifier.width(20.dp))
                    Text(
                        item.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (item.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun QuickAction(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(52.dp)) {
        Icon(painterResource(icon), contentDescription = label)
    }
}
