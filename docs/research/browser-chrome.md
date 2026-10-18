# Browser chrome for M1: toolbar, tabs, find, menu (research, 2026-10-18)

Why: Cory's first-use feedback: "no tab switching? tab management?" and "doesn't look like regular
Firefox / isn't pretty". Cory chose a **Chrome-style top toolbar** (address bar and tab switcher at the
top, bottom edge free for page content), Material 3 with dynamic colour, grid tab tray with thumbnails.
Verified against `FIREFOX_158_0_RELEASE` (`mobile/android/android-components`, `mobile/android/fenix`) and
the 158.0 POMs on maven.mozilla.org unless marked unverified.

## What Hatch has today

`ui/HatchApp.kt` (overlays: Settings; persona sheet; self-check), `ui/BrowserScreen.kt` (top toolbar with
back/forward, `OutlinedTextField`, reload, 8-item `DropdownMenu`, persona `AssistChip`, progress bar; "No
tabs" placeholder), `ui/PersonaSheet.kt`, `ui/SettingsScreen.kt`, `ui/SelfCheckBar.kt`, `ui/Theme.kt`
(dynamic M3 colour on S+), `ui/WebContent.kt` (vendored). **No tab list or switching UI** (only "Close tab
(N open)"), no thumbnails, favicons, find bar, undo, site-security indicator, scroll-to-hide.
`TabsUseCases` wired (`Components.kt:96`), `UndoMiddleware` not.

## Adopt vs hand-roll

| Concern | Decision | Why / size (AAR bytes, 158.0) |
|---|---|---|
| Address bar, tab counter, progress, display/edit modes | **Adopt `compose-browser-toolbar`** (reverses the M0 exclusion; decision logged in architecture.md) | It is the Fenix 158 toolbar: `BrowserToolbar(store, cfr, useMinimalBottomToolbarWhenEnteringText)` renders `BrowserDisplayToolbar` (pill origin, `ProgressBarConfig`, four action slots) or `BrowserEditToolbar` (`InlineAutocompleteTextField`). State: `BrowserToolbarStore` with `BrowserToolbarState(mode, displayState, editState, gravity = ToolbarGravity.Top)`; integrators handle `BrowserToolbarEvent`s in a middleware (Fenix `BrowserToolbarMiddleware.kt` is the pattern). Colours come from `MaterialTheme.colorScheme`, so `HatchTheme` applies without `AcornTheme`. Closure: compose-browser-toolbar 544 K + compose-base 837 K + compose-cfr 178 K + ui-tabcounter 96 K + browser-menu2 155 K + concept-menu 52 K + ui-icons 305 K + ui-colors 36 K + concept-toolbar 31 K + ui-autocomplete 24 K ≈ 2.26 MB of AC AARs, plus androidx it pins (navigation-compose 2.10.1, material3-adaptive 1.3.0, adaptive-navigation-suite, window-size-class) ≈ 1.4 MB. R8 strips what is unused; well under 2 % of the APK. |
| Tab counter glyph | `ui-tabcounter` (transitive) | Compose `TabCounter(tabCount, showPrivacyBadge, contentColor)`. |
| Icons | `ui-icons` (transitive) | `mozac_ic_plus_24`, `mozac_ic_ellipsis_vertical_24`, `mozac_ic_arrow_clockwise_24`, `mozac_ic_home_24`, `mozac_ic_cross_24`, `mozac_ic_globe_24`, `mozac_ic_shield_slash_24`, `mozac_ic_shield_checkmark_24` verified. **Never reference `mozac_ic_logo_firefox_24`** (or chrome/safari/lens logos): trademarks, not MPL. Never copy Fenix `ic_firefox`/`ic_launcher`. |
| Tab tray | **Hand-roll in Compose, copying Fenix building blocks** (MPL header kept, `THIRD_PARTY.md` entry) | `compose-tabstray` is a toy (`LazyColumn`, hard-coded colour, no thumbnails/grid). Fenix's full tray is not extractable, but `tabstray/ui/tabitems/TabGridTabItem.kt` (favicon+title+close header, thumbnail card in `aspectRatio`, M3 `SwipeToDismissBox`), `compose/SwipeToDismiss.kt`, `compose/ThumbnailImage.kt` (`ThumbnailStorage.loadThumbnail(ImageLoadRequest(id, sizePx, isPrivate))` in a `DisposableEffect`, bitmap recycled), `compose/Favicon.kt` are 100-300 lines each. Undo: `tabsUseCases.undo` (`UndoTabRemovalUseCase` → `UndoAction.RestoreRecoverableTabs`) needs `mozilla.components.feature.session.middleware.undo.UndoMiddleware` in the store (feature-session is already a dependency). |
| Thumbnails | Adopt `browser-thumbnails` (+ support-images, concept-base) | `BrowserThumbnails(context, engineView, store)` captures on load-complete via `EngineView.captureThumbnail`; `ThumbnailsMiddleware(ThumbnailStorage(context))` persists and deletes with tabs. Fenix calls `requestScreenshot` right before opening the tray. |
| Favicons | Adopt `browser-icons` (pulls androidsvg, palette-ktx, service-merino-manifest) | `BrowserIcons(context, client)` + `install(engine, store)` (installs AC's built-in icons extension — **run the harness/red-team pages afterwards; a second built-in extension must not add a tell**) + compose `Loader`. `DefaultIconGenerator` letter fallback. |
| Find in page | **Hand-roll** | `feature-findinpage` is a View bar; the engine API is `EngineSession.findAll/findNext/clearFindMatches` (`EngineSession.kt:1022-1032`) and results arrive in `tab.content.findResults`. A Compose row is ~80 lines. |
| URL autocomplete | `browser-domains` `ShippedDomainsProvider` + a session-tabs provider | The compose toolbar takes `BrowserEditToolbarAction.AutocompleteSuggestionUpdated(AutocompleteResult?)`; Fenix computes it with `providers.firstNotNullOfOrNull { it.getAutocompleteSuggestion(input) }` (`fenix/search/BrowserToolbarSearchMiddleware.kt:422-493`). No awesomebar/history in M1 (needs Places). |
| Menu | Hand-roll with M3 `ModalBottomSheet` | `compose-menu` is a Store-driven Fenix main menu; a sheet with a quick-action row + list is smaller. |
| Theme | Keep `HatchTheme` (dynamic M3) | Fenix uses fixed Acorn palettes; Hatch gets Material You. Compose BOM 2026.09.00 → material3 1.4.0, ui 1.12.1: `SwipeToDismissBox`, `ModalBottomSheet`, `PredictiveBackHandler` (activity-compose 1.13). |

## Screen-by-screen (top toolbar)

**Toolbar (display mode)**, `ToolbarGravity.Top`, directly under the status bar (edge-to-edge, scrim-free).
`browserActionsStart = []`; `pageActionsStart = [site-info ActionButtonRes]` (`mozac_ic_globe_24` when
`securityInfo == Unknown`, lock/shield-slash by `securityInfo.isSecure`; tap → site info dialog with host,
cert issuer, per-site permissions link); `pageOrigin = PageOrigin(url = URLStringUtils.toDisplayUrl(url),
contextualMenuOptions = ContextualMenuOption.entries, onClick = OriginClicked)`; `pageActionsEnd =
[force-desktop toggle ActionButtonRes (ACTIVE when persona.desktop), reload/stop]`; `browserActionsEnd =
[TabCounterAction(count, onClick = OpenTabs, onLongClick = menu{new tab, close tab}),
ActionButtonRes(mozac_ic_ellipsis_vertical_24 → menu)]`; `progressBarConfig = ProgressBarConfig(progress)`
while loading. **Persona strip**: a compact 32-dp row under the toolbar (the slot Fenix reserves for its
tab strip): `AssistChip("Win · Firefox · US")` → PersonaSheet, and a `desktop/phone` `FilterChip`
mirroring the toggle. Collapses with the toolbar.

**Edit mode.** Tap origin → `EnterEditMode`; `SearchQueryUpdated(BrowserToolbarQuery(url), isQueryPrefilled
= true)`; on text change compute `AutocompleteSuggestionUpdated`; `CommitUrl(text)` → existing
`normalizeInput()` → `loadUrl`; `ExitEditMode`; back exits edit mode.

**Tab tray** (`Overlay.Tabs`, full screen, predictive back): `Scaffold` with `TopAppBar` ("12 tabs" / in
select mode "3 selected" + close/share; overflow "Close all tabs" with confirm),
`LazyVerticalGrid(GridCells.Fixed(2 portrait / 3-4 landscape))` of `TabGridItem` cards (favicon + title,
close ×, thumbnail at aspect ≈ 0.95, selected border), horizontal `SwipeToDismissBox` → `removeTab` +
snackbar "Tab closed – UNDO", long-press → multi-select, `FloatingActionButton(+)` → new tab then close
tray. Empty state + FAB. Opening the tray calls `thumbnails.requestScreenshot` first.

**Home / new tab.** New tab loads `about:blank`; `BrowserScreen` overlays `HomeContent` while the tab is
`about:blank` with no history: wordmark, persona card (identity, region, desktop switch, "Change…"),
"Recently open" thumbnails row; toolbar auto-enters edit mode. No top-sites storage in M1.

**Find bar.** Row under the toolbar: `TextField` (Ctrl+F focuses), "3/17" from `findResults.last()`,
prev/next (`findNext(false/true)`), close (`clearFindMatches`). Hide persona strip while open.

**Menu.** `ModalBottomSheet`: header (persona chip + desktop switch), quick-action row (back, forward,
share, reload), list: New tab, Find in page, Open in another browser, Add-ons, Saved logins, Site rules,
Self-check, Eruda devtools, Persona…, Settings.

## Engine integration

- `EngineView.setDynamicToolbarMaxHeight(h)` / `setVerticalClipping(px)` (`concept/engine/EngineView.kt:
  104-111`) → `GeckoView.java:231-250` → `GeckoDisplay.java:256-284`. Setting the max height resets
  clipping to 0. Fenix 158 does scroll-to-hide with View behaviours (`EngineViewClippingBehavior`,
  `EngineViewScrollingDataBehavior`), collecting `EngineView.verticalScrollDelta` (`Flow<Float>`,
  `GeckoEngineView.kt:101-103`); for a **top** toolbar Fenix translates the engine view with the toolbar and
  sets clipping accordingly; `BaseBrowserFragment.initializeEngineView` (`:1795-1821`) sets
  `setDynamicToolbarMaxHeight(top + bottom)`. Fenix gates dynamic toolbars on
  `!geckoRuntime.isInteractiveWidgetDefaultResizesVisual` (`BaseBrowserFragment.kt:2204`).
- Hatch (Compose, no CoordinatorLayout): `Box { WebContent; Column(TopCenter, translationY = -hidden)
  { BrowserToolbar; PersonaStrip } }` with a `DynamicToolbarController` collecting `verticalScrollDelta`,
  clamping `hidden ∈ [0, h]`, snapping after ~120 ms idle, expanding while `content.loading`. **Because
  this cannot be validated on a device before shipping, "Hide toolbar when scrolling" is a Settings switch
  that defaults OFF; the static toolbar is the reliable default.** Fallback if it misbehaves: host
  `CoordinatorLayout{EngineView, ComposeView}` in an `AndroidView` and reuse AC's behaviours.
- IME: keep `WindowInsets.ime` union (HATCH-WC-04); when the keyboard is visible, disable hiding, expand,
  `setDynamicToolbarMaxHeight(0)`, `setVerticalClipping(0)`.
- Fullscreen: hide the toolbar column, `setDynamicToolbarMaxHeight(0)`, `setVerticalClipping(0)`, close
  the find bar; on exit restore. Keep the existing immersive-mode code.

## New dependencies (158.0, MPL-2.0)

```
org.mozilla.components:compose-browser-toolbar:158.0   # + transitive compose-base, compose-cfr, ui-tabcounter, browser-menu2
org.mozilla.components:ui-icons:158.0
org.mozilla.components:ui-tabcounter:158.0
org.mozilla.components:concept-toolbar:158.0           # AutocompleteProvider / AutocompleteResult
org.mozilla.components:browser-domains:158.0           # ShippedDomainsProvider
org.mozilla.components:browser-thumbnails:158.0
org.mozilla.components:browser-icons:158.0
```
Not added: compose-tabstray, compose-menu, compose-awesomebar, feature-awesomebar, feature-findinpage,
feature-toolbar, feature-top-sites. Every copied Fenix file keeps its MPL header and gets a
`THIRD_PARTY.md` line (MPL 2.0 §3.1: source of modified MPL files must be available; the repo is public).

## Files

Create: `ui/toolbar/HatchToolbar.kt` (BrowserToolbarStore + middleware mapping events to use cases; sync of
url/progress/securityInfo/tab count), `ui/toolbar/DynamicToolbarController.kt`, `ui/toolbar/PersonaStrip.kt`,
`ui/tabs/TabTrayScreen.kt`, `ui/tabs/TabGridItem.kt`, `ui/tabs/ThumbnailImage.kt`, `ui/tabs/SwipeToDismiss.kt`,
`ui/tabs/Favicon.kt`, `ui/tabs/TabTrayState.kt`, `ui/find/FindInPageBar.kt`, `ui/menu/MainMenuSheet.kt`,
`ui/home/HomeContent.kt`, `ui/SiteInfoDialog.kt`.
Modify: `Components.kt` (ThumbnailStorage, `ThumbnailsMiddleware`, `UndoMiddleware`, `BrowserIcons`,
`ShippedDomainsProvider.initialize`), `MainActivity.kt` (BrowserThumbnails feature, Ctrl+F/Ctrl+Tab/
Ctrl+1..9/Alt+arrows, back order: find → tray → …; fullscreen hooks), `ui/HatchApp.kt`, `ui/BrowserScreen.kt`,
`ui/UiEvents.kt`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, docs.

## Risks / unverified

1. Scroll-to-hide without CoordinatorLayout (hence default OFF in M1).
2. `browser-icons.install()` adds a second built-in extension; verify no tell (harness + red-team pages).
3. `isInteractiveWidgetDefaultResizesVisual` default on GeckoView 158 not read.
4. Exact lock icon name (`mozac_ic_lock_24` vs `mozac_ic_lock_slash_24`) unconfirmed.
5. Compose toolbar reads `AcornTheme.gradients` for CFR props; default `localAcornGradients` should suffice.
6. Thumbnails ≈ screen-size bitmaps per tab on disk; the tray loads them scaled; keep the 600 MB PSS gate.
