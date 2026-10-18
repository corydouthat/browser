/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

// Adapted from Fenix 158 (mobile/android/fenix/app/src/main/java/org/mozilla/fenix/compose/Favicon.kt, tag
// FIREFOX_158_0_RELEASE): BrowserIcons is passed in, the placeholder is a tinted surface, previews dropped.
// Listed in THIRD_PARTY.md.

package app.hatch.ui.tabs

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mozilla.components.browser.icons.BrowserIcons
import mozilla.components.browser.icons.IconRequest
import mozilla.components.browser.icons.compose.Loader
import mozilla.components.browser.icons.compose.Placeholder
import mozilla.components.browser.icons.compose.WithIcon

internal val FAVICON_SHAPE = RoundedCornerShape(2.dp)

/**
 * The tab's favicon: the bitmap the engine already delivered (`tab.content.icon`) when present, otherwise
 * `BrowserIcons.Loader` (memory/disk cache → network → `DefaultIconGenerator` letter fallback).
 */
@Composable
fun Favicon(
    url: String,
    icons: BrowserIcons,
    size: Dp,
    modifier: Modifier = Modifier,
    bitmap: Bitmap? = null,
    isPrivate: Boolean = false,
    shape: Shape = FAVICON_SHAPE,
) {
    if (bitmap != null && !bitmap.isRecycled) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.size(size).clip(shape),
            contentScale = ContentScale.Crop,
        )
        return
    }
    icons.Loader(url = url, size = IconRequest.Size.DEFAULT, isPrivate = isPrivate) {
        Placeholder {
            Box(modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest))
        }
        WithIcon { icon ->
            Image(
                painter = icon.painter,
                contentDescription = null,
                modifier = modifier.size(size).clip(shape),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
