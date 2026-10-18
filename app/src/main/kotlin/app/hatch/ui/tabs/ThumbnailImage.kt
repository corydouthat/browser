/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

// Adapted from Fenix 158 (mobile/android/fenix/app/src/main/java/org/mozilla/fenix/compose/ThumbnailImage.kt,
// tag FIREFOX_158_0_RELEASE): the storage is passed in instead of read from Fenix's Components, the preview /
// under-test branches and tracing are dropped, and the load is keyed on a thumbnail generation (ENG-06) so a
// capture that lands after the card was composed is picked up. Listed in THIRD_PARTY.md.

package app.hatch.ui.tabs

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.launch
import mozilla.components.browser.thumbnails.storage.ThumbnailStorage
import mozilla.components.concept.base.images.ImageLoadRequest

private data class ThumbnailImageState(val bitmap: Bitmap?, val hasLoaded: Boolean)

/**
 * Loads a tab thumbnail from [ThumbnailStorage] (`loadThumbnail(ImageLoadRequest(id, sizePx, isPrivate))`,
 * scaled to [ImageLoadRequest.size]) and recycles the bitmap when the composable leaves the tree, so a grid of
 * thumbnails does not keep screen-sized bitmaps alive. [generation] (`ThumbnailGeneration.generation`) changes
 * when a new capture has been written, which re-reads the file. [fallbackContent] renders when no thumbnail exists.
 */
@Composable
fun ThumbnailImage(
    request: ImageLoadRequest,
    storage: ThumbnailStorage,
    generation: Long,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.TopCenter,
    fallbackContent: @Composable () -> Unit,
) {
    val key = ThumbnailGeneration.thumbnailKey(request, generation)
    var state by remember(key) { mutableStateOf(ThumbnailImageState(null, false)) }
    val scope = rememberCoroutineScope()

    DisposableEffect(key) {
        if (!state.hasLoaded) {
            scope.launch {
                val bitmap = storage.loadThumbnail(request).await()
                bitmap?.prepareToDraw()
                state = ThumbnailImageState(bitmap = bitmap, hasLoaded = true)
            }
        }
        onDispose {
            // Recycle to liberate the RAM; the bitmap is re-fetched if this composable is composed again.
            state.bitmap?.recycle()
            state = ThumbnailImageState(bitmap = null, hasLoaded = false)
        }
    }

    val bitmap = state.bitmap
    if (bitmap != null && !bitmap.isRecycled) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale,
            alignment = alignment,
        )
    } else if (state.hasLoaded) {
        fallbackContent()
    }
}
