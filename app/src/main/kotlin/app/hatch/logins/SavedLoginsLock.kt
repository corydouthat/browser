package app.hatch.logins

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import mozilla.components.concept.storage.Login

/**
 * The gate of the Saved logins screen as plain state (SEC-1; JVM-tested). Fenix's `SavedLoginsAuthFragment` +
 * `SecureFragment` pattern: the list is only readable after a device-credential check, and leaving the foreground
 * (`Lifecycle.Event.ON_STOP`: Home, Recents, screen lock) re-engages the gate, hides every revealed password and
 * drops the loaded list so nothing sensitive survives in composition state.
 */
class SavedLoginsLock {
    var unlocked: Boolean by mutableStateOf(false)
        private set

    var revealed: Set<String> by mutableStateOf(emptySet())
        private set

    var logins: List<Login> by mutableStateOf(emptyList())

    fun unlock() {
        unlocked = true
    }

    fun toggleReveal(guid: String) {
        if (!unlocked) return
        revealed = if (guid in revealed) revealed - guid else revealed + guid
    }

    /** The app left the foreground: lock, hide, forget. */
    fun onStop() {
        unlocked = false
        revealed = emptySet()
        logins = emptyList()
    }
}
