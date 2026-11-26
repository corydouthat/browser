package app.hatch.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import app.hatch.persona.Persona
import app.hatch.persona.Presets
import app.hatch.ui.surfaces.PersonaLabels
import app.hatch.ui.surfaces.PreviewTheme
import app.hatch.ui.surfaces.SurfaceIcons
import app.hatch.ui.surfaces.SurfacePreviewFrame
import app.hatch.ui.surfaces.SurfacePreviewThemes
import app.hatch.ui.surfaces.shouldToggleDesktop
import app.hatch.ui.theme.HatchDimens
import app.hatch.ui.theme.HatchShapes
import app.hatch.ui.theme.HatchTheme
import app.hatch.ui.theme.hatchFocusRing
import kotlinx.coroutines.launch

/** The glyph of a menu row (mapped to a vector in the composable, so the list stays a pure, JVM-testable model). */
enum class MenuIcon { NewTab, Find, External, SiteRules, SitePermissions, SavedLogins, Addons, Settings, Eruda }

/** One row of the main menu list; [dividerBefore] draws the 1 dp `ln` rule that starts a group. */
data class MenuItem(
    val label: String,
    val icon: MenuIcon,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val dividerBefore: Boolean = false,
)

/**
 * The ordered menu ("Main menu" mockup, pure; `MainMenuSheet` renders it): New tab, Find in page, Open in another
 * browser | Site rules, Site permissions, Saved logins | Add-ons, Settings. "Eruda devtools" is appended only when
 * Settings › Developer › "Eruda in menu" ([erudaInMenu]) is on; Ctrl+Shift+I toggles Eruda either way. The persona
 * editor and Self-check are reached from Settings (Ctrl+Shift+P still opens the persona sheet).
 */
fun mainMenuItems(
    onNewTab: () -> Unit,
    onFindInPage: () -> Unit,
    onOpenInBrowser: () -> Unit,
    openInBrowserEnabled: Boolean,
    onSiteRules: () -> Unit,
    onSitePermissions: () -> Unit,
    onSavedLogins: () -> Unit,
    onAddons: () -> Unit,
    onSettings: () -> Unit,
    erudaInMenu: Boolean,
    onEruda: () -> Unit,
): List<MenuItem> = buildList {
    add(MenuItem("New tab", MenuIcon.NewTab, onNewTab))
    add(MenuItem("Find in page", MenuIcon.Find, onFindInPage))
    add(MenuItem("Open in another browser", MenuIcon.External, onOpenInBrowser, enabled = openInBrowserEnabled))
    add(MenuItem("Site rules", MenuIcon.SiteRules, onSiteRules, dividerBefore = true))
    add(MenuItem("Site permissions", MenuIcon.SitePermissions, onSitePermissions))
    add(MenuItem("Saved logins", MenuIcon.SavedLogins, onSavedLogins))
    add(MenuItem("Add-ons", MenuIcon.Addons, onAddons, dividerBefore = true))
    add(MenuItem("Settings", MenuIcon.Settings, onSettings))
    if (erudaInMenu) add(MenuItem("Eruda devtools", MenuIcon.Eruda, onEruda))
}

/** One of the four circular quick actions under the persona row. */
data class QuickAction(val label: String, val enabled: Boolean)

/** Back, Forward, Share, and Reload — Stop while the page loads (pure, JVM-tested). */
fun quickActions(canGoBack: Boolean, canGoForward: Boolean, loading: Boolean): List<QuickAction> = listOf(
    QuickAction("Back", canGoBack),
    QuickAction("Forward", canGoForward),
    QuickAction("Share", true),
    QuickAction(if (loading) "Stop" else "Reload", true),
)

