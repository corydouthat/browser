package app.hatch.engine

import android.content.Context
import android.util.Log
// hatch:rules begin
import app.hatch.applinks.AppLinksPolicy
import app.hatch.components
// hatch:rules end
import app.hatch.persona.PersonaManager
import kotlinx.coroutines.launch
import mozilla.components.browser.errorpages.ErrorPages
import mozilla.components.concept.engine.request.ErrorType
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.request.RequestInterceptor
import mozilla.components.feature.app.links.AppLinksInterceptor

/**
 * Engine-level request interceptor: app links (intent://, market:, custom schemes) via AC's
 * [AppLinksInterceptor], Hatch's own error page for failed loads ([HatchErrorPage]), and the error page's
 * "Try with persona off" link ([PersonaOffLink]: honoured only with the one-time token of the error page Hatch built
 * for that session; every other `hatch-internal:` request is denied without side effects, so normal pages see
 * nothing new).
 */
class HatchRequestInterceptor(
    private val context: Context,
    // hatch:rules begin — AppSettings.appLinksMode → AppLinksPolicy.launchInApp (AppLinksInterceptor.kt:66 at
    // FIREFOX_158_0_RELEASE: false = never leave the browser for an engine-supported scheme, fallback URL for others).
    // Declared before `store` so `HatchRequestInterceptor(context) { store }` keeps binding the trailing lambda to it.
    private val launchInApp: () -> Boolean = { AppLinksPolicy.of(context.components.settings.appLinksMode).launchInApp },
    // hatch:rules end
    store: () -> BrowserStore,
) : RequestInterceptor {
    private val appLinks: AppLinksInterceptor by lazy {
        AppLinksInterceptor(
            context = context,
            launchInApp = launchInApp, // hatch:rules
            store = store(),
        )
    }

    private val errorTokens = ErrorPageTokens<EngineSession>()

    override fun onLoadRequest(
        engineSession: EngineSession,
        uri: String,
        lastUri: String?,
        hasUserGesture: Boolean,
        isSameDomain: Boolean,
        isRedirect: Boolean,
        isDirectNavigation: Boolean,
        isSubframeRequest: Boolean,
    ): RequestInterceptor.InterceptionResponse? {
        when (val d = PersonaOffLink.decide(uri, hasUserGesture, isSubframeRequest, errorTokens.peek(engineSession))) {
            PersonaOffLink.Decision.Refuse -> return RequestInterceptor.InterceptionResponse.Deny
            is PersonaOffLink.Decision.Honour -> {
                errorTokens.take(engineSession)
                personaOffThenLoad(engineSession, d.uri)
                return RequestInterceptor.InterceptionResponse.Deny
            }
            PersonaOffLink.Decision.NotOurs -> {
                // Any other top-level navigation leaves the error page: its token must not outlive it.
                if (!isSubframeRequest && !uri.startsWith(HatchErrorPage.PAGE_PREFIX)) errorTokens.invalidate(engineSession)
            }
        }
        return appLinks.onLoadRequest(
            engineSession, uri, lastUri, hasUserGesture, isSameDomain, isRedirect, isDirectNavigation, isSubframeRequest,
        )
    }

    override fun onErrorRequest(session: EngineSession, errorType: ErrorType, uri: String?): RequestInterceptor.ErrorResponse {
        val c = context.components
        val token = if (uri != null && PersonaOffLink.isWebUrl(uri)) {
            errorTokens.issue(session, uri)
        } else {
            errorTokens.invalidate(session)
            null
        }
        val page = ErrorPages.createUrlEncodedErrorPage(
            context = context,
            errorType = errorType,
            uri = uri,
            htmlResource = HatchErrorPage.HTML_RESOURCE,
            errorStringsProvider = HatchErrorStringsProvider(),
        )
        val extra = ErrorPageParams.build(
            failedUri = uri,
            token = token,
            kind = HatchErrorPage.copyFor(errorType, uri).kind,
            personaOn = c.personaManager.current.value.desktop,
            palette = c.settings.palette,
            mode = c.settings.themeMode,
        )
        return RequestInterceptor.ErrorResponse(page + extra)
    }

    override fun interceptsAppInitiatedRequests(): Boolean = true

    /** The error page's "Try with persona off": the same path as the toolbar's Phone mode, then retry the URL. */
    private fun personaOffThenLoad(session: EngineSession, uri: String) {
        val c = context.components
        c.applicationScope.launch {
            val pm = c.personaManager
            if (pm.current.value.desktop) {
                val result = pm.apply(pm.personaOff(), reload = false)
                if (result is PersonaManager.ApplyResult.Failed) Log.w(TAG, "persona off failed", result.error)
            }
            session.loadUrl(uri)
        }
    }

    private companion object {
        const val TAG = "HatchRequestInterceptor"
    }
}
