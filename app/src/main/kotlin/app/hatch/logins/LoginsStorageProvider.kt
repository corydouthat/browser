package app.hatch.logins

import android.content.Context
import app.hatch.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
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
 */
class LoginsStorageProvider(context: Context, private val settings: AppSettings) {
    private val appContext: Context = context.applicationContext

    val securePrefs: Lazy<SecureAbove22Preferences> = lazy {
        SecureAbove22Preferences(context = appContext, name = SECURE_PREFS_NAME, forceInsecure = false)
    }

    /**
     * SEC-6: set when the database existed but its wrapped key did not (KeyStore loss); the Saved logins / app UI
     * shows [KeyLossDetector.NOTICE] once. The undecryptable file is copied aside before `SyncableLoginsStorage`
     * generates a new key and wipes the live database.
     */
    val keyLossNotice: MutableStateFlow<String?> = MutableStateFlow(null)

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
        keyLossNotice.value = KeyLossDetector.NOTICE
    }

    /** `PromptFeature.loginValidationDelegate`: decides "save" vs "update" for a submitted form. */
    val validationDelegate: LoginValidationDelegate by lazy { DefaultLoginValidationDelegate(storage) }

    /** `PromptFeature.loginExceptionStorage`: origins the user answered "Never save" for. */
    val exceptions: LoginExceptionStore by lazy { LoginExceptionStore(appContext) }

    /** Store-side half of the engine delegate; honours the autofill setting on every fetch. */
    val storageDelegate: LoginStorageDelegate by lazy {
        GeckoLoginStorageDelegate(loginStorage = storage, isLoginAutofillEnabled = { settings.loginAutofill })
    }

    /** The delegate installed on the `GeckoRuntime` (UI thread) by `GeckoRuntimeFactory.create`. */
    fun geckoStorageDelegate(applicationScope: CoroutineScope): Autocomplete.StorageDelegate =
        GeckoAutocompleteStorageDelegate(NoCreditCardsAddressesDelegate, storageDelegate, applicationScope)

    /** `PromptFeature.loginDelegate`: the Compose-hosted picker plus the "Manage logins" callback. */
    fun loginDelegate(picker: LoginPickerState, onManage: () -> Unit): LoginDelegate = object : LoginDelegate {
        override val loginPickerView: LoginPickerState get() = picker
        override val onManageLogins: () -> Unit get() = onManage
    }

    companion object {
        /** SharedPreferences file (suffix `_kp_post_m` added by lib-dataprotect) holding the wrapped DB key. */
        const val SECURE_PREFS_NAME = "hatch_logins_key"

        /** `SyncableLoginsStorage.DB_NAME` at FIREFOX_158_0_RELEASE (`SyncableLoginsStorage.kt:35`). */
        const val DB_NAME = "logins2.sqlite"
    }
}
