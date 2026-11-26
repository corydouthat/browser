package app.hatch.engine

import android.content.Context
import app.hatch.ui.theme.HatchColors
import app.hatch.ui.theme.HatchPalette
import app.hatch.ui.theme.ThemeMode
import androidx.compose.ui.graphics.toArgb
import mozilla.components.browser.errorpages.DefaultErrorStringsProvider
import mozilla.components.browser.errorpages.ErrorStrings
import mozilla.components.concept.engine.request.ErrorType
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.WeakHashMap

/**
 * Hatch's own error page ("Browser · Page error" mockup): `assets/errorpages/hatch_error.html` + `.css` + `.js`,
 * loaded through AC's `ErrorPages.createUrlEncodedErrorPage(context, errorType, uri, htmlResource, …)`
 * (browser-errorpages 158 `ErrorPages.kt:51-59`; there is no css parameter, the page links its own stylesheet). AC
 * puts `title`, `button`, `description`, `image`, `errorCode` and `isPrivate` (plus `showSSL`/`showHSTS`/badCert*
 * and `showContinueHttp`/continueHttp* when they apply) into the query string; the page's script reads
 * `document.documentURI` like AC's `errorPageScripts.js`. Hatch appends [ErrorPageParams]: the failed URL, the
 * one-time token for "Try with persona off", the icon kind, the theme mode and the palette colours.
 */
object HatchErrorPage {
    /** Path under `assets/` (the page is served as `resource://android/assets/errorpages/hatch_error.html`). */
    const val HTML_RESOURCE = "errorpages/hatch_error.html"
    const val PAGE_PREFIX = "resource://android/assets/errorpages/"

    /** The icon tile's glyph: no connection (circle with a slash), lock (security), warning (everything else). */
    enum class Kind(val key: String) { Network("net"), Security("lock"), Warning("warn") }

    /** What the page shows for one [ErrorType] (plain text: the page sets it with `textContent`). */
    data class Copy(val title: String, val message: String, val code: String, val kind: Kind)

