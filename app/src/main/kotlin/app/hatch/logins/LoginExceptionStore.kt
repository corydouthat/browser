package app.hatch.logins

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import mozilla.components.feature.prompts.login.LoginExceptions

/**
 * Pure (JVM-testable) half of the "Never save for this site" list: origin normalisation and the JSON wire
 * form stored in SharedPreferences. `SaveLoginDialogFragment` (feature-prompts 158, lines 125 and 150) asks
 * [LoginExceptions.isLoginExceptionByOrigin] with the prompt's `origin` before showing the dialog and calls
 * [LoginExceptions.addLoginException] when the user taps "Never save".
 */
object LoginExceptionCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(String.serializer())

    /** Lower-cased scheme+host(+port) without path, query or trailing slash; blank for garbage. */
    fun normalizeOrigin(origin: String): String {
        val trimmed = origin.trim().lowercase()
        if (trimmed.isEmpty()) return ""
        val schemeEnd = trimmed.indexOf("://")
        val (scheme, rest) = if (schemeEnd > 0) trimmed.substring(0, schemeEnd) to trimmed.substring(schemeEnd + 3) else "https" to trimmed
        val hostPort = rest.takeWhile { it != '/' && it != '?' && it != '#' }.removePrefix("www.")
        if (hostPort.isEmpty()) return ""
        return "$scheme://$hostPort"
    }

    fun encode(origins: Set<String>): String = json.encodeToString(serializer, origins.sorted())

    /** Tolerant: an unreadable blob is treated as an empty list rather than crashing the save prompt. */
    fun decode(stored: String?): Set<String> {
        if (stored.isNullOrBlank()) return emptySet()
        return runCatching { json.decodeFromString(serializer, stored) }
            .getOrDefault(emptyList())
            .map(::normalizeOrigin)
            .filter { it.isNotEmpty() }
            .toSet()
    }
}

/** SharedPreferences-backed [LoginExceptions] with an in-memory cache and a flow for the Settings screen. */
class LoginExceptionStore(context: Context) : LoginExceptions {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _origins = MutableStateFlow(LoginExceptionCodec.decode(prefs.getString(KEY_ORIGINS, null)))

    val origins: StateFlow<Set<String>> get() = _origins

    override fun isLoginExceptionByOrigin(origin: String): Boolean {
        val key = LoginExceptionCodec.normalizeOrigin(origin)
        return key.isNotEmpty() && key in _origins.value
    }

    override fun addLoginException(origin: String) {
        val key = LoginExceptionCodec.normalizeOrigin(origin)
        if (key.isEmpty()) return
        write(_origins.value + key)
    }

    fun remove(origin: String) = write(_origins.value - LoginExceptionCodec.normalizeOrigin(origin))

    fun clear() = write(emptySet())

    @Synchronized
    private fun write(next: Set<String>) {
        _origins.value = next
        prefs.edit().putString(KEY_ORIGINS, LoginExceptionCodec.encode(next)).apply()
    }

    companion object {
        const val PREFS_NAME = "hatch_login_exceptions"
        const val KEY_ORIGINS = "origins"
    }
}
