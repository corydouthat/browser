package app.hatch.ui.rules

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.hatch.ui.overlayPane
import app.hatch.ui.settings.NavIcon
import app.hatch.ui.settings.ScreenTopBar
import app.hatch.ui.theme.HatchTheme

/**
 * Full-screen overlay chrome shared by the rules module's screens (site rules, rule editor, site permissions,
 * persona editor): opaque surface (so the browser below never shows through), the design's 64 dp top bar with a
 * back arrow or an X ([nav]), trailing [actions], an optional FAB and an own snackbar host for the screen's local
 * state (the browser's host sits under the overlay), styled like the app-wide one: inverse surface, r12, the
 * action in `invAc`. The scaffold is an [overlayPane]: it takes keyboard focus when it opens, is announced by its
 * title, and keeps its content clear of a landscape cutout (A11Y-03 / A11Y-04).
 */
@Composable
fun RulesScaffold(
    title: String,
    onBack: () -> Unit,
    snackbar: SnackbarHostState,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    nav: NavIcon = NavIcon.Back,
    content: @Composable (PaddingValues) -> Unit,
) {
    val c = HatchTheme.colors
    Surface(Modifier.fillMaxSize(), color = c.bg, contentColor = c.ink) {
        Scaffold(
            modifier = Modifier.overlayPane(title),
            topBar = { ScreenTopBar(title = title, onNav = onBack, nav = nav, actions = actions) },
            snackbarHost = {
                SnackbarHost(snackbar) { data ->
                    Snackbar(
                        snackbarData = data,
                        shape = RoundedCornerShape(12.dp),
                        containerColor = c.inv,
                        contentColor = c.oinv,
                        actionColor = c.invAc,
                        actionContentColor = c.invAc,
                        dismissActionContentColor = c.oinv,
                    )
                }
            },
            floatingActionButton = floatingActionButton,
            containerColor = c.bg,
            contentColor = c.ink,
            content = content,
        )
    }
}