    /**
     * Hatch's strings for [type] at [uri]'s host. [Copy.code] is the Gecko `nsresult` the GeckoView error class
     * stands for (names verified in `xpcom/base/ErrorList.py` at FIREFOX_158_0_RELEASE), or the AC [ErrorType] name
     * where GeckoView reports a category rather than one result.
     */
    fun copyFor(type: ErrorType, uri: String?): Copy {
        val host = hostOf(uri) ?: "The site"
        val unreachable = "Can't reach this site"
        return when (type) {
            ErrorType.ERROR_NET_TIMEOUT ->
                Copy(unreachable, "$host took too long to respond. Your connection looks weak.", "NS_ERROR_NET_TIMEOUT", Kind.Network)
            ErrorType.ERROR_UNKNOWN_HOST ->
                Copy(unreachable, "$host's server address could not be found. Check the address, or your connection.", "NS_ERROR_UNKNOWN_HOST", Kind.Network)
            ErrorType.ERROR_CONNECTION_REFUSED ->
                Copy(unreachable, "$host refused the connection.", "NS_ERROR_CONNECTION_REFUSED", Kind.Network)
            ErrorType.ERROR_NET_RESET ->
                Copy(unreachable, "The connection to $host was reset.", "NS_ERROR_NET_RESET", Kind.Network)
            ErrorType.ERROR_NET_INTERRUPT ->
                Copy(unreachable, "The connection to $host was interrupted while the page was loading.", "NS_ERROR_NET_INTERRUPT", Kind.Network)
            ErrorType.ERROR_OFFLINE ->
                Copy("You're offline", "Hatch can't reach the internet. Check Wi-Fi or mobile data, then try again.", "NS_ERROR_OFFLINE", Kind.Network)
            // AC-only type (no GeckoView error class): shown under its AC name.
            ErrorType.ERROR_NO_INTERNET ->
                Copy("You're offline", "Hatch can't reach the internet. Check Wi-Fi or mobile data, then try again.", type.name, Kind.Network)
            ErrorType.ERROR_PROXY_CONNECTION_REFUSED ->
                Copy(unreachable, "The proxy server refused the connection.", "NS_ERROR_PROXY_CONNECTION_REFUSED", Kind.Network)
            ErrorType.ERROR_UNKNOWN_PROXY_HOST ->
                Copy(unreachable, "The proxy server could not be found.", "NS_ERROR_UNKNOWN_PROXY_HOST", Kind.Network)
            ErrorType.ERROR_LOCAL_NETWORK_ACCESS_DENIED ->
                Copy(unreachable, "Access to $host on your local network was blocked.", "NS_ERROR_LOCAL_NETWORK_ACCESS_DENIED", Kind.Network)
            ErrorType.ERROR_REDIRECT_LOOP ->
                Copy("This page isn't redirecting properly", "$host keeps redirecting in a way that will never finish.", "NS_ERROR_REDIRECT_LOOP", Kind.Warning)
            ErrorType.ERROR_PORT_BLOCKED ->
                Copy("This address is restricted", "The address uses a network port that is normally used for something other than browsing.", "NS_ERROR_PORT_ACCESS_NOT_ALLOWED", Kind.Warning)
            ErrorType.ERROR_UNKNOWN_SOCKET_TYPE ->
                Copy(unreachable, "Hatch doesn't know how to talk to this server.", "NS_ERROR_UNKNOWN_SOCKET_TYPE", Kind.Warning)
            ErrorType.ERROR_MALFORMED_URI ->
                Copy("This address isn't valid", "The address isn't in a format Hatch recognises.", "NS_ERROR_MALFORMED_URI", Kind.Warning)
            ErrorType.ERROR_UNKNOWN_PROTOCOL ->
                Copy("This address isn't understood", "No app on this device can open this kind of address.", "NS_ERROR_UNKNOWN_PROTOCOL", Kind.Warning)
            ErrorType.ERROR_FILE_NOT_FOUND ->
                Copy("File not found", "The file could not be found. It may have been moved, renamed or deleted.", "NS_ERROR_FILE_NOT_FOUND", Kind.Warning)
            ErrorType.ERROR_FILE_ACCESS_DENIED ->
                Copy("Access to the file was denied", "The file can't be read. It may have been removed, moved, or its permissions prevent access.", "NS_ERROR_FILE_ACCESS_DENIED", Kind.Warning)
            ErrorType.ERROR_CORRUPTED_CONTENT ->
                Copy("Corrupted content", "$host sent a response that could not be shown because it is damaged.", "NS_ERROR_CORRUPTED_CONTENT", Kind.Warning)
            ErrorType.ERROR_INVALID_CONTENT_ENCODING ->
                Copy("Content encoding error", "$host uses a form of compression that is invalid or unsupported.", "NS_ERROR_INVALID_CONTENT_ENCODING", Kind.Warning)
            ErrorType.ERROR_UNSAFE_CONTENT_TYPE ->
                Copy("This file type isn't allowed", "$host sent a file of a type that can't be opened safely.", type.name, Kind.Warning)
            ErrorType.ERROR_CONTENT_CRASHED ->
                Copy("This page stopped working", "The page's content process crashed. Try again to reload it.", type.name, Kind.Warning)
            ErrorType.ERROR_SECURITY_SSL ->
                Copy("Secure connection failed", "The connection to $host could not be secured, so the page isn't shown.", type.name, Kind.Security)
            ErrorType.ERROR_SECURITY_BAD_CERT ->
                Copy("Warning: potential security risk", "$host's certificate isn't trusted. Someone could be trying to impersonate the site.", type.name, Kind.Security)
            ErrorType.ERROR_BAD_HSTS_CERT ->
                Copy("Did not connect: potential security issue", "$host requires a secure connection and its certificate isn't trusted.", type.name, Kind.Security)
            ErrorType.ERROR_HTTPS_ONLY ->
                Copy("Secure site not available", "$host doesn't support HTTPS. You can continue to the insecure version.", type.name, Kind.Security)
            ErrorType.ERROR_SAFEBROWSING_MALWARE_URI, ErrorType.ERROR_SAFEBROWSING_UNWANTED_URI,
            ErrorType.ERROR_SAFEBROWSING_HARMFUL_URI, ErrorType.ERROR_SAFEBROWSING_PHISHING_URI, ErrorType.ERROR_HARMFULADDON_URI ->
                Copy("This site was blocked", "$host has been reported as unsafe and was blocked.", type.name, Kind.Security)
            ErrorType.UNKNOWN ->
                Copy("This page couldn't load", "Something went wrong while loading $host.", type.name, Kind.Warning)
        }
    }

