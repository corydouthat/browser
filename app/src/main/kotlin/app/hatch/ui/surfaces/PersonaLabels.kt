package app.hatch.ui.surfaces

import app.hatch.persona.Persona

/**
 * Short persona names for the surfaces that show "identity · region" in one line (home Desktop card, menu persona
 * row, site info "This site sees"). Preset names carry an explanation in parentheses ("Real device (no spoofing)",
 * "United Kingdom (London)", "Windows · Chrome 141 (UA only; …)"); the one-line label drops it. The persona sheet's
 * chips use [short] as well; the persona editor titles its rows with [short] and shows [note] under the mono
 * subtitle (DF-06), so the explanation is still on screen there.
 */
object PersonaLabels {
    /** [name] without a trailing parenthetical, trimmed; the full name when nothing would be left. */
    fun short(name: String): String {
        val trimmed = name.trim()
        if (!trimmed.endsWith(")")) return trimmed
        val open = trimmed.lastIndexOf('(')
        if (open <= 0) return trimmed
        return trimmed.substring(0, open).trim().ifEmpty { trimmed }
    }

    /** The trailing parenthetical of [name] without the parentheses ("UA only; …"), or null when there is none. */
    fun note(name: String): String? {
        val trimmed = name.trim()
        if (short(trimmed) == trimmed) return null
        return trimmed.substring(trimmed.lastIndexOf('(') + 1, trimmed.length - 1).trim().ifEmpty { null }
    }

    fun identity(persona: Persona): String = short(persona.identity.name)

    fun region(persona: Persona): String = short(persona.region.name)

    /** "Windows · Firefox 158 · United States". */
    fun full(persona: Persona): String = "${identity(persona)} · ${region(persona)}"
}

/**
 * The Desktop / Phone pickers (home cards, menu segmented control) select a mode; the app only exposes a toggle
 * (`HatchApp.toggleDesktop`: `forceDesktop()` / `personaOff()`). Tapping the already selected mode is a no-op.
 */
fun shouldToggleDesktop(currentDesktop: Boolean, wantDesktop: Boolean): Boolean = currentDesktop != wantDesktop
