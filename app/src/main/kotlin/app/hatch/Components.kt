package app.hatch

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import app.hatch.downloads.HatchDownloadService
import app.hatch.engine.GeckoRuntimeFactory
import app.hatch.engine.HatchRequestInterceptor
import app.hatch.ext.BridgeExtension
import app.hatch.persona.PersonaManager
import app.hatch.persona.UrlRewriteRules
import app.hatch.rules.SiteRulesRepository
import app.hatch.settings.AppSettings
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
import mozilla.components.support.base.android.NotificationsDelegate
import mozilla.components.support.utils.DefaultDateTimeProvider
import mozilla.components.support.utils.DefaultDownloadFileUtils
import mozilla.components.support.utils.DownloadFileUtils
import mozilla.components.support.webextensions.WebExtensionSupport
import org.mozilla.geckoview.GeckoRuntime

/** Process-wide lazy singletons. Order of first access: settings -> personaManager -> runtime -> engine -> store. */
class Components(private val context: Context) {
    val applicationScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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
        )
    }

    val runtime: GeckoRuntime by lazy { GeckoRuntimeFactory.create(context, personaManager.current.value, settings) }

    val engine: Engine by lazy { GeckoEngine(context, engineSettings, runtime) }

    val client: Client by lazy { GeckoViewFetchClient(context, runtime) }

    val downloadFileUtils: DownloadFileUtils by lazy { DefaultDownloadFileUtils(context = context) }

    val store: BrowserStore by lazy {
        BrowserStore(
            middleware = listOf(
                DownloadMiddleware(
                    applicationContext = context,
                    downloadServiceClass = HatchDownloadService::class.java,
                    deleteFileFromStorage = { false },
                    downloadFileUtils = downloadFileUtils,
                ),
                PromptMiddleware(),
            ) + EngineMiddleware.create(engine),
        ).also { store ->
            // Extension lifecycle (popups/options pages as tabs, install/permission prompts into the store).
            WebExtensionSupport.initialize(engine, store, openPopupInTab = true)
            bridge.install()
            // Popups / target=_blank sessions inherit the persona's UA + desktop viewport (HATCH-WC-05).
            personaManager.observeSessions(store)
        }
    }

    val sessionStorage: SessionStorage by lazy { SessionStorage(context, engine, applicationScope = applicationScope) }

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

    val bridge: BridgeExtension by lazy {
        BridgeExtension(engine, { store }) {
            BridgeExtension.State(
                persona = personaManager.shimConfig(),
                rules = rulesRepository.rules.value,
                rulesJson = rulesRepository.toJsonArray(),
                hostRewrites = UrlRewriteRules.extensionHostRewrites,
            )
        }
    }
}
