package app.hatch.ui.logins

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import app.hatch.logins.LoginDisplay
import app.hatch.logins.LoginPickerState
import app.hatch.ui.surfaces.PreviewTheme
import app.hatch.ui.surfaces.SurfaceIcons
import app.hatch.ui.surfaces.SurfacePreviewFrame
import app.hatch.ui.surfaces.SurfacePreviewThemes
import app.hatch.ui.theme.HatchDimens
import app.hatch.ui.theme.HatchShapes
import app.hatch.ui.theme.HatchTheme
import app.hatch.ui.theme.hatchFocusRing

/** One username chip of the picker (pure facts, so the bar can be previewed without `Login` objects). */
internal data class LoginChip(val username: String, val host: String)

/** What a chip shows and announces: the username ("(no username)" when blank) and "<user> on <host>". */
internal fun loginChipLabel(username: String): String = username.ifEmpty { "(no username)" }

internal fun loginChipDescription(username: String, host: String): String = "Fill ${loginChipLabel(username)} on $host"

/**
 * Compose rendering of [LoginPickerState] ("Browser · Login picker" mockup): the bar GeckoView's `onLoginSelect`
 * prompt turns into when more than one saved login matches the form (a single match is filled silently by the engine
 * when `loginAutofillEnabled` is on). It sits above the keyboard: key glyph, one chip per username (the first one
 * tinted, the others outlined) and "Manage" (Saved logins).
 */
@Composable
fun LoginPickerBar(state: LoginPickerState, modifier: Modifier = Modifier) {
    if (!state.isPromptDisplayed) return
    val logins = state.logins
    LoginPickerLayout(
        chips = logins.map { LoginChip(it.username, LoginDisplay.hostLabel(it.origin)) },
        onSelect = { index -> logins.getOrNull(index)?.let(state::select) },
        onManage = state::manage,
        modifier = modifier.imePadding().navigationBarsPadding(),
    )
}

@Composable
internal fun LoginPickerLayout(
    chips: List<LoginChip>,
    onSelect: (Int) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HatchTheme.colors
    Column(modifier.fillMaxWidth().background(c.s1)) {
        Box(Modifier.fillMaxWidth().height(HatchDimens.Hairline).background(c.ln))
        // The mockup's 8 dp row padding and 8 dp gaps, with 4 dp of each moved inside the scrolling chip row so the
        // chips' focus rings (2 dp, 2 dp outside) are not clipped by the scroll viewport.
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(SurfaceIcons.Key, contentDescription = "Saved logins", tint = c.mu, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                chips.forEachIndexed { i, chip -> UsernameChip(chip, first = i == 0, onClick = { onSelect(i) }) }
            }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier
                    .hatchFocusRing(HatchShapes.Chip)
                    .heightIn(min = 36.dp)
                    .clip(HatchShapes.Chip)
                    .clickable(role = Role.Button, onClickLabel = "Manage saved logins", onClick = onManage)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Manage", style = MaterialTheme.typography.labelLarge, color = c.acT, maxLines = 1)
            }
        }
    }
}

@Composable
private fun UsernameChip(chip: LoginChip, first: Boolean, onClick: () -> Unit) {
    val c = HatchTheme.colors
    val description = loginChipDescription(chip.username, chip.host)
    Box(
        Modifier
            .hatchFocusRing(HatchShapes.Chip)
            .heightIn(min = 36.dp)
            .clip(HatchShapes.Chip)
            .then(if (first) Modifier.background(c.tb) else Modifier.border(HatchDimens.Hairline, c.ln2, HatchShapes.Chip))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = HatchDimens.GapMedium),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            loginChipLabel(chip.username),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (first) FontWeight.Medium else FontWeight.Normal),
            color = if (first) c.ti else c.ink,
            maxLines = 1,
        )
    }
}

@Preview(widthDp = 360)
@Composable
internal fun LoginPickerPreview(@PreviewParameter(SurfacePreviewThemes::class) theme: PreviewTheme) {
    SurfacePreviewFrame(theme) {
        LoginPickerLayout(
            chips = listOf(LoginChip("alex.r", "chase.com"), LoginChip("travel-acct", "chase.com")),
            onSelect = {},
            onManage = {},
        )
    }
}
