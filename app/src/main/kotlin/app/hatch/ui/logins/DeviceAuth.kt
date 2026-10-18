package app.hatch.ui.logins

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Device-credential gate for the Saved logins screen (what Fenix's `SavedLoginsAuthFragment` does with
 * `BiometricPromptFeature`): biometric **or** PIN/pattern/password through androidx.biometric 1.1.0
 * (`BIOMETRIC_WEAK or DEVICE_CREDENTIAL`, the only combination supported on every API level from 26 up),
 * `KeyguardManager.createConfirmDeviceCredentialIntent` when the biometric stack reports the combination
 * unavailable but a lock screen exists, and straight through when the phone has no lock screen at all
 * (nothing to authenticate against; the screen says so).
 */
class DeviceAuth internal constructor(
    private val activity: FragmentActivity?,
    private val launchKeyguard: ((android.content.Intent) -> Unit)?,
) {
    enum class Method { BIOMETRIC_OR_CREDENTIAL, KEYGUARD, NONE }

    fun method(): Method {
        val act = activity ?: return Method.NONE
        val canBiometric = BiometricManager.from(act).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
        if (canBiometric) return Method.BIOMETRIC_OR_CREDENTIAL
        val keyguard = act.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return if (keyguard?.isDeviceSecure == true && launchKeyguard != null) Method.KEYGUARD else Method.NONE
    }

    /** [onResult] runs on the main thread; for [Method.NONE] it runs synchronously with `true`. */
    fun authenticate(title: String, subtitle: String, onResult: (Boolean) -> Unit) {
        val act = activity
        when (method()) {
            Method.BIOMETRIC_OR_CREDENTIAL -> {
                requireNotNull(act)
                val callback = object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
                    // onAuthenticationFailed = one bad attempt; the prompt stays up, so no result yet.
                }
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                    .build()
                BiometricPrompt(act, ContextCompat.getMainExecutor(act), callback).authenticate(info)
            }
            Method.KEYGUARD -> {
                requireNotNull(act)
                val keyguard = act.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                @Suppress("DEPRECATION") // the non-deprecated path is BiometricPrompt, tried first
                val intent = keyguard.createConfirmDeviceCredentialIntent(title, subtitle)
                if (intent == null) onResult(true) else launchKeyguard!!.invoke(intent)
            }
            Method.NONE -> onResult(true)
        }
    }
}

/** Builds a [DeviceAuth] bound to the hosting Activity; the keyguard result is routed through Compose. */
@Composable
fun rememberDeviceAuth(onKeyguardResult: (Boolean) -> Unit): DeviceAuth {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        onKeyguardResult(result.resultCode == Activity.RESULT_OK)
    }
    return remember(context) { DeviceAuth(context.findFragmentActivity()) { intent -> launcher.launch(intent) } }
}

internal fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}
