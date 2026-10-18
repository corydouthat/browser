package app.hatch.engine

import app.hatch.persona.Persona
import mozilla.components.browser.state.state.BrowserState

/**
 * ENG-02: the `BrowserStore`'s initial state is seeded from the active persona so `BrowserState.desktopMode`
 * mirrors it from the first dispatch. `TabsUseCases.addTab` copies `store.state.desktopMode` into every new tab
 * (`TabsUseCases.kt:163-200` at FIREFOX_158_0_RELEASE) and `CreateEngineSessionMiddleware` calls
 * `toggleDesktopMode(tab.content.desktopMode, reload = false)` on the fresh session before linking it, so with the
 * seed every tab is born in desktop mode through AC's own path; `PersonaManager.observeSessions` stays as the
 * belt-and-braces correction. `BrowserState()`'s default is `desktopMode = false` (`BrowserState.kt:61`).
 */
object BrowserStateFactory {
    fun initial(persona: Persona): BrowserState = BrowserState(desktopMode = persona.desktop)
}
