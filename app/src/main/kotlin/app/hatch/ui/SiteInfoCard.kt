package app.hatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hatch.persona.Persona
import app.hatch.persona.Presets
import app.hatch.ui.surfaces.PersonaLabels
import app.hatch.ui.surfaces.PreviewTheme
import app.hatch.ui.surfaces.SurfaceIcons
import app.hatch.ui.surfaces.SurfacePreviewFrame
import app.hatch.ui.surfaces.SurfacePreviewThemes
import app.hatch.ui.theme.HatchDimens
import app.hatch.ui.theme.HatchShapes
import app.hatch.ui.theme.HatchTheme
import app.hatch.ui.theme.hatchFocusRing
import app.hatch.ui.toolbar.ToolbarState
import mozilla.components.browser.state.state.SecurityInfo
import mozilla.components.browser.state.state.TabSessionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The site-info card's heading: host and connection state (pure, JVM-tested). */
internal data class SiteInfoSummary(val host: String, val badge: ToolbarState.SecurityBadge, val connection: String)

internal fun siteInfoSummary(url: String, info: SecurityInfo): SiteInfoSummary {
    val badge = ToolbarState.securityBadge(info)
    return SiteInfoSummary(
        host = info.host.ifBlank { OpenHost.of(url) ?: url },
        badge = badge,
        connection = when (badge) {
            ToolbarState.SecurityBadge.Secure -> "Connection is secure"
            ToolbarState.SecurityBadge.Insecure -> "Connection is not secure"
            // about:, file:, resource: pages and loads whose security state is not known yet.
            ToolbarState.SecurityBadge.Unknown -> "No connection details"
        },
    )
}

/**
 * The certificate rows of the site-info card, in the mockup's order: "Issued by" (the CA AC reports, else the
 * certificate's issuer O/CN), "Issued to" (the subject's organisation, else its CN) and "Expires" (UTC date).
 * Only rows with a value are returned.
 */
internal fun siteInfoRows(info: SecurityInfo): List<Pair<String, String>> {
    val cert = info.certificate
    val rows = mutableListOf<Pair<String, String>>()
    val issuer = info.issuer.ifBlank {
        cert?.let { X500Names.attribute(it.issuerX500Principal.name, "O") ?: X500Names.attribute(it.issuerX500Principal.name, "CN") }.orEmpty()
    }
    if (issuer.isNotBlank()) rows += "Issued by" to issuer
    if (cert != null) {
        val subject = cert.subjectX500Principal.name
        (X500Names.attribute(subject, "O") ?: X500Names.attribute(subject, "CN"))?.let { rows += "Issued to" to it }
        rows += "Expires" to expiryDate(cert.notAfter)
    }
    return rows
}

/** `yyyy-MM-dd` in UTC, like the mockup's "2027-03-14". */
internal fun expiryDate(date: Date): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)

internal object OpenHost {
    fun of(url: String): String? {
        val i = url.indexOf("://")
        if (i <= 0) return null
        val rest = url.substring(i + 3)
        val end = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
        return rest.substring(0, end).takeIf { it.isNotEmpty() }
    }
}

/** RFC 2253 distinguished names (`X500Principal.getName()`): one attribute value, unescaped. */
internal object X500Names {
    fun attribute(dn: String, type: String): String? =
        rdns(dn).firstOrNull { it.first.equals(type, ignoreCase = true) }?.second?.takeIf { it.isNotBlank() }

    /** `type=value` pairs in order; multi-valued RDNs (`+`) are flattened. */
    fun rdns(dn: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val part = StringBuilder()
        var quoted = false
        var i = 0
        fun flush() {
            val s = part.toString()
            part.clear()
            val eq = s.indexOf('=')
            if (eq > 0) out += s.substring(0, eq).trim() to unescape(s.substring(eq + 1).trim())
        }
        while (i < dn.length) {
            val ch = dn[i]
            when {
                ch == '\\' && i + 1 < dn.length -> { part.append(ch).append(dn[i + 1]); i += 2; continue }
                ch == '"' -> { quoted = !quoted; part.append(ch) }
                (ch == ',' || ch == '+') && !quoted -> flush()
                else -> part.append(ch)
            }
            i++
        }
        flush()
        return out
    }

    private fun unescape(v: String): String {
        val s = if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) v.substring(1, v.length - 1) else v
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                val hex = if (i + 2 < s.length) s.substring(i + 1, i + 3) else ""
                if (hex.length == 2 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                    bytes.write(hex.toInt(16)); i += 3; continue
                }
                bytes.write(s[i + 1].toString().toByteArray(Charsets.UTF_8)); i += 2; continue
            }
            bytes.write(ch.toString().toByteArray(Charsets.UTF_8)); i++
        }
        return bytes.toString(Charsets.UTF_8.name())
    }
}

/** Site-info geometry from the "Site info" mockup (dp). */
private object SiteInfoDimens {
    val Gap = 8.dp
    val Padding = 20.dp
    val PersonaBoxRadius = 18.dp
    val MaxWidth = 560.dp
    const val SCRIM_ALPHA = 0.45f
}

