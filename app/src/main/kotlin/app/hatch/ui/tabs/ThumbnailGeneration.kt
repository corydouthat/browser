package app.hatch.ui.tabs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.thumbnails.storage.ThumbnailStorage
import mozilla.components.concept.base.images.ImageLoadRequest
import mozilla.components.concept.base.images.ImageSaveRequest
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store

/**
 * ENG-06 / UXP-14: `ThumbnailImage` loads a thumbnail once per request and AC's `ThumbnailsMiddleware` writes the
 * capture to disk asynchronously, so a card composed before the write landed (the tray opens in the same frame as
 * `requestScreenshot`) showed the previous capture, or the favicon fallback, until the tray was reopened.
 *
 * This middleware takes over `ContentAction.UpdateThumbnailAction` from AC's (it sits before it in the store's
 * list and, like AC's, does not forward the action to the reducer): it saves through the same
 * `ThumbnailStorage.saveThumbnail(ImageSaveRequest, Bitmap)` and bumps [generation] when the disk write completes.
 * `ThumbnailImage` keys its load on `(request, generation)` ([thumbnailKey]), so every card showing that tab
 * re-reads the fresh file. Deletes / clear stay with AC's middleware.
 */
class ThumbnailGeneration {
    private val _generation = MutableStateFlow(0L)

    /** Bumped after every completed thumbnail write. */
    val generation: StateFlow<Long> get() = _generation

    fun bump() {
        _generation.value = _generation.value + 1
    }

    fun middleware(storage: ThumbnailStorage): Middleware<BrowserState, BrowserAction> = { store, next, action ->
        if (action is ContentAction.UpdateThumbnailAction) {
            saveAndBump(store, storage, action)
        } else {
            next(action)
        }
    }

    private fun saveAndBump(
        store: Store<BrowserState, BrowserAction>,
        storage: ThumbnailStorage,
        action: ContentAction.UpdateThumbnailAction,
    ) {
        val tab = store.state.tabs.find { it.id == action.sessionId } ?: return
        storage.saveThumbnail(ImageSaveRequest(tab.id, tab.content.private), action.thumbnail).invokeOnCompletion { bump() }
    }

    companion object {
        /** Load key for one card: changes when the request changes or a new capture was written. */
        fun thumbnailKey(request: ImageLoadRequest, generation: Long): Pair<ImageLoadRequest, Long> = request to generation
    }
}
