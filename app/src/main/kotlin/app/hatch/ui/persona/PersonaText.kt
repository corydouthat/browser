package app.hatch.ui.persona

import app.hatch.persona.Identity
import app.hatch.persona.Region

/**
 * The one-line machine summaries the persona screens print in mono (pure, JVM-tested). The design's rows read
 * "Win32 · 1920×1080 · 8 cores", "MacIntel · 1512×982 · DPR 2", "Linux x86_64 · 1920×1080": platform, screen, then
 * the device pixel ratio when it is not 1, else the CPU core count when the identity sets one.
 */
object PersonaText {
    fun identitySubtitle(id: Identity): String {
        val parts = ArrayList<String>()
        id.platform?.let { parts += it }
        id.screen?.let { parts += "${it.width}×${it.height}" }
        val dpr = id.screen?.dpr
        when {
            dpr != null && dpr != 1.0 -> parts += "DPR ${formatNumber(dpr)}"
            id.hardwareConcurrency != null -> parts += "${id.hardwareConcurrency} cores"
        }
        if (parts.isEmpty()) return if (id.desktop) "Desktop · engine defaults" else "Real device · engine defaults"
        return parts.joinToString(" · ")
    }

    /** "en-GB, en · Europe/London" (plus "· 51.51, -0.13" when the region answers geolocation). */
    fun regionSubtitle(r: Region): String = buildString {
        append(r.languages.joinToString(", "))
        append(" · ").append(r.timeZone ?: "device time zone")
        r.geolocation?.let { append(" · ").append(formatCoordinate(it.latitude)).append(", ").append(formatCoordinate(it.longitude)) }
    }

    /**
     * The persona sheet's info box when applying [to] changes the time zone (a restart: `TZ` is read once at engine
     * start). Null when nothing needs saying.
     */
    fun timeZoneNotice(restartNeeded: Boolean, to: Region): String? {
        if (!restartNeeded) return null
        return "Time zone changes to ${to.timeZone ?: "the device time zone"}. Open tabs reload once."
    }

    /** 2.0 → "2", 1.25 → "1.25". */
    fun formatNumber(v: Double): String = if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()

    private fun formatCoordinate(v: Double): String = String.format(java.util.Locale.ROOT, "%.2f", v)
}
