package app.hatch.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Hardware-keyboard shortcuts and other Activity-level events the Compose UI reacts to.
 *
 * Every event has exactly one handler: the `when` in [HatchApp]'s event loop. Events owned by another module
 * ([OpenSavedLogins] → `Components.loginsUi`; [OpenSiteRules], [OpenSitePermissions], [OpenPersonaEditor] →
 * `Components.rulesUi`) are routed there from that same `when`.
 */
sealed interface UiEvent {
    // ---- toolbar / navigation --------------------------------------------------------------------
    data object FocusUrl : UiEvent
    data object NewTab : UiEvent
    data object CloseTab : UiEvent
    data object Reload : UiEvent
    data object GoBack : UiEvent
    data object GoForward : UiEvent

    /** Ctrl+Tab / Ctrl+Shift+Tab. */
    data object NextTab : UiEvent
    data object PrevTab : UiEvent

    /** Ctrl+1..9: 1-based position in the tab list; 9 selects the last tab (desktop-browser convention). */
    data class SelectTabIndex(val index: Int) : UiEvent

    // ---- overlays ---------------------------------------------------------------------------------
    data object OpenTabs : UiEvent
    data object OpenMenu : UiEvent
    data object FindInPage : UiEvent
    data object ToggleEruda : UiEvent
    data object PersonaSheet : UiEvent
    data object SelfCheck : UiEvent
    data object OpenSettings : UiEvent
    data object OpenAddons : UiEvent

    /** Handled by the credentials module (saved logins / passkeys screen). */
    data object OpenSavedLogins : UiEvent

    /** Handled by the rules module (site rules list + editor). */
    data object OpenSiteRules : UiEvent

    /** Handled by the rules module (per-site permissions view / revoke). */
    data object OpenSitePermissions : UiEvent

    /** Handled by the rules module (identity / region editor). */
    data object OpenPersonaEditor : UiEvent

    /** Esc: consumed only when a Hatch surface is open (find bar, tray, menu, sheet, settings, URL edit mode). */
    data object Escape : UiEvent
}

/**
 * Fire-and-forget event bus from the Activity to the Compose UI. [emit] never suspends (`tryEmit`), the collector
 * in `HatchApp` never suspends either (UXP-15: a handler that waited on a snackbar would stall every later key /
 * toolbar event behind it; the former `UiEvent.Snackbar` did exactly that and had no emitter, so it is gone), and
 * [BUFFER] events can queue while the collector is between frames; beyond that `tryEmit` drops the event.
 */
class UiEvents {
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = BUFFER)
    val events: SharedFlow<UiEvent> get() = _events

    /** @return false when the buffer is full and the event was dropped (only when nothing collects). */
    fun emit(event: UiEvent): Boolean = _events.tryEmit(event)

    companion object {
        const val BUFFER = 16
    }
}
