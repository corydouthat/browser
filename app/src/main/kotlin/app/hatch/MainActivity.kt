package app.hatch

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.hatch.crash.CrashCapture
import app.hatch.crash.CrashRecoveryActivity
import app.hatch.persona.AppRestart
import kotlinx.coroutines.delay
import app.hatch.selfcheck.SelfCheckController
import app.hatch.ui.AppUiState
import app.hatch.ui.HatchApp
import app.hatch.ui.HatchTheme
import app.hatch.ui.UiEvent
import app.hatch.ui.UiEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.concept.engine.EngineView
import mozilla.components.concept.engine.LifecycleObserver
import mozilla.components.feature.app.links.AppLinksFeature
import mozilla.components.feature.contextmenu.ContextMenuCandidate
import mozilla.components.feature.contextmenu.ContextMenuFeature
import mozilla.components.feature.downloads.DownloadsFeature
import mozilla.components.feature.downloads.manager.FetchDownloadManager
import mozilla.components.feature.intent.processing.TabIntentProcessor
import mozilla.components.feature.prompts.PromptFeature
import mozilla.components.feature.session.FullScreenFeature
import mozilla.components.feature.session.PictureInPictureFeature
import mozilla.components.feature.session.SessionFeature
import mozilla.components.feature.sitepermissions.SitePermissionsFeature
import mozilla.components.feature.sitepermissions.SitePermissionsRules
import mozilla.components.feature.tabs.WindowFeature
// hatch:credentials begin
import mozilla.components.feature.webauthn.WebAuthnFeature
// hatch:credentials end
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import app.hatch.downloads.HatchDownloadService
// hatch:rules begin
import app.hatch.applinks.AppLinksPolicy
// hatch:rules end
// hatch:chrome begin
import androidx.core.view.ViewCompat
import app.hatch.ui.keys.KeyShortcuts
import mozilla.components.browser.thumbnails.BrowserThumbnails
import mozilla.components.browser.thumbnails.facts.BrowserThumbnailsFacts
// hatch:chrome end

/**
 * The one Activity. AppCompatActivity (not ComponentActivity) because the Android Components prompt /
 * context-menu / permission features show DialogFragments through a FragmentManager under the Compose UI.
 * One EngineView lives for the whole Activity; Compose only hosts it (see ui/WebContent.kt).
 */
class MainActivity : AppCompatActivity() {
    private lateinit var engineView: EngineView
    private val uiState = AppUiState()
    private val uiEvents = UiEvents()
    private lateinit var selfCheck: SelfCheckController

