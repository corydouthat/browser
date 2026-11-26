package app.hatch.logins

import android.content.Context
import android.util.Log
import app.hatch.settings.AppSettings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.plus
import kotlinx.coroutines.flow.MutableStateFlow
import mozilla.components.browser.engine.gecko.autofill.GeckoAutocompleteStorageDelegate
import mozilla.components.concept.storage.LoginStorageDelegate
import mozilla.components.concept.storage.LoginValidationDelegate
import mozilla.components.concept.storage.LoginsStorage
import mozilla.components.feature.prompts.login.LoginDelegate
import mozilla.components.lib.dataprotect.SecureAbove22Preferences
import mozilla.components.service.sync.logins.DefaultLoginValidationDelegate
import mozilla.components.service.sync.logins.GeckoLoginStorageDelegate
import mozilla.components.service.sync.logins.LoginsCrypto
import mozilla.components.service.sync.logins.SyncableLoginsStorage
import org.mozilla.geckoview.Autocomplete
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-lifetime owner of the in-app logins store (Path 2 of `docs/research/google-signin-autofill.md`):
 * the same wiring Fenix 158 uses (`Core.kt:537`, `GeckoProvider.kt:75-87`, `BaseBrowserFragment.kt:1048-1101`).
 *
 * - [storage] is a `SyncableLoginsStorage` (app-services `logins` SQLCipher database under `databases/`,
 *   key kept in [securePrefs]). Everything is lazy: nothing touches the KeyStore or the database until the
 *   engine fetches logins for a form, a save prompt fires, or the Saved logins screen opens.
 * - [securePrefs] is `SecureAbove22Preferences` with `forceInsecure = false`: the database key is wrapped by
 *   an AndroidKeyStore key. Fenix ships `forceInsecure = !isDebug` (plain SharedPreferences on release,
 *   `Core.kt:691-697`, fenix issues 8324/19155: KeyStore crashes on some OEM builds). Hatch is a single-user
 *   sideload, so it keeps the KeyStore path; if the key is ever lost (signing-key rotation, OEM KeyStore
 *   bug) `SyncableLoginsStorage` regenerates it and wipes the database (`SyncableLoginsStorage.kt:106-112`),
 *   which is loud but not a crash. Recorded in `docs/limitations.md`.
 * - [geckoStorageDelegate] feeds GeckoView's `Autocomplete.StorageDelegate` (`GeckoRuntime.java:758`):
 *   `onLoginFetch(domain)` on login forms, `onLoginSave` on submit (`Autocomplete.java:1107-1166`).
 *   Credit cards / addresses are [NoCreditCardsAddressesDelegate] (never stored).
 * - Every AC delegate launches on [scope] (IO plus an exception handler). AC's delegates do not catch everything
 *   (`GeckoAutocompleteStorageDelegate.onLoginFetch` and `GeckoLoginStorageDelegate.onLoginUsed` not at all,
 *   `onLoginSave` only `LoginsApiException`, FIREFOX_158_0_RELEASE), and an app-services failure there used to be
 *   an uncaught exception that killed the app (seats.aero, build 28: "NSS has not initialized"). Now it costs
 *   autofill only, and [notice] says so once.
 */
class LoginsStorageProvider(context: Context, private val settings: AppSettings, parentScope: CoroutineScope) {
    private val appContext: Context = context.applicationContext
    private val failureNoticed = AtomicBoolean(false)

    /** Where the AC logins delegates run: IO, under [parentScope]'s SupervisorJob, failures logged and noticed. */
    val scope: CoroutineScope = contained(parentScope + Dispatchers.IO) { e ->
        Log.e(TAG, "logins storage call failed", e)
        if (failureNoticed.compareAndSet(false, true)) notice.value = "Saved logins are unavailable: ${e.message ?: e.javaClass.simpleName}"
    }

    val securePrefs: Lazy<SecureAbove22Preferences> = lazy {
        SecureAbove22Preferences(context = appContext, name = SECURE_PREFS_NAME, forceInsecure = false)
    }

    /**
     * A one-shot message for the app-wide snackbar. SEC-6: [KeyLossDetector.NOTICE] when the database existed but its
     * wrapped key did not (KeyStore loss; the undecryptable file is copied aside before `SyncableLoginsStorage`
     * generates a new key and wipes the live database). Also the first storage failure caught on [scope].
     */
    val notice: MutableStateFlow<String?> = MutableStateFlow(null)

    val storage: Lazy<LoginsStorage> = lazy {
        detectKeyLoss()
        SyncableLoginsStorage(appContext, securePrefs)
    }

    private fun detectKeyLoss() {
        val db = appContext.getDatabasePath(DB_NAME)
        val keyPresent = runCatching { securePrefs.value.getString(LoginsCrypto.LOGINS_KEY) != null }.getOrDefault(false)
        if (!KeyLossDetector.detect(dbExists = db.exists(), keyPresent = keyPresent)) return
        runCatching {
            val aside = File(db.parentFile, KeyLossDetector.asideName(DB_NAME, System.currentTimeMillis()))
            db.copyTo(aside, overwrite = false)
        }
        notice.value = KeyLossDetector.NOTICE
    }

    /** `PromptFeature.loginValidationDelegate`: decides "save" vs "update" for a submitted form. */
    val validationDelegate: LoginValidationDelegate by lazy { DefaultLoginValidationDelegate(storage, scope = scope) }

    /** `PromptFeature.loginExceptionStorage`: origins the user answered "Never save" for. */
    val exceptions: LoginExceptionStore by lazy { LoginExceptionStore(appContext) }

    /** Store-side half of the engine delegate; honours the autofill setting on every fetch. */
    val storageDelegate: LoginStorageDelegate by lazy {
        GeckoLoginStorageDelegate(loginStorage = storage, isLoginAutofillEnabled = { settings.loginAutofill }, scope = scope)
    }

    /** The delegate installed on the `GeckoRuntime` (UI thread) by `GeckoRuntimeFactory.create`. */
    fun geckoStorageDelegate(): Autocomplete.StorageDelegate =
        GeckoAutocompleteStorageDelegate(NoCreditCardsAddressesDelegate, storageDelegate, scope)

    /** `PromptFeature.loginDelegate`: the Compose-hosted picker plus the "Manage logins" callback. */
    fun loginDelegate(picker: LoginPickerState, onManage: () -> Unit): LoginDelegate = object : LoginDelegate {
        override val loginPickerView: LoginPickerState get() = picker
        override val onManageLogins: () -> Unit get() = onManage
    }

    companion object {
        private const val TAG = "LoginsStorage"

        /** [parent] plus a handler: a failing launch reports to [onFailure] instead of crashing the process. */
        internal fun contained(parent: CoroutineScope, onFailure: (Throwable) -> Unit): CoroutineScope =
            parent + CoroutineExceptionHandler { _, e -> onFailure(e) }

        /** SharedPreferences file (suffix `_kp_post_m` added by lib-dataprotect) holding the wrapped DB key. */
        const val SECURE_PREFS_NAME = "hatch_logins_key"

        /** `SyncableLoginsStorage.DB_NAME` at FIREFOX_158_0_RELEASE (`SyncableLoginsStorage.kt:35`). */
        const val DB_NAME = "logins2.sqlite"
    }
}
