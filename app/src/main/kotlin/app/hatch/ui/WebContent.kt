/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

// Vendored from Android Components 158 (components/compose/engine/.../WebContent.kt) and reduced for Hatch: the
// EngineView is created once by the Activity and handed in; Compose only HOSTS the view. Rendering and releasing
// the selected tab's EngineSession is the job of AC's SessionFeature (feature-session EngineViewPresenter:
// observes the selected tab, dispatches CreateEngineSessionAction, releases on crash / no tab, stamps last-access).
// M1 inventory risk 5: there is exactly one owner of render/release; the previous copy of this file also called
// render()/release() from AndroidView.update and raced the presenter on tab close.

package app.hatch.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import mozilla.components.concept.engine.EngineView

/** Composes the given [engineView]. What it renders is driven by `SessionFeature` (MainActivity). */
@Composable
fun WebContent(
    engineView: EngineView,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { engineView.asView() },
    )
}
