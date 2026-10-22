package app.hatch.ui

import android.view.View
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics

/**
 * A11Y-03 / A11Y-04: how the full-screen overlays (Settings, tab tray, Saved logins, the rules screens) and the browser
 * under them share keyboard focus, the accessibility tree and the window insets. The browser (toolbar, persona chip,
 * the GeckoView `AndroidView`) stays composed under every overlay, so without this Tab and TalkBack's swipe order walk
 * from the overlay into the hidden toolbar and page.
 */
object OverlayPane {
    /** Whether a full-screen overlay hides the browser (the same rule as HatchApp's status-bar colour). */
    fun browserCovered(overlay: Overlay, savedLoginsOpen: Boolean, rulesOpen: Boolean): Boolean =
        overlay != Overlay.None || savedLoginsOpen || rulesOpen

    /** The EngineView's `importantForAccessibility` while covered / shown. */
    fun engineAccessibility(covered: Boolean): Int =
        if (covered) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
}

/**
 * The browser while an overlay covers it: no semantics reach TalkBack (`clearAndSetSemantics` drops the whole
 * subtree) and keyboard focus cannot enter it (a focus group that cancels every enter). No-op when [covered] is false.
 */
fun Modifier.coveredByOverlay(covered: Boolean): Modifier =
    if (!covered) {
        this
    } else {
        this
            .clearAndSetSemantics { }
            .focusProperties { onEnter = { cancelFocusChange() } }
            .focusGroup()
    }

/**
 * The root of a full-screen overlay: announces [title] as a pane (TalkBack reads it when the overlay appears), moves
 * keyboard focus into the overlay when it opens (its first focusable element, normally the top bar's back / close
 * button), and keeps its content inside the horizontal safe-drawing insets (landscape display cutout, side navigation
 * bar). The caller's background is drawn outside this padding, so it still fills the cutout.
 */
@Composable
fun Modifier.overlayPane(title: String): Modifier {
    val requester = remember { FocusRequester() }
    LaunchedEffect(requester) { runCatching { requester.requestFocus() } }
    return this
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        .semantics { paneTitle = title }
        .focusRequester(requester)
        .focusGroup()
}
