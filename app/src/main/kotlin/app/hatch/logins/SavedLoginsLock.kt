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
 *
 * The Add / Edit / Delete dialogs live here too (RS-01): a dialog can only be opened while unlocked, [onStop] closes
 * every one of them, and the screen composes them only inside its unlocked branch. The Edit dialog is keyed by guid
 * and re-reads the login (and its password) from [logins] after an unlock, so no password is kept across a lock.
 */
class SavedLoginsLock {
    var unlocked: Boolean by mutableStateOf(false)
        private set

    var revealed: Set<String> by mutableStateOf(emptySet())
        private set

    var logins: List<Login> by mutableStateOf(emptyList())

    /** The guid whose Edit dialog is open, or null. */
    var editingGuid: String? by mutableStateOf(null)
        private set

    /** The guid whose Delete confirmation is open, or null. */
    var deletingGuid: String? by mutableStateOf(null)
        private set

    /** Whether the Add dialog is open. */
    var adding: Boolean by mutableStateOf(false)
        private set

    /** Opens the Edit dialog for [guid]; refused (false) while locked. */
    fun beginEdit(guid: String): Boolean {
        if (!unlocked) return false
        editingGuid = guid
        return true
    }

    /** Opens the Delete confirmation for [guid]; refused (false) while locked. */
    fun beginDelete(guid: String): Boolean {
        if (!unlocked) return false
        deletingGuid = guid
        return true
    }

    /** Opens the Add dialog; refused (false) while locked. */
    fun beginAdd(): Boolean {
        if (!unlocked) return false
        adding = true
        return true
    }

    /** Closes whichever dialog is open. */
    fun closeDialogs() {
        editingGuid = null
        deletingGuid = null
        adding = false
    }

    /** The login the Edit dialog shows: only while unlocked, always the freshly loaded copy. */
    fun editing(): Login? = if (unlocked) editingGuid?.let { g -> logins.firstOrNull { it.guid == g } } else null

    /** The login the Delete confirmation names: only while unlocked. */
    fun deleting(): Login? = if (unlocked) deletingGuid?.let { g -> logins.firstOrNull { it.guid == g } } else null

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
        closeDialogs()
    }
}
