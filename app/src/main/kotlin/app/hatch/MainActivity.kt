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
import app.hatch.persona.AppRestart
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
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import app.hatch.downloads.HatchDownloadService

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val c = components
        engineView = c.engine.createView(this)
        engineView.setActivityContext(this)
        lifecycle.addObserver(LifecycleObserver(engineView))
        selfCheck = SelfCheckController(applicationContext, c, lifecycleScope)

        setContent {
            HatchTheme {
                HatchApp(
                    components = c,
                    engineView = engineView,
                    uiState = uiState,
                    uiEvents = uiEvents,
                    selfCheck = selfCheck,
                    onRestart = { AppRestart.restart(this) },
                )
            }
        }
        val root: View = findViewById(android.R.id.content)
        installFeatures(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullScreenFeature.onBackPressed()) return
                if (promptFeature.onBackPressed()) return
                if (uiState.onBack()) return
                if (sessionFeature.onBackPressed()) return
                moveTaskToBack(true)
            }
        })

        if (savedInstanceState == null) {
            restoreOrOpenFirstTab(intent)
        }
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
                launchInApp = { true },
                loadUrlUseCase = c.sessionUseCases.loadUrl,
            ),
            owner = this, view = root,
        )
        pipFeature = PictureInPictureFeature(c.store, this)
    }

    private fun restoreOrOpenFirstTab(launchIntent: Intent?) {
        val c = components
        lifecycleScope.launch(Dispatchers.IO) {
            c.tabsUseCases.restore(c.sessionStorage)
            launch(Dispatchers.Main) {
                c.sessionStorage.autoSave(c.store)
                    .periodicallyInForeground(interval = 30, unit = java.util.concurrent.TimeUnit.SECONDS)
                    .whenGoingToBackground()
                    .whenSessionsChange()
                val handledIntent = launchIntent != null && processIntent(launchIntent)
                if (!handledIntent && c.store.state.tabs.isEmpty()) {
                    c.tabsUseCases.addTab(c.settings.homeUrl)
                }
            }
        }
    }

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
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = mode }
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
        super.onActivityResult(requestCode, resultCode, data)
    }

    // ---- hardware keyboard -----------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val ctrl = event.isCtrlPressed
            val shift = event.isShiftPressed
            val handled = when {
                ctrl && shift && event.keyCode == KeyEvent.KEYCODE_I -> { uiEvents.emit(UiEvent.ToggleEruda); true }
                ctrl && shift && event.keyCode == KeyEvent.KEYCODE_P -> { uiEvents.emit(UiEvent.PersonaSheet); true }
                ctrl && !shift && event.keyCode == KeyEvent.KEYCODE_L -> { uiEvents.emit(UiEvent.FocusUrl); true }
                ctrl && !shift && event.keyCode == KeyEvent.KEYCODE_R -> { uiEvents.emit(UiEvent.Reload); true }
                event.keyCode == KeyEvent.KEYCODE_F5 -> { uiEvents.emit(UiEvent.Reload); true }
                ctrl && !shift && event.keyCode == KeyEvent.KEYCODE_T -> { uiEvents.emit(UiEvent.NewTab); true }
                ctrl && !shift && event.keyCode == KeyEvent.KEYCODE_W -> { uiEvents.emit(UiEvent.CloseTab); true }
                event.keyCode == KeyEvent.KEYCODE_ESCAPE -> { uiEvents.emit(UiEvent.Escape); true }
                else -> false
            }
            if (handled) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        engineView.setActivityContext(null)
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_PROMPT = 1
        private const val REQUEST_DOWNLOADS = 2
        private const val REQUEST_SITE_PERMISSIONS = 3
    }
}