    private val sessionFeature = ViewBoundFeatureWrapper<SessionFeature>()
    private val windowFeature = ViewBoundFeatureWrapper<WindowFeature>()
    private val promptFeature = ViewBoundFeatureWrapper<PromptFeature>()
    private val contextMenuFeature = ViewBoundFeatureWrapper<ContextMenuFeature>()
    private val downloadsFeature = ViewBoundFeatureWrapper<DownloadsFeature>()
    private val fullScreenFeature = ViewBoundFeatureWrapper<FullScreenFeature>()
    private val sitePermissionsFeature = ViewBoundFeatureWrapper<SitePermissionsFeature>()
    private val appLinksFeature = ViewBoundFeatureWrapper<AppLinksFeature>()
    private var pipFeature: PictureInPictureFeature? = null
    // hatch:chrome begin — captures a thumbnail on load-complete; the tray calls requestScreenshot before opening
    private val thumbnailsFeature = ViewBoundFeatureWrapper<BrowserThumbnails>()
    // hatch:chrome end
    // hatch:credentials begin — passkeys: GeckoView hands the GMS/Credential Manager PendingIntent to this delegate
    private val webAuthnFeature = ViewBoundFeatureWrapper<WebAuthnFeature>()
    // hatch:credentials end

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CrashCapture.safeMode) {
            // The previous start crashed before the browser UI was up: recovery screen, no Gecko, no store.
            startActivity(Intent(this, CrashRecoveryActivity::class.java))
            finish()
            return
        }
        // The marker written by CrashCapture.install is cleared once the browser UI has been up for a few
        // seconds; a crash before that puts the next start in safe mode.
        lifecycleScope.launch {
            delay(STARTUP_SETTLE_MS)
            CrashCapture.startupCompleted(applicationContext)
        }
        enableEdgeToEdge()
        val c = components
        engineView = c.engine.createView(this)
        engineView.setActivityContext(this)
        lifecycle.addObserver(LifecycleObserver(engineView))
        selfCheck = SelfCheckController(applicationContext, c, lifecycleScope)
        // hatch:credentials begin — POST_NOTIFICATIONS (API 33+) is requested at the first download:
        // FetchDownloadManager.download() calls notificationsDelegate.requestNotificationPermission()
        // (FetchDownloadManager.kt:77-79), which only works once the delegate has a bound Activity. Must run
        // before STARTED (registerForActivityResult). m1-inventory risk 4.
        c.notificationsDelegate.bindToActivity(this)
        // hatch:credentials end

        setContent {
            HatchTheme {
                HatchApp(
                    components = c,
                    engineView = engineView,
                    uiState = uiState,
                    uiEvents = uiEvents,
                    selfCheck = selfCheck,
                    onRestart = { AppRestart.restart(this) },
                    requestScreenshot = { // hatch:chrome
                        thumbnailsFeature.get()?.requestScreenshot(BrowserThumbnailsFacts.CaptureAttemptedTriggers.TAB_COUNTER_CLICK)
                    },
                )
            }
        }
        val root: View = findViewById(android.R.id.content)
        installFeatures(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullScreenFeature.onBackPressed()) return
                if (promptFeature.onBackPressed()) return
                // Compose surfaces in z-order (HatchApp draws them in this order, topmost last): rules overlays
                // (site rules / editor / site permissions / persona editor) above the Saved logins screen above the
                // chrome's own overlays (the login picker bar is dismissed by promptFeature above).
                if (c.rulesUi.onBack()) return
                if (c.loginsUi.onBack()) return
                if (uiState.onBack()) return
                if (sessionFeature.onBackPressed()) return
                moveTaskToBack(true)
            }
        })

        // hatch:chrome begin — risk 1: restore + autosave are process-scoped (Components.sessionRestore) and run
        // whenever the browser UI starts, not only when savedInstanceState == null; the launch intent is processed
        // once per Activity instance (a recreated Activity must not re-open its VIEW URL).
        ensureSession(launchIntent = if (savedInstanceState == null) intent else null)
        // hatch:chrome end
    }

    private fun installFeatures(root: View) {
        val c = components
        sessionFeature.set(
            SessionFeature(c.store, c.sessionUseCases.goBack, c.sessionUseCases.goForward, engineView),
            owner = this, view = root,
        )
        windowFeature.set(WindowFeature(c.store, c.tabsUseCases), owner = this, view = root)
        promptFeature.set(
            PromptFeature(
                activity = this,
                store = c.store,
                fragmentManager = supportFragmentManager,
                tabsUseCases = c.tabsUseCases,
                fileUploadsDirCleaner = c.fileUploadsDirCleaner,
                // hatch:credentials begin — in-app logins (Fenix BaseBrowserFragment.kt:1048-1063, 1097-1105;
                // every parameter verified against feature-prompts 158 PromptFeature.kt:250-284)
                loginValidationDelegate = c.logins.validationDelegate,
                isLoginAutofillEnabled = { c.settings.loginAutofill },
                isSaveLoginEnabled = { c.settings.saveLogins },
                loginExceptionStorage = c.logins.exceptions,
                loginDelegate = c.logins.loginDelegate(c.loginsUi.picker) { uiEvents.emit(UiEvent.OpenSavedLogins) },
                // hatch:credentials end
                isSuggestEmailMaskEnabled = { false },
                isEmailMaskFeatureEnabled = { false },
                onNeedToRequestPermissions = { permissions -> askPermissions(permissions, REQUEST_PROMPT) },
            ),
            owner = this, view = root,
        )
        contextMenuFeature.set(
            ContextMenuFeature(
                fragmentManager = supportFragmentManager,
                store = c.store,
                candidates = ContextMenuCandidate.defaultCandidates(
                    context = this,
                    tabsUseCases = c.tabsUseCases,
                    contextMenuUseCases = c.contextMenuUseCases,
                    snackBarParentView = root,
                    downloadsLocation = { c.downloadFileUtils.currentDownloadLocation },
                ),
                engineView = engineView,
                useCases = c.contextMenuUseCases,
            ),
            owner = this, view = root,
        )
        downloadsFeature.set(
            DownloadsFeature(
                applicationContext = applicationContext,
                store = c.store,
                useCases = c.downloadsUseCases,
                onNeedToRequestPermissions = { permissions -> askPermissions(permissions, REQUEST_DOWNLOADS) },
                downloadFileUtils = c.downloadFileUtils,
                downloadManager = FetchDownloadManager(
                    applicationContext,
                    c.store,
                    HatchDownloadService::class,
                    notificationsDelegate = c.notificationsDelegate,
                ),
                fragmentManager = supportFragmentManager,
            ),
            owner = this, view = root,
        )
        fullScreenFeature.set(
            FullScreenFeature(
                store = c.store,
                sessionUseCases = c.sessionUseCases,
                viewportFitChanged = ::viewportFitChanged,
                fullScreenChanged = ::fullScreenChanged,
            ),
            owner = this, view = root,
        )
        sitePermissionsFeature.set(
            SitePermissionsFeature(
                context = this,
                fragmentManager = supportFragmentManager,
                // hatch:rules begin — the storage the Site permissions screen reads/revokes (SitePermissionsFeature.kt:110)
                storage = c.sitePermissionsStorage,
                // hatch:rules end
                sitePermissionsRules = SitePermissionsRules(
                    camera = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    location = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    notification = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    microphone = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    autoplayAudible = SitePermissionsRules.AutoplayAction.BLOCKED,
                    autoplayInaudible = SitePermissionsRules.AutoplayAction.ALLOWED,
                    persistentStorage = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    mediaKeySystemAccess = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    crossOriginStorageAccess = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    localDeviceAccess = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    localNetworkAccess = SitePermissionsRules.Action.ASK_TO_ALLOW,
                ),
                onNeedToRequestPermissions = { permissions -> askPermissions(permissions, REQUEST_SITE_PERMISSIONS) },
                onShouldShowRequestPermissionRationale = { shouldShowRequestPermissionRationale(it) },
                store = c.store,
            ),
            owner = this, view = root,
        )
        appLinksFeature.set(
            AppLinksFeature(
                context = this,
                store = c.store,
                fragmentManager = supportFragmentManager,
                // hatch:rules begin — AppSettings.appLinksMode → AppLinksPolicy (AppLinksFeature.kt:99,105; risk 3)
                launchInApp = { AppLinksPolicy.of(c.settings.appLinksMode).launchInApp },
                shouldPrompt = { AppLinksPolicy.of(c.settings.appLinksMode).shouldPrompt },
                // hatch:rules end
                loadUrlUseCase = c.sessionUseCases.loadUrl,
            ),
            owner = this, view = root,
        )
        pipFeature = PictureInPictureFeature(c.store, this)
        // hatch:chrome begin
        thumbnailsFeature.set(BrowserThumbnails(this, engineView, c.store), owner = this, view = root)
        // hatch:chrome end
        // hatch:credentials begin — WebAuthn (docs/research/passkeys-webauthn.md): registers the engine
        // ActivityDelegate (GeckoEngine.kt:912-926) and launches the PendingIntent GeckoView gets from
        // Credential Manager / GMS FIDO2. Fenix BaseBrowserFragment.kt:1373-1385 (Fenix gates it on
        // MOZILLA_OFFICIAL; Hatch installs it unconditionally so it works the day the allowlist lands).
        webAuthnFeature.set(
            WebAuthnFeature(
                engine = c.engine,
                activity = this,
                exitFullScreen = c.sessionUseCases.exitFullscreen::invoke,
                currentTab = { c.store.state.selectedTabId },
            ),
            owner = this, view = root,
        )
        // hatch:credentials end
    }

    // hatch:chrome begin
    /** Waits for the process-wide restore, then handles the launch intent and guarantees at least one tab. */
    private fun ensureSession(launchIntent: Intent?) {
        val c = components
        lifecycleScope.launch(Dispatchers.Main) {
            c.sessionRestore.join()
            val handledIntent = launchIntent != null && processIntent(launchIntent)
            if (!handledIntent && c.store.state.tabs.isEmpty()) {
                c.tabsUseCases.addTab(c.settings.homeUrl)
            }
        }
    }
    // hatch:chrome end

    private fun processIntent(intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_VIEW && intent.action != Intent.ACTION_SEND) return false
        val c = components
        val processor = TabIntentProcessor(c.tabsUseCases, c.searchUseCases.newTabSearch, engine = c.engine, applicationScope = c.applicationScope)
        return processor.process(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIntent(intent)
    }

    // ---- fullscreen / PiP ------------------------------------------------------------------------

    private fun fullScreenChanged(enabled: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        // hatch:chrome begin — Fenix BaseBrowserFragment.expandBrowserView: the chrome goes away, Gecko gets the
        // whole surface (no dynamic-toolbar reservation, no bottom clipping); the find bar closes with it.
        uiState.fullScreen = enabled
        if (enabled) {
            uiState.findBar = false
            engineView.setDynamicToolbarMaxHeight(0)
            engineView.setVerticalClipping(0)
        }
        // hatch:chrome end
        if (enabled) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun viewportFitChanged(mode: Int) {
        // layoutInDisplayCutoutMode exists from API 28; minSdk is 26 (no cutouts to extend into below P).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = mode }
        }
    }

    override fun onUserLeaveHint() {
        pipFeature?.onHomePressed()
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipFeature?.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }

    // ---- permissions / activity results (AC features) ------------------------------------------------

    private fun askPermissions(permissions: Array<String>, code: Int) {
        ActivityCompat.requestPermissions(this, permissions, code)
    }

    @Deprecated("AC feature contract")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val perms = permissions.map { it }.toTypedArray()
        when (requestCode) {
            REQUEST_PROMPT -> promptFeature.get()?.onPermissionsResult(perms, grantResults)
            REQUEST_DOWNLOADS -> downloadsFeature.get()?.onPermissionsResult(perms, grantResults)
            REQUEST_SITE_PERMISSIONS -> sitePermissionsFeature.get()?.onPermissionsResult(perms, grantResults)
        }
    }

    @Deprecated("AC PromptFeature uses the classic file-picker result path")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (promptFeature.onActivityResult(requestCode, data, resultCode)) return
        // hatch:credentials begin — after PromptFeature, like Fenix BaseBrowserFragment.kt:2117-2123
        if (webAuthnFeature.onActivityResult(requestCode, data, resultCode)) return
        // hatch:credentials end
        super.onActivityResult(requestCode, resultCode, data)
    }

    // ---- hardware keyboard -----------------------------------------------------------------------------

    // hatch:chrome begin — table in ui/keys/KeyShortcuts.kt (JVM-tested). Esc/F5/Ctrl+R/Ctrl+L reach the page
    // unless a Hatch surface is open; "page input focused" = engine view focused + IME visible (best effort).
    // A surface is any of the chrome's (AppUiState.hasOverlay), the rules overlays or the Saved logins screen.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val view = engineView.asView()
            val c = components
            val imeVisible = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            val overlayOpen = uiState.hasOverlay() || c.rulesUi.isOpen || c.loginsUi.savedLoginsOpen
            val ctx = KeyShortcuts.Context(
                overlayOpen = overlayOpen,
                pageInputFocused = view.hasFocus() && imeVisible && !overlayOpen,
            )
            val mapped = KeyShortcuts.map(event.keyCode, event.isCtrlPressed, event.isShiftPressed, event.isAltPressed, ctx)
            if (mapped != null) {
                uiEvents.emit(mapped)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
    // hatch:chrome end

    override fun onDestroy() {
        if (::engineView.isInitialized) { // not in safe mode (CrashCapture)
            engineView.setActivityContext(null)
            // hatch:credentials begin
            components.notificationsDelegate.unBindActivity(this)
            // hatch:credentials end
        }
        super.onDestroy()
    }

    companion object {
        /** How long the browser UI must stay up after onCreate before the start counts as successful (CrashCapture). */
        private const val STARTUP_SETTLE_MS = 5_000L
        private const val REQUEST_PROMPT = 1
        private const val REQUEST_DOWNLOADS = 2
        private const val REQUEST_SITE_PERMISSIONS = 3
    }
}
