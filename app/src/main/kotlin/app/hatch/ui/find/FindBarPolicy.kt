package app.hatch.ui.find

/**
 * When the find bar closes by itself (UXP-05, Fenix `BaseBrowserFragment.closeFindInPageBarOnNavigation`): a tab
 * switch or a navigation to another document. A fragment-only change (`#section`) is the same document and keeps
 * the bar; so does a reload of the same URL.
 */
object FindBarPolicy {
    /** What the bar was opened on / what the selected tab shows now. */
    data class Anchor(val tabId: String?, val url: String?) {
        val documentUrl: String? get() = url?.substringBefore('#')
    }

    fun shouldClose(opened: Anchor, now: Anchor): Boolean {
        if (opened.tabId != now.tabId) return true
        return opened.documentUrl != now.documentUrl
    }
}