/**
 * Tap on the toolbar's persona chip ("Site info" mockup): a card anchored [top] + 8 dp under the chrome, inset 8 dp,
 * r28 in the sheet colour, over a 45 % scrim that covers the page only (the chrome stays visible). Host and
 * connection, the tinted "This site sees" box ("Change" opens the Persona sheet), the certificate rows in mono and a
 * "Site permissions" row. A scrim tap or back (`AppUiState.onBack`) dismisses it.
 */
@Composable
fun SiteInfoCard(
    tab: TabSessionState,
    persona: Persona,
    top: Dp,
    onChangePersona: () -> Unit,
    onSitePermissions: () -> Unit,
    onDismiss: () -> Unit,
) {
    SiteInfoLayout(
        summary = siteInfoSummary(tab.content.url, tab.content.securityInfo),
        rows = siteInfoRows(tab.content.securityInfo),
        personaLabel = PersonaLabels.full(persona),
        desktop = persona.desktop,
        top = top,
        onChangePersona = { onDismiss(); onChangePersona() },
        onSitePermissions = { onDismiss(); onSitePermissions() },
        onDismiss = onDismiss,
    )
}

@Composable
internal fun SiteInfoLayout(
    summary: SiteInfoSummary,
    rows: List<Pair<String, String>>,
    personaLabel: String,
    desktop: Boolean,
    top: Dp,
    onChangePersona: () -> Unit,
    onSitePermissions: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = HatchTheme.colors
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .padding(top = top)
                .fillMaxSize()
                .background(Color.Black.copy(alpha = SiteInfoDimens.SCRIM_ALPHA))
                .clickable(interactionSource = null, indication = null, onClickLabel = "Close site info", onClick = onDismiss),
        )
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = top + SiteInfoDimens.Gap, start = SiteInfoDimens.Gap, end = SiteInfoDimens.Gap, bottom = SiteInfoDimens.Gap)
                .widthIn(max = SiteInfoDimens.MaxWidth)
                // A non-clickable M3 Surface consumes pointer input, so taps on the card never reach the scrim.
                .fillMaxWidth()
                // A11Y-03: announced as a pane, and keyboard focus moves into the card when it opens.
                .overlayPane("Site information"),
            shape = HatchShapes.Dialog,
            color = c.sh,
            shadowElevation = 12.dp,
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(SiteInfoDimens.Padding),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        summary.host,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
                        color = c.ink,
                        modifier = Modifier.semantics { heading() },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val secure = summary.badge == ToolbarState.SecurityBadge.Secure
                        val insecure = summary.badge == ToolbarState.SecurityBadge.Insecure
                        Icon(
                            if (secure) SurfaceIcons.Lock else SurfaceIcons.Warning,
                            contentDescription = null,
                            tint = if (insecure) c.dg else c.ink,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            summary.connection,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal),
                            color = if (insecure) c.dg else c.ink,
                        )
                    }
                }
                PersonaBox(personaLabel, desktop, onChangePersona)
                if (rows.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        rows.forEach { (k, v) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(k, style = MaterialTheme.typography.bodyMedium, color = c.mu)
                                Text(
                                    v,
                                    style = HatchTheme.mono,
                                    color = c.ink,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(HatchDimens.Hairline).background(c.ln))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .hatchFocusRing(RoundedCornerShape(12.dp))
                        .heightIn(min = HatchDimens.RowOneLine)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClick = onSitePermissions),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(SurfaceIcons.Shield, contentDescription = null, tint = c.mu, modifier = Modifier.size(20.dp))
                    Text("Site permissions", style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f))
                    Icon(SurfaceIcons.ChevronRight, contentDescription = null, tint = c.mu, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** The tinted "This site sees · <persona> · Change" box (primaryContainer, r18). */
@Composable
private fun PersonaBox(label: String, desktop: Boolean, onChange: () -> Unit) {
    val c = HatchTheme.colors
    val shape = RoundedCornerShape(SiteInfoDimens.PersonaBoxRadius)
    Row(
        Modifier
            .fillMaxWidth()
            .hatchFocusRing(shape)
            .clip(shape)
            .background(c.tb)
            .clickable(role = Role.Button, onClickLabel = "Change persona", onClick = onChange)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(if (desktop) SurfaceIcons.Monitor else SurfaceIcons.Phone, contentDescription = null, tint = c.ti, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text("This site sees", style = MaterialTheme.typography.bodySmall, color = c.ti)
            Text(label, style = MaterialTheme.typography.titleSmall, color = c.ti)
        }
        Text("Change", style = MaterialTheme.typography.labelLarge, color = c.ti)
    }
}

@Preview(widthDp = 360, heightDp = 640)
@Composable
internal fun SiteInfoPreview(@PreviewParameter(SurfacePreviewThemes::class) theme: PreviewTheme) {
    SurfacePreviewFrame(theme) {
        SiteInfoLayout(
            summary = SiteInfoSummary("secure.chase.com", ToolbarState.SecurityBadge.Secure, "Connection is secure"),
            rows = listOf("Issued by" to "DigiCert EV RSA CA G2", "Issued to" to "JPMorgan Chase and Co.", "Expires" to "2027-03-14"),
            personaLabel = PersonaLabels.full(Presets.default()),
            desktop = true,
            top = 88.dp,
            onChangePersona = {},
            onSitePermissions = {},
            onDismiss = {},
        )
    }
}
