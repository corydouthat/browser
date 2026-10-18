package app.hatch.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * "Open in another browser": an `ACTION_VIEW` chooser that never lists Hatch itself. Android's chooser honours
 * `EXTRA_EXCLUDE_COMPONENTS` (API 24+, minSdk is 26), so the exclusion is by component name, which also covers the
 * debug build (`app.hatch.browser.debug`) running next to a release build.
 */
object ExternalBrowser {
    /** Only http(s) URLs are handed to other apps (no `about:`, `file:`, `data:`, extension pages). */
    fun isShareableUrl(url: String?): Boolean =
        url != null && (url.startsWith("http://") || url.startsWith("https://")) && url.length > 8

    /** (package, activity class) pairs the chooser must hide: Hatch's own VIEW handler per package (pure). */
    fun excludedTargets(selfPackages: Collection<String>, activityClassName: String): List<Pair<String, String>> =
        selfPackages.filter { it.isNotBlank() }.distinct().map { it to activityClassName }

    fun excludedComponents(selfPackages: Collection<String>, activityClassName: String): Array<ComponentName> =
        excludedTargets(selfPackages, activityClassName).map { (pkg, cls) -> ComponentName(pkg, cls) }.toTypedArray()

    fun chooserIntent(context: Context, url: String, title: String = "Open with"): Intent {
        val view = Intent(Intent.ACTION_VIEW, url.toUri()).apply { addCategory(Intent.CATEGORY_BROWSABLE) }
        val chooser = Intent.createChooser(view, title)
        chooser.putExtra(
            Intent.EXTRA_EXCLUDE_COMPONENTS,
            excludedComponents(listOf(context.packageName), MAIN_ACTIVITY),
        )
        return chooser
    }

    fun shareIntent(url: String, title: String?): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
        }
        return Intent.createChooser(send, null)
    }

    const val MAIN_ACTIVITY = "app.hatch.MainActivity"
}
