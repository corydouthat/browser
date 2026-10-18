package app.hatch.engine

import android.content.Context
import mozilla.components.browser.errorpages.ErrorPages
import mozilla.components.concept.engine.request.ErrorType
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.request.RequestInterceptor
import mozilla.components.feature.app.links.AppLinksInterceptor

/**
 * Engine-level request interceptor: app links (intent://, market:, custom schemes) via AC's
 * [AppLinksInterceptor] and AC error pages (browser-errorpages) for failed loads.
 */
class HatchRequestInterceptor(
    private val context: Context,
    store: () -> BrowserStore,
) : RequestInterceptor {
    private val appLinks: AppLinksInterceptor by lazy {
        AppLinksInterceptor(
            context = context,
            launchInApp = { true },
            store = store(),
        )
    }

    override fun onLoadRequest(
        engineSession: EngineSession,
        uri: String,
        lastUri: String?,
        hasUserGesture: Boolean,
        isSameDomain: Boolean,
        isRedirect: Boolean,
        isDirectNavigation: Boolean,
        isSubframeRequest: Boolean,
    ): RequestInterceptor.InterceptionResponse? =
        appLinks.onLoadRequest(
            engineSession, uri, lastUri, hasUserGesture, isSameDomain, isRedirect, isDirectNavigation, isSubframeRequest,
        )

    override fun onErrorRequest(session: EngineSession, errorType: ErrorType, uri: String?): RequestInterceptor.ErrorResponse =
        RequestInterceptor.ErrorResponse(ErrorPages.createUrlEncodedErrorPage(context, errorType, uri))

    override fun interceptsAppInitiatedRequests(): Boolean = true
}
