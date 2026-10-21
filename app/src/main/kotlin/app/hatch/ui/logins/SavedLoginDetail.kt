package app.hatch.ui.logins

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hatch.ui.settings.CardDivider
import app.hatch.ui.settings.GroupedCard
import app.hatch.ui.settings.OutlinedPill
import app.hatch.ui.settings.ScreenIcons
import app.hatch.ui.settings.ScreenTopBar
import app.hatch.ui.settings.TopBarTextAction
import app.hatch.ui.surfaces.PreviewTheme
import app.hatch.ui.surfaces.SurfacePreviewFrame
import app.hatch.ui.surfaces.SurfacePreviewThemes
import app.hatch.ui.theme.HatchTheme
import app.hatch.ui.theme.HatchType
import app.hatch.ui.theme.hatchFocusRing
import mozilla.components.concept.storage.Login

/**
 * One saved login (mockup "Saved login detail"): 48 dp avatar, site title 22 sp 600 and the origin in mono; a card
 * with Username (copy) and Password (mono when revealed, reveal toggle in a primaryContainer circle, copy); the
 * "Last used … · saved …" line when the store recorded those times; "Delete login" (confirmed by the caller).
 * Rendered inside [SavedLoginsScreen], so FLAG_SECURE and the lock-on-stop apply unchanged.
 */
@Composable
internal fun LoginDetailContent(
    login: Login,
    registrable: Map<String, String>,
    revealed: Boolean,
    now: Long,
    onToggleReveal: () -> Unit,
    onCopyUsername: () -> Unit,
    onCopyPassword: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = HatchTheme.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val r = registrable.forOrigin(login.origin)
            LoginAvatar(login.origin, 48, r)
            Column(Modifier.weight(1f)) {
                Text(
                    LoginFormat.title(login.origin, r),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = c.ink,
                    modifier = Modifier.semantics { heading() },
                )
                Text(login.origin, style = HatchType.mono, color = c.mu)
            }
        }
        GroupedCard {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Username", style = MaterialTheme.typography.bodySmall, color = c.mu)
                    Text(login.username.ifEmpty { "(no username)" }, style = MaterialTheme.typography.bodyLarge, color = c.ink)
                }
                IconButton(onClick = onCopyUsername, enabled = login.username.isNotEmpty(), modifier = Modifier.hatchFocusRing(CircleShape)) {
                    Icon(ScreenIcons.Copy, contentDescription = "Copy username", tint = c.mu, modifier = Modifier.size(20.dp))
                }
            }
            CardDivider()
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Password", style = MaterialTheme.typography.bodySmall, color = c.mu)
                    Text(
                        if (revealed) login.password else MASK.repeat(login.password.length.coerceIn(MIN_MASK, MAX_MASK)),
                        style = HatchType.mono.copy(fontSize = 15.sp, lineHeight = 22.sp),
                        color = c.ink,
                    )
                }
                // The reveal toggle: a 44 dp primaryContainer circle while the password shows (48 dp touch target).
                Box(
                    Modifier
                        .size(48.dp)
                        .hatchFocusRing(CircleShape)
                        .toggleable(value = revealed, role = Role.Switch, onValueChange = { onToggleReveal() })
                        .semantics { contentDescription = if (revealed) "Hide password" else "Show password" },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(if (revealed) c.tb else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(ScreenIcons.Eye, contentDescription = null, tint = if (revealed) c.ti else c.mu, modifier = Modifier.size(20.dp))
                    }
                }
                IconButton(onClick = onCopyPassword, modifier = Modifier.hatchFocusRing(CircleShape)) {
                    Icon(ScreenIcons.Copy, contentDescription = "Copy password", tint = c.mu, modifier = Modifier.size(20.dp))
                }
            }
        }
        LoginFormat.datesLine(login.timeLastUsed, login.timeCreated, now)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = c.mu, modifier = Modifier.padding(horizontal = 8.dp))
        }
        OutlinedPill("Delete login", onClick = onDelete, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

private const val MASK = "•"
private const val MIN_MASK = 6
private const val MAX_MASK = 16

@Preview(name = "Saved login detail · revealed", widthDp = 360, heightDp = 760)
@Composable
internal fun SavedLoginDetailPreview(@PreviewParameter(SurfacePreviewThemes::class) theme: PreviewTheme) {
    val day = 86_400_000L
    val now = 1_792_000_000_000L
    val login = Login(
        guid = "1", username = "alex.r", password = "sample-Passw0rd", origin = "https://secure.chase.com",
        timeCreated = now - 70 * day, timeLastUsed = now - 3 * day,
    )
    SurfacePreviewFrame(theme) {
        Column {
            ScreenTopBar("", onNav = {}, actions = { TopBarTextAction("Edit", onClick = {}) })
            LoginDetailContent(login, PreviewRegistrable, revealed = true, now = now, onToggleReveal = {}, onCopyUsername = {}, onCopyPassword = {}, onDelete = {})
        }
    }
}