    /** The host shown in the copy (without `www.`), null for non-network URLs. */
    internal fun hostOf(uri: String?): String? {
        // RS-03: the shared parser (userinfo, backslash, IPv6), as the toolbar uses.
        return UrlHost.of(uri)?.removePrefix("www.")?.takeIf { it.isNotEmpty() }
    }
}

/** AC's provider with Hatch's copy for the main strings; bad-certificate and HTTPS-only details stay AC's. */
class HatchErrorStringsProvider : DefaultErrorStringsProvider() {
    override fun errorStringsFor(context: Context, errorType: ErrorType, uri: String?): ErrorStrings {
        val copy = HatchErrorPage.copyFor(errorType, uri)
        return ErrorStrings(title = copy.title, message = copy.message, refreshButton = "Try again", imageName = null, errorCode = copy.code)
    }
}

/** The query parameters Hatch adds after AC's (pure, JVM-tested). */
object ErrorPageParams {
    /** Colour tokens the page needs, per mode (`l_*` light, `d_*` dark), as 6-digit hex without `#`. */
    private val TOKENS = listOf("ac", "oa", "bg", "s1", "ink", "mu")

    fun build(
        failedUri: String?,
        token: String?,
        kind: HatchErrorPage.Kind,
        personaOn: Boolean,
        palette: HatchPalette,
        mode: ThemeMode,
    ): String = buildString {
        if (failedUri != null) append("&u=").append(enc(failedUri))
        if (token != null && personaOn && PersonaOffLink.isWebUrl(failedUri)) append("&n=").append(token).append("&po=1")
        append("&kind=").append(kind.key)
        append("&mode=").append(mode.key)
        for ((prefix, dark) in listOf("l" to false, "d" to true)) {
            val c = HatchColors.of(palette, dark)
            val values = mapOf("ac" to c.ac, "oa" to c.oa, "bg" to c.bg, "s1" to c.s1, "ink" to c.ink, "mu" to c.mu)
            for (k in TOKENS) append('&').append(prefix).append('_').append(k).append('=').append(hex(values.getValue(k).toArgb()))
        }
    }

    internal fun hex(argb: Int): String = String.format("%06X", argb and 0xFFFFFF)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

/**
 * "Try with persona off" on the Hatch error page navigates to `hatch-internal://persona-off?u=<failed URL>&n=<token>`.
 * GeckoView routes every top-level navigation through `NavigationDelegate.onLoadRequest` → AC `RequestInterceptor
 * .onLoadRequest` (`GeckoEngineSession.kt:1050-1076`, `maybeInterceptRequest` :1138-1203), which is where
 * [decide] runs.
 *
 * Why a token and not "the request comes from the error page": AC passes `lastUri` = the previous *load request*,
 * which it resets to "" on every user-gesture request (`GeckoEngineSession.kt:1142-1145`), and an error page keeps the
 * failed URL as its location (`errorPageScripts.js` reads the query from `document.documentURI`, and "Try again" is
 * `location.reload()`), so neither `lastUri` nor the tab URL can tell an error page from the failed site. Instead
 * [ErrorPageTokens] issues a fresh 128-bit token for each error page it builds, bound to that engine session and
 * failed URL, and only that page's query string carries it. A web page cannot read another document's URL, so no site
 * can produce a valid link; every `hatch-internal:` request is consumed (denied) whether it is honoured or not.
 */
object PersonaOffLink {
    const val SCHEME = "hatch-internal"
    const val HOST = "persona-off"

