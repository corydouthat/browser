package app.hatch.downloads

import app.hatch.components
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.fetch.Client
import mozilla.components.feature.downloads.AbstractFetchDownloadService
import mozilla.components.feature.downloads.DefaultPackageNameProvider
import mozilla.components.feature.downloads.DownloadEstimator
import mozilla.components.feature.downloads.FileSizeFormatter
import mozilla.components.feature.downloads.PackageNameProvider
import mozilla.components.feature.downloads.filewriter.DefaultDownloadFileWriter
import mozilla.components.feature.downloads.filewriter.DownloadFileWriter
import mozilla.components.support.base.android.NotificationsDelegate
import mozilla.components.support.utils.DownloadFileUtils

/** Foreground download service (dataSync) backed by Gecko's cookie-aware fetch client. */
class HatchDownloadService : AbstractFetchDownloadService() {
    override val httpClient: Client by lazy { components.client }
    override val store: BrowserStore by lazy { components.store }
    override val notificationsDelegate: NotificationsDelegate by lazy { components.notificationsDelegate }
    override val fileSizeFormatter: FileSizeFormatter by lazy { components.fileSizeFormatter }
    override val downloadEstimator: DownloadEstimator by lazy { components.downloadEstimator }
    override val packageNameProvider: PackageNameProvider by lazy { DefaultPackageNameProvider(applicationContext) }
    override val downloadFileUtils: DownloadFileUtils by lazy { components.downloadFileUtils }
    override val downloadFileWriter: DownloadFileWriter by lazy {
        DefaultDownloadFileWriter(context = applicationContext, downloadFileUtils = downloadFileUtils)
    }
}
