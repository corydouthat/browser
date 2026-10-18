package app.hatch.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Hardware-keyboard shortcuts and other Activity-level events the Compose UI reacts to. */
sealed interface UiEvent {
    data object FocusUrl : UiEvent
    data object NewTab : UiEvent
    data object CloseTab : UiEvent
    data object Reload : UiEvent
    data object ToggleEruda : UiEvent
    data object PersonaSheet : UiEvent
    data object Escape : UiEvent
    data class Snackbar(val message: String, val action: String? = null, val onAction: (() -> Unit)? = null) : UiEvent
}

class UiEvents {
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UiEvent> get() = _events
    fun emit(event: UiEvent) { _events.tryEmit(event) }
}
