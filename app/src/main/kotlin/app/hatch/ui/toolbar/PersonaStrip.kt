package app.hatch.ui.toolbar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hatch.persona.Persona
import mozilla.components.ui.icons.R as iconsR

/**
 * Compact row under the toolbar (the slot Fenix reserves for its tab strip): the persona chip opens the persona
 * sheet, the desktop/phone chip mirrors the toolbar's force-desktop toggle. Collapses with the toolbar.
 *
 * Heights are minimums, not fixed (UXP-08): the chips keep Material 3's `AssistChipDefaults.Height` and grow with
 * the system font scale, and the row wraps them, so large-font settings scale the labels instead of clipping them.
 * The chrome column measures its real height for the dynamic toolbar (`onSizeChanged` in `BrowserScreen`).
 */
@Composable
fun PersonaStrip(
    persona: Persona,
    onPersona: () -> Unit,
    onToggleDesktop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = STRIP_MIN_HEIGHT).padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            AssistChip(
                onClick = onPersona,
                label = {
                    Text(
                        ToolbarState.personaLabel(persona.identity.name, persona.region.name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                leadingIcon = {
                    Icon(
                        painterResource(iconsR.drawable.mozac_ic_globe_16),
                        contentDescription = null,
                        modifier = Modifier.width(AssistChipDefaults.IconSize),
                    )
                },
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = persona.desktop,
                onClick = onToggleDesktop,
                label = { Text(if (persona.desktop) "Desktop" else "Phone", style = MaterialTheme.typography.labelMedium) },
                leadingIcon = {
                    Icon(
                        painterResource(
                            if (persona.desktop) iconsR.drawable.mozac_ic_device_desktop_24 else iconsR.drawable.mozac_ic_device_mobile_24,
                        ),
                        contentDescription = null,
                        modifier = Modifier.width(AssistChipDefaults.IconSize),
                    )
                },
            )
        }
    }
}

private val STRIP_MIN_HEIGHT = 36.dp
