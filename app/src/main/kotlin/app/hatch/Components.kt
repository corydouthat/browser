package app.hatch

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import app.hatch.downloads.HatchDownloadService
import app.hatch.engine.GeckoRuntimeFactory
import app.hatch.engine.PageColorScheme
import app.hatch.engine.HatchRequestInterceptor
import app.hatch.ext.BridgeExtension
// hatch:credentials begin
import app.hatch.logins.LoginsStorageProvider
import app.hatch.logins.LoginsUiState
// hatch:credentials end
import app.hatch.persona.PersonaManager
import app.hatch.persona.UrlRewriteRules
import app.hatch.rules.SiteRulesRepository
// hatch:rules begin
import app.hatch.hostrewrites.HostRewriteFilter
import app.hatch.persona.HostRewrite
import app.hatch.rules.RulesUiState
import mozilla.components.browser.engine.gecko.permission.GeckoSitePermissionsStorage
import mozilla.components.concept.engine.permission.SitePermissionsStorage
import mozilla.components.feature.sitepermissions.OnDiskSitePermissionsStorage
// hatch:rules end
import app.hatch.settings.AppSettings
// hatch:fixer begin
import app.hatch.engine.BrowserStateFactory
import app.hatch.ui.tabs.TabClosePolicy
import app.hatch.ui.tabs.ThumbnailGeneration
// hatch:fixer end
// hatch:chrome begin
import app.hatch.ui.toolbar.OpenTabsAutocompleteProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.domains.autocomplete.ShippedDomainsProvider
import mozilla.components.browser.icons.BrowserIcons
import mozilla.components.browser.thumbnails.ThumbnailsMiddleware
import mozilla.components.browser.thumbnails.storage.ThumbnailStorage
import mozilla.components.feature.session.middleware.undo.UndoMiddleware
import java.util.concurrent.TimeUnit
// hatch:chrome end
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.engine.gecko.fetch.GeckoViewFetchClient
import mozilla.components.browser.session.storage.SessionStorage
import mozilla.components.browser.state.engine.EngineMiddleware
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.fetch.Client
import mozilla.components.feature.contextmenu.ContextMenuUseCases
import mozilla.components.feature.downloads.DefaultFileSizeFormatter
import mozilla.components.feature.downloads.DownloadEstimator
import mozilla.components.feature.downloads.DownloadMiddleware
import mozilla.components.feature.downloads.DownloadsUseCases
import mozilla.components.feature.downloads.FileSizeFormatter
import mozilla.components.feature.prompts.PromptMiddleware
import mozilla.components.feature.prompts.file.FileUploadsDirCleaner
import mozilla.components.feature.search.SearchUseCases
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.lib.publicsuffixlist.PublicSuffixList
import mozilla.components.support.base.android.NotificationsDelegate
import mozilla.components.support.utils.DefaultDateTimeProvider
import mozilla.components.support.utils.DefaultDownloadFileUtils
import mozilla.components.support.utils.DownloadFileUtils
import mozilla.components.support.webextensions.WebExtensionSupport
import org.mozilla.geckoview.GeckoRuntime

/** Process-wide lazy singletons. Order of first access: settings -> personaManager -> runtime -> engine -> store. */
class Components(private val context: Context) {
    val applicationScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** The application context for code that needs resources / system services (toolbar strings, clipboard). */
    val appContext: Context get() = context

    val settings: AppSettings by lazy { AppSettings(context) }

    val personaManager: PersonaManager by lazy { PersonaManager(context, settings) { this } }

    /** Engine defaults; `userAgentString` is also what `engine.settings.userAgentString` writes to. */
    val engineSettings: DefaultSettings by lazy {
        val persona = personaManager.current.value
        DefaultSettings(
            requestInterceptor = HatchRequestInterceptor(context) { store },
            userAgentString = persona.identity.userAgent,
            remoteDebuggingEnabled = settings.remoteDebugging,
            supportMultipleWindows = true,
            automaticFontSizeAdjustment = false,
            fontInflationEnabled = false,
            forceUserScalableContent = true,
            getDesktopMode = { personaManager.current.value.desktop },
            loginAutofillEnabled = settings.loginAutofill, // hatch:credentials — GeckoEngine copies it to runtime.settings (GeckoEngine.kt:2016)
        )
    }

    // hatch:credentials begin — in-app logins store + Compose picker/overlay state (docs/research/google-signin-autofill.md Path 2)
    val logins: LoginsStorageProvider by lazy { LoginsStorageProvider(context, settings) }
    val loginsUi: LoginsUiState by lazy { LoginsUiState() }
    // hatch:credentials end

