package app.hatch.ui

import java.net.URI

/**
 * Gate for "Install add-on from URL" (SEC-7): only `https://` URLs with a host reach
 * `Engine.installWebExtension`. GeckoView still enforces `xpinstall.signatures.required` and the permission
 * dialog, so this only closes the plain-http / file / data / resource paths the spec rules out.
 */
object AddonUrl {
    const val REJECTED_MESSAGE = "Only https:// add-on URLs are allowed"

    fun isAllowed(raw: String): Boolean {
        val s = raw.trim()
        if (s.isEmpty()) return false
        val uri = runCatching { URI(s) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty()
    }
}