    sealed interface Decision {
        /** Not a Hatch-internal URI: the normal interception (app links) continues. */
        data object NotOurs : Decision

        /** A Hatch-internal URI that is not honoured: deny it, do nothing else. */
        data object Refuse : Decision

        /** Switch the persona off, then load [uri] in the same session. */
        data class Honour(val uri: String) : Decision
    }

    /** The token issued for one error page: the session's failed URL and the secret only that page knows. */
    data class Pending(val failedUri: String, val token: String)

    fun isOurs(uri: String): Boolean = uri.length > SCHEME.length && uri.regionMatches(0, "$SCHEME:", 0, SCHEME.length + 1, ignoreCase = true)

    fun isWebUrl(uri: String?): Boolean =
        uri != null && (uri.startsWith("https://", ignoreCase = true) || uri.startsWith("http://", ignoreCase = true))

    /**
     * Pure decision for one load request. Honoured only when all hold: the request is a top-level, user-gesture
     * navigation to `hatch-internal://persona-off`; the session has a [pending] token from an error page Hatch
     * built; the link's token equals it (constant-time) and its `u` equals the failed URL that page was built for;
     * and that URL is http(s).
     */
    fun decide(uri: String, hasUserGesture: Boolean, isSubframeRequest: Boolean, pending: Pending?): Decision {
        if (!isOurs(uri)) return Decision.NotOurs
        if (!hasUserGesture || isSubframeRequest || pending == null) return Decision.Refuse
        val query = parse(uri) ?: return Decision.Refuse
        if (query.host != HOST) return Decision.Refuse
        val target = query.params["u"] ?: return Decision.Refuse
        val token = query.params["n"] ?: return Decision.Refuse
        if (!constantTimeEquals(token, pending.token)) return Decision.Refuse
        if (target != pending.failedUri || !isWebUrl(target)) return Decision.Refuse
        return Decision.Honour(target)
    }

    private data class Parsed(val host: String, val params: Map<String, String>)

    /** `hatch-internal://<host>?k=v&…` with percent-decoding; duplicate keys make the link invalid. */
    private fun parse(uri: String): Parsed? {
        val rest = uri.substring(SCHEME.length + 1).removePrefix("//")
        val host = rest.substringBefore('?').substringBefore('#').lowercase()
        val query = rest.substringAfter('?', "").substringBefore('#')
        val params = mutableMapOf<String, String>()
        if (query.isNotEmpty()) {
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val k = decode(pair.substringBefore('=')) ?: return null
                val v = decode(pair.substringAfter('=', "")) ?: return null
                if (params.put(k, v) != null) return null
            }
        }
        return Parsed(host, params)
    }

    private fun decode(s: String): String? = try {
        java.net.URLDecoder.decode(s, "UTF-8")
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}

/**
 * The one-time tokens of the error pages Hatch has built, one per engine session (weakly held, so a closed tab's
 * entry goes with its session). [issue] replaces the session's previous token; [take] consumes it; [invalidate]
 * drops it when the session navigates anywhere else.
 */
class ErrorPageTokens<S : Any>(private val random: SecureRandom = SecureRandom()) {
    private val pending = WeakHashMap<S, PersonaOffLink.Pending>()

    @Synchronized
    fun issue(session: S, failedUri: String): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        pending[session] = PersonaOffLink.Pending(failedUri, token)
        return token
    }

    @Synchronized
    fun peek(session: S): PersonaOffLink.Pending? = pending[session]

    @Synchronized
    fun take(session: S): PersonaOffLink.Pending? = pending.remove(session)

    @Synchronized
    fun invalidate(session: S) {
        pending.remove(session)
    }

    companion object {
        const val TOKEN_BYTES = 16
    }
}