private fun MenuIcon.vector(): ImageVector = when (this) {
    MenuIcon.NewTab -> SurfaceIcons.MenuPlus
    MenuIcon.Find -> SurfaceIcons.MenuFind
    MenuIcon.External -> SurfaceIcons.MenuExternal
    MenuIcon.SiteRules -> SurfaceIcons.MenuCode
    MenuIcon.SitePermissions -> SurfaceIcons.MenuShield
    MenuIcon.SavedLogins -> SurfaceIcons.MenuKey
    MenuIcon.Addons -> SurfaceIcons.MenuPuzzle
    MenuIcon.Settings -> SurfaceIcons.MenuGear
    MenuIcon.Eruda -> SurfaceIcons.MenuTerminal
}

private fun quickActionIcon(label: String): ImageVector = when (label) {
    "Back" -> SurfaceIcons.Back
    "Forward" -> SurfaceIcons.Forward
    "Share" -> SurfaceIcons.Share
    "Stop" -> SurfaceIcons.Stop
    else -> SurfaceIcons.Reload
}

/** Disabled quick actions and rows: 50 % opacity (the mockup's disabled Forward). */
private const val DISABLED_ALPHA = 0.5f

/**
 * Main menu sheet ("Main menu" mockup): r28 sheet in the sheet colour with a 32×4 handle; the Desktop / Phone
 * segmented control; the "<identity> · <region>" row with "Change" (Persona sheet); the four quick actions; and the
 * grouped item list. Every action animates the sheet closed first so the next overlay (settings, tray, dialog)
 * opens cleanly.
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

    val c = HatchTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = HatchShapes.Sheet,
        containerColor = c.sh,
        contentColor = c.ink,
        scrimColor = Color.Black.copy(alpha = HatchDimens.SHEET_SCRIM_ALPHA),
        dragHandle = { SheetHandle() },
    ) {
        MainMenuContent(
            persona = persona,
            canGoBack = canGoBack,
            canGoForward = canGoForward,
            loading = loading,
            items = items,
            // The persona flips in place (the toolbar tint crossfades behind the sheet); the sheet stays open.
            onSelectMode = { wantDesktop -> if (shouldToggleDesktop(persona.desktop, wantDesktop)) onToggleDesktop() },
            onPersona = { dismissThen(onPersona) },
            onQuickAction = { label ->
                when (label) {
                    "Back" -> dismissThen(onBack)
                    "Forward" -> dismissThen(onForward)
                    "Share" -> dismissThen(onShare)
                    else -> dismissThen(onReload)
                }
            },
            onItem = { item -> dismissThen(item.onClick) },
        )
    }
}

/** The mockup's 32×4 `ln2` handle, 10 dp from the top and 14 dp above the segmented control. */
@Composable
private fun SheetHandle() {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(HatchTheme.colors.ln2))
    }
}

/** The sheet's content (previewable without the modal window). */
@Composable
internal fun MainMenuContent(
    persona: Persona,
    canGoBack: Boolean,
    canGoForward: Boolean,
    loading: Boolean,
    items: List<MenuItem>,
    onSelectMode: (desktop: Boolean) -> Unit,
    onPersona: () -> Unit,
    onQuickAction: (String) -> Unit,
    onItem: (MenuItem) -> Unit,
) {
    val c = HatchTheme.colors
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 28.dp)) {
        ModeSegments(desktop = persona.desktop, onSelect = onSelectMode)
        Row(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 4.dp)
                .fillMaxWidth()
                .hatchFocusRing(RoundedCornerShape(12.dp))
                .heightIn(min = HatchDimens.RowOneLine)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = "Change persona", onClick = onPersona)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HatchDimens.GapSmall),
        ) {
            Text(
                buildAnnotatedString {
                    append(PersonaLabels.identity(persona))
                    withStyle(SpanStyle(color = c.mu)) { append(" · " + PersonaLabels.region(persona)) }
                },
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal),
                color = c.ink,
                modifier = Modifier.weight(1f),
            )
            Text("Change", style = MaterialTheme.typography.labelLarge, color = c.acT)
        }
        Box(Modifier.fillMaxWidth().height(HatchDimens.Hairline).background(c.ln))
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)) {
            quickActions(canGoBack, canGoForward, loading).forEach { action ->
                QuickActionButton(action, onClick = { onQuickAction(action.label) }, modifier = Modifier.weight(1f))
            }
        }
        Column(Modifier.padding(top = 6.dp)) {
            items.forEach { item -> MenuRow(item, onClick = { onItem(item) }) }
        }
    }
}