    // hatch:fixer begin — DF-11: registrable domains for the Saved logins titles (AC's PSL, loaded from
    // assets/publicsuffixes on first use, off the main thread).
    val publicSuffixList: PublicSuffixList by lazy { PublicSuffixList(context) }
    // hatch:fixer end

    val runtime: GeckoRuntime by lazy {
        GeckoRuntimeFactory.create(context, personaManager.current.value, settings, logins.geckoStorageDelegate(applicationScope)) // hatch:credentials
    }

    // hatch:fixer begin — RS-02: pages see the system's prefers-color-scheme, never Hatch's Appearance override.
    val engine: Engine by lazy { GeckoEngine(context, engineSettings, runtime).also { PageColorScheme.install(context, it) } }
    // hatch:fixer end

    val client: Client by lazy { GeckoViewFetchClient(context, runtime) }

    val downloadFileUtils: DownloadFileUtils by lazy { DefaultDownloadFileUtils(context = context) }

    // hatch:fixer begin — ENG-01 / ENG-05: `onTrimMemory` and the restart flush must never *create* the store
    // (that would initialise Gecko from the :restart process or a background trim); they ask first.
    private val storeLazy: Lazy<BrowserStore> = lazy { createStore() }
    val isStoreInitialized: Boolean get() = storeLazy.isInitialized()
    private val iconsLazy: Lazy<BrowserIcons> = lazy { BrowserIcons(context, client) }
    val isIconsInitialized: Boolean get() = iconsLazy.isInitialized()

    /** ENG-06: bumped after every completed thumbnail write; the tray / home row key their loads on it. */
    val thumbnailGeneration: ThumbnailGeneration by lazy { ThumbnailGeneration() }
    // hatch:fixer end

    val store: BrowserStore by storeLazy

    private fun createStore(): BrowserStore =
        BrowserStore(
            // hatch:fixer — ENG-02: desktopMode seeded from the persona so every new tab / engine session is
            // created in desktop mode by AC's own path (TabsUseCases -> CreateEngineSessionMiddleware).
            initialState = BrowserStateFactory.initial(personaManager.current.value),
            middleware = listOf(
                DownloadMiddleware(
                    applicationContext = context,
                    downloadServiceClass = HatchDownloadService::class.java,
                    deleteFileFromStorage = { false },
                    downloadFileUtils = downloadFileUtils,
                ),
                PromptMiddleware(),
                // hatch:chrome begin — tab tray: "Tab closed – UNDO" (UndoAction.RestoreRecoverableTabs; the window is
                // TabClosePolicy.UNDO_WINDOW_MS, UXP-03) and thumbnails persisted on UpdateThumbnailAction (through
                // ThumbnailGeneration's middleware, ENG-06) / deleted with their tabs (AC's ThumbnailsMiddleware).
                UndoMiddleware(clearAfterMillis = TabClosePolicy.UNDO_WINDOW_MS),
                thumbnailGeneration.middleware(thumbnailStorage),
                ThumbnailsMiddleware(thumbnailStorage),
                // hatch:chrome end
            ) + EngineMiddleware.create(engine),
        ).also { store ->
            // Extension lifecycle (popups/options pages as tabs, install/permission prompts into the store).
            WebExtensionSupport.initialize(engine, store, openPopupInTab = true)
            bridge.install()
            // Favicons: AC's built-in "icons@mozac.org" extension (one isolated-world content script at document_end
            // that only reads <link rel=icon> / <meta og:image> and posts one native message; docs/extension.md).
            // Verified tell-free in headless with `run.mjs --extra-ext` (red-team 01/03/07/13, full harness run);
            // re-run that check after every AC upgrade.
            icons.install(engine, store)
            // Popups / target=_blank sessions inherit the persona's UA + desktop viewport (HATCH-WC-05).
            personaManager.observeSessions(store)
        }

    val sessionStorage: SessionStorage by lazy { SessionStorage(context, engine, applicationScope = applicationScope) }

