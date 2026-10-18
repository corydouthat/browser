package app.hatch.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.hatch.Components
import app.hatch.persona.AppRestart
import app.hatch.persona.Persona
import app.hatch.persona.PersonaManager
import app.hatch.selfcheck.SelfCheckController
import kotlinx.coroutines.launch
import mozilla.components.concept.engine.EngineView

/** Which full-screen overlay (if any) sits above the browser. The browser (and its EngineView) is always composed. */
enum class Overlay { None, Settings }

class AppUiState {
    var overlay by mutableStateOf(Overlay.None)
    var personaSheet by mutableStateOf(false)
    var selfCheck by mutableStateOf(false)
    var focusUrlRequest by mutableStateOf(0)

    /** True when a Compose overlay consumed the back press. */
    fun onBack(): Boolean = when {
        personaSheet -> { personaSheet = false; true }
        overlay != Overlay.None -> { overlay = Overlay.None; true }
        selfCheck -> { selfCheck = false; true }
        else -> false
    }
}

@Composable
fun HatchApp(
    components: Components,
    engineView: EngineView,
    uiState: AppUiState,
    uiEvents: UiEvents,
    selfCheck: SelfCheckController,
    onRestart: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun applyPersona(persona: Persona) {
        scope.launch {
            when (val r = components.personaManager.apply(persona)) {
                PersonaManager.ApplyResult.Applied -> snackbar.showSnackbar("Persona: ${persona.identity.name} · ${persona.region.name}")
                PersonaManager.ApplyResult.NeedsRestart -> {
                    val res = snackbar.showSnackbar(
                        message = "Restart to apply time zone",
                        actionLabel = "Restart",
                        duration = SnackbarDuration.Long,
                    )
                    if (res == SnackbarResult.ActionPerformed) onRestart()
                }
                is PersonaManager.ApplyResult.Failed -> snackbar.showSnackbar("Persona switch failed: ${r.error.message}")
            }
        }
    }

    LaunchedEffect(uiEvents) {
        uiEvents.events.collect { e ->
            when (e) {
                UiEvent.FocusUrl -> { uiState.overlay = Overlay.None; uiState.focusUrlRequest++ }
                UiEvent.NewTab -> components.tabsUseCases.addTab(components.settings.homeUrl)
                UiEvent.CloseTab -> components.store.state.selectedTabId?.let { components.tabsUseCases.removeTab(it) }
                UiEvent.Reload -> components.sessionUseCases.reload()
                UiEvent.ToggleEruda -> {
                    val session = components.store.state.selectedTabId?.let { id ->
                        components.store.state.tabs.firstOrNull { it.id == id }?.engineState?.engineSession
                    }
                    val ok = session != null && components.bridge.toggleEruda(session)
                    if (!ok) snackbar.showSnackbar("Eruda: no bridge port on this page (load a http(s) page first)")
                }
                UiEvent.PersonaSheet -> uiState.personaSheet = !uiState.personaSheet
                UiEvent.Escape -> if (!uiState.onBack()) components.sessionUseCases.stopLoading()
                is UiEvent.Snackbar -> {
                    val res = snackbar.showSnackbar(e.message, actionLabel = e.action)
                    if (res == SnackbarResult.ActionPerformed) e.onAction?.invoke()
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        BrowserScreen(
            components = components,
            engineView = engineView,
            uiState = uiState,
            snackbar = snackbar,
            selfCheck = selfCheck,
            onApplyPersona = ::applyPersona,
            onMessage = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
        )
        if (uiState.personaSheet) {
            PersonaSheet(
                personaManager = components.personaManager,
                onDismiss = { uiState.personaSheet = false },
                onApply = { p -> uiState.personaSheet = false; applyPersona(p) },
            )
        }
        when (uiState.overlay) {
            Overlay.Settings -> SettingsScreen(
                components = components,
                onClose = { uiState.overlay = Overlay.None },
                onRestart = onRestart,
                snackbar = snackbar,
            )
            Overlay.None -> Unit
        }
    }
}