/**
 * Desktop / Phone segmented control: r16 `s1` track with 4 dp padding and a 4 dp gap; the selected segment is a
 * 44 dp r12 `ac` pill with `oa` content (14 sp 600), the other `mu` (14 sp 500). The 44 dp pills get Compose's
 * 48 dp minimum touch target expansion.
 */
@Composable
private fun ModeSegments(desktop: Boolean, onSelect: (Boolean) -> Unit) {
    val c = HatchTheme.colors
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.s1)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Segment("Desktop", SurfaceIcons.MonitorSmall, selected = desktop, onClick = { onSelect(true) }, modifier = Modifier.weight(1f))
        Segment("Phone", SurfaceIcons.PhoneSmall, selected = !desktop, onClick = { onSelect(false) }, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Segment(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = HatchTheme.colors
    val pill = RoundedCornerShape(12.dp)
    val fg = if (selected) c.oa else c.mu
    Row(
        modifier
            .hatchFocusRing(pill)
            .heightIn(min = 44.dp)
            .clip(pill)
            .then(if (selected) Modifier.background(c.ac) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HatchDimens.GapSmall, Alignment.CenterHorizontally),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = fg,
        )
    }
}

/** 48 dp `s1` circle with a 20 dp glyph and a 12 sp label 6 dp below; disabled at 50 %. */
@Composable
private fun QuickActionButton(action: QuickAction, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = HatchTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .alpha(if (action.enabled) 1f else DISABLED_ALPHA)
            .hatchFocusRing(shape)
            .clip(shape)
            .clickable(enabled = action.enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fg = if (action.enabled) c.ink else c.mu
        Box(
            Modifier.size(HatchDimens.MinTouch).clip(CircleShape).background(c.s1),
            contentAlignment = Alignment.Center,
        ) {
            Icon(quickActionIcon(action.label), contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        }
        // A11Y-11: at large font scales a label wider than its column ellipsizes instead of breaking mid-word; the
        // merged node still reads the full label.
        Text(action.label, style = MaterialTheme.typography.bodySmall, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 48 dp row, 24 dp inset, 20 dp `mu` glyph, 16 dp gap, 15 sp label; a group starts with a 1 dp `ln` rule. */
@Composable
private fun MenuRow(item: MenuItem, onClick: () -> Unit) {
    val c = HatchTheme.colors
    if (item.dividerBefore) Box(Modifier.fillMaxWidth().height(HatchDimens.Hairline).background(c.ln))
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (item.enabled) 1f else DISABLED_ALPHA)
            .heightIn(min = HatchDimens.RowOneLine)
            .hatchFocusRing(RoundedCornerShape(12.dp), inside = true)
            .clickable(enabled = item.enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(item.icon.vector(), contentDescription = null, tint = c.mu, modifier = Modifier.size(20.dp))
        Box(Modifier.width(16.dp))
        Text(item.label, style = MaterialTheme.typography.bodyLarge, color = c.ink)
    }
}

@Preview(widthDp = 360)
@Composable
internal fun MainMenuPreview(@PreviewParameter(SurfacePreviewThemes::class) theme: PreviewTheme) {
    val none = {}
    SurfacePreviewFrame(theme) {
        Column(Modifier.background(HatchTheme.colors.sh)) {
            SheetHandle()
            MainMenuContent(
                persona = Presets.default(),
                canGoBack = true,
                canGoForward = false,
                loading = false,
                items = mainMenuItems(none, none, none, true, none, none, none, none, none, erudaInMenu = false, onEruda = none),
                onSelectMode = {},
                onPersona = {},
                onQuickAction = {},
                onItem = {},
            )
        }
    }
}