    // hatch:chrome begin
    /**
     * Session restore + autosave run exactly once per PROCESS, the first time the browser UI needs them (M1
     * inventory risk 1: the old `savedInstanceState == null` gate skipped the restore after Android recreated the
     * Activity in a fresh process, left autosave unregistered and let the next save overwrite the file).
     * `TabsUseCases.restore(SessionStorage)` reads on IO and dispatches `RestoreAction` + `RestoreCompleteAction`
     * on Main; autosave (30 s foreground / background / on change) is registered only after the restore so it can
     * never persist an empty store over the saved session.
     */
    val sessionRestore: Job by lazy {
        applicationScope.launch {
            withContext(Dispatchers.IO) { tabsUseCases.restore(sessionStorage) }
            sessionStorage.autoSave(store)
                .periodicallyInForeground(interval = 30, unit = TimeUnit.SECONDS)
                .whenGoingToBackground()
                .whenSessionsChange()
        }
    }

    /**
     * Thumbnails for the tab tray / home row (`browser-thumbnails`): JPEG q90 of the full GeckoView surface per tab
     * under `noBackupFilesDir/mozac_browser_thumbnails` (100 MB DiskLruCache cap; PERF-07). Its constructor clears
     * the private-tab cache on disk, so `HatchApplication` warms it on IO (PERF-03).
     */
    val thumbnailStorage: ThumbnailStorage by lazy { ThumbnailStorage(context) }

    /** Favicons (`browser-icons`: memory + disk cache, letter fallback via DefaultIconGenerator). */
    val icons: BrowserIcons by iconsLazy

    /** URL-bar inline autocomplete: open tabs first, then the shipped top-domains list. */
    val domainsAutocompleteProvider: ShippedDomainsProvider by lazy { ShippedDomainsProvider(autocompletePriority = 1).also { it.initialize(context) } }
    val openTabsAutocompleteProvider: OpenTabsAutocompleteProvider by lazy {
        OpenTabsAutocompleteProvider(tabUrls = { store.state.tabs.map { it.content.url } })
    }
    // hatch:chrome end

    val sessionUseCases: SessionUseCases by lazy { SessionUseCases(store) }
    val tabsUseCases: TabsUseCases by lazy { TabsUseCases(store) }
    val searchUseCases: SearchUseCases by lazy { SearchUseCases(store, tabsUseCases, sessionUseCases) }
    val contextMenuUseCases: ContextMenuUseCases by lazy { ContextMenuUseCases(store) }
    val downloadsUseCases: DownloadsUseCases by lazy { DownloadsUseCases(store, downloadFileUtils) }

    val notificationsDelegate: NotificationsDelegate by lazy { NotificationsDelegate(NotificationManagerCompat.from(context)) }
    val fileSizeFormatter: FileSizeFormatter by lazy { DefaultFileSizeFormatter(context) }
    val downloadEstimator: DownloadEstimator by lazy { DownloadEstimator(DefaultDateTimeProvider()) }
    val fileUploadsDirCleaner: FileUploadsDirCleaner by lazy { FileUploadsDirCleaner(applicationScope) { context.cacheDir } }

    val rulesRepository: SiteRulesRepository by lazy {
        SiteRulesRepository(context).also { repo ->
            repo.onChanged = { rules -> bridge.sendRules(repo.toJsonArray(rules)) }
        }
    }

    // hatch:rules begin — site rules / permissions / persona-editor overlays, the site-permissions storage the
    // feature and the screen share, and the host-rewrite table minus the user's disabled hosts.
    val rulesUi: RulesUiState by lazy { RulesUiState() }

    /**
     * Same construction as Fenix 158 `Core.geckoSitePermissionsStorage` (Core.kt:306-307): the Gecko storage wraps
     * the Room one so a revoke also clears GeckoView's own persisted content permission (`removeGeckoContentPermissionBy`).
     */
    val sitePermissionsStorage: SitePermissionsStorage by lazy { GeckoSitePermissionsStorage(runtime, OnDiskSitePermissionsStorage(context)) }

    /** `UrlRewriteRules.extensionHostRewrites` minus `settings.disabledHostRewrites`; pushed at startup and on change. */
    fun effectiveHostRewrites(): List<HostRewrite> = HostRewriteFilter.effective(UrlRewriteRules.extensionHostRewrites, settings.disabledHostRewrites)
    // hatch:rules end

    val bridge: BridgeExtension by lazy {
        BridgeExtension(engine, { store }) {
            BridgeExtension.State(
                persona = personaManager.shimConfig(),
                rules = rulesRepository.rules.value,
                rulesJson = rulesRepository.toJsonArray(),
                hostRewrites = effectiveHostRewrites(), // hatch:rules — per-host disable list (AppSettings.disabledHostRewrites)
            )
        }
    }
}
