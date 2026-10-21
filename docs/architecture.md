# Architecture

Hatch is an Android browser whose only product goal is to make a website render its **real desktop
experience** on a phone: not the "Request desktop site" UA swap with a 980 px viewport, but a coherent desktop
environment (layout width, pointer/hover media, no touch API surface, desktop `navigator`, screen, time zone,
language, location) that survives the five detection mechanisms modern sites use (server UA / client hints,
hostname redirects, CSS width breakpoints, JS touch/pointer probes, fingerprint tells).

## Engine choice: GeckoView 158 + Android Components 158

Chromium WebView lost on three facts: `AwSettings::PopulateFixedWebPreferences` hard-codes
`viewport_meta_enabled = true` (desktop mode still honours `<meta viewport>`), CDP `setEmulatedMedia` cannot
set `pointer`/`hover`, and the only route to a devtools socket is in-process CDP that nobody ships. GeckoView
does all of it at engine level and the engine is **pinned in the APK** (no system-WebView auto-updates on the
road):

- `USER_AGENT_MODE_DESKTOP` + `VIEWPORT_MODE_DESKTOP` per session; the viewport ignores `<meta viewport>` and is
  `max(display CSS width, browser.viewport.desktopWidth)` (we set 1366).
- Prefs for what Chromium cannot do: `ui.primaryPointerCapabilities` / `ui.allPointerCapabilities = 6`
  (fine + hover, refreshed live), `dom.w3c_touch_events.enabled = 0` (no `TouchEvent`/`ontouchstart`; taps still
  synthesize mouse events through APZ), `dom.maxtouchpoints.testing.value = 0`,
  `general.platform/oscpu/appversion.override`, `intl.accept_languages`.
- Gecko sends **no** client hints and has no `navigator.userAgentData`, so there is nothing to leak.
- `GeckoPreferenceController` (158 stable, `@ExperimentalGeckoViewApi`) makes persona switches live; only `TZ`
  needs a restart.
- Built-in extensions get `contentScripts.register` with `world: "MAIN"` + `runAt: "document_start"` + inline
  code (synchronous persona delivery) and `scripting.executeScript` (CSP-exempt Eruda); Mozilla-signed XPIs
  (uBlock Origin) install from AMO.

`privacy.resistFingerprinting` is never enabled: it would override UA, platform, pointer, touch and time zone.

## Repository layout

```
persona/     pure Kotlin/JVM: Identity, Region, Persona, Presets, PersonaToPrefs, ExpectedReport,
             UrlRewriteRules, UserscriptHeader + JUnit (goldens, tables)     -> docs/persona-model.md
app/         Android app (Kotlin + Compose, Android Components glue, Gecko runtime)
  assets/extension/   built-in MV2 WebExtension                              -> docs/extension.md
  assets/selfcheck/   generated at build time from harness/ (not in git)
harness/     detection harness (static page + probes + vendored detector libs) -> docs/detection-harness.md
personas/    preset JSON (source of truth) + golden/*.prefs.json (locked by a JVM test)
tool/        build_apk.sh, remote_build.sh, check_apk_size.sh, headless/{setup.sh,run.mjs,server.mjs,gen-expectations.mjs}
docs/        this folder
reports/     headless reports per milestone (reports/headless/m0/), device reports (reports/device/<version>/)
```

## Process model at runtime

```
┌──────────── app process (app.hatch.browser) ────────────────────────────────────────────┐
│ HatchApplication ── Components (lazy singletons)                                        │
│   AppSettings ─ PersonaManager ─ GeckoRuntimeFactory ─ GeckoEngine ─ BrowserStore       │
│                       │                 │                     │                         │
│                  GeckoPrefs      geckoview-config.yaml   EngineMiddleware, Download/    │
│          (GeckoPreferenceController)  (prefs + env TZ)  PromptMiddleware                │
│   BridgeExtension ◄── native ports "hatch" / "hatch-tab" ──► extension background/content│
│   BrowserIcons ◄── native port "MozacBrowserIcons" ──► icons@mozac.org (AC, favicons)     │
│   SiteRulesRepository (filesDir/rules.json)   SelfCheckController + LocalHttpServer      │
│   LoginsStorageProvider (SyncableLoginsStorage, key in AndroidKeyStore) ─ Gecko autofill │
│   SitePermissionsStorage (Gecko over Room)   ThumbnailStorage   sessionRestore (process) │
│   MainActivity (AppCompatActivity) ─ one EngineView ─ Compose UI: HatchTheme + toolbar   │
│     (AC toolbar store), find row, tab tray, menu sheet, settings, rules / logins overlays │
└──────────────────────────────────────────────────────────────────────────────────────────┘
   Gecko child processes (content, extension, GPU, socket) inherit env TZ from the yaml
┌──── :restart process ────┐  AppRestart.RestartActivity: kill old pid, relaunch MainActivity, exit
└──────────────────────────┘  (never touches Gecko; used only for TZ changes)
```

### Startup order

1. `HatchApplication.onCreate` (main process only) touches `rulesRepository` so the bundled example rules
   exist.
2. `MainActivity.onCreate` → `components.engine.createView` → `Components.engine` → `Components.runtime` →
   `GeckoRuntimeFactory.create`: writes `filesDir/geckoview-config.yaml` from the **active persona** (atomic
   rename), builds `GeckoRuntimeSettings` (`configFilePath`, `remoteDebuggingEnabled`, `extensionsProcessEnabled`,
   `locales`, `fontInflation(false)`, `automaticFontSizeAdjustment(false)`, `forceUserScalableEnabled(true)`,
   `inputAutoZoomEnabled(!desktop)`, `aboutConfigEnabled(true)`), `GeckoRuntime.create`. The first page load is
   therefore already correct, before any live API is used.
3. `Components.store` → `WebExtensionSupport.initialize` (extension popups/options as tabs, install prompts into
   the store) → `bridge.install()` → built-in extension installed; on its background port connect the app pushes
   the full state (persona shim config, rules, host rewrites minus the disabled hosts) → `icons.install()` (AC's
   `icons@mozac.org` favicon collector, a second built-in extension; see the decisions log).
4. `Components.sessionRestore` (once per **process**): `TabsUseCases.restore(SessionStorage)` on IO, then
   `AutoSave` (30 s in foreground, on background, on change). `MainActivity.ensureSession` joins it on every
   `onCreate`, processes the launch intent once per Activity instance (`TabIntentProcessor` for VIEW/SEND) and
   opens the home URL when the store is still empty.

### What Android Components does vs. what is hand-rolled

| Concern | Decision |
|---|---|
| Engine wrapper, store, session persistence, desktop-mode toggle | AC `GeckoEngine(context, DefaultSettings, runtime)`, `BrowserStore` + `EngineMiddleware`, `SessionStorage` + `AutoSave` |
| Tabs, popups → tabs, engine-view binding, back navigation | AC `TabsUseCases`, `WindowFeature`, `SessionFeature` |
| Fullscreen video, PiP | AC `FullScreenFeature`, `PictureInPictureFeature` |
| Alerts/auth/select/date/color, file upload incl. camera, beforeunload | AC `PromptFeature` (hence `AppCompatActivity`: the dialogs are `DialogFragment`s under the Compose UI) |
| Long-press context menu (the page's `contextmenu` handler runs first = right-click semantics) | AC `ContextMenuFeature` with `defaultCandidates` |
| Downloads (cookie-aware via `GeckoViewFetchClient`) | AC `DownloadsFeature` + `DownloadMiddleware` + `HatchDownloadService : AbstractFetchDownloadService` (foreground, `dataSync`) |
| Site permissions, `intent://` / app links, open-from-other-apps, error pages | AC `SitePermissionsFeature`, `AppLinksFeature` + `AppLinksInterceptor` (in `HatchRequestInterceptor`), `TabIntentProcessor`, `browser-errorpages` |
| Extension lifecycle (uBO popups/dashboard as tabs, permission prompts) | AC `WebExtensionSupport.initialize` + store `webExtensionPromptRequest` handled in Settings |
| Address bar (display/edit modes, inline autocomplete, progress, tab counter) | state and events: AC `compose-browser-toolbar`'s `BrowserToolbarStore` (`ToolbarGravity.Top`) driven by `ui/toolbar/HatchToolbarMiddleware`; autocomplete from open tabs + AC `browser-domains` `ShippedDomainsProvider`. Drawing: hand-rolled `DisplayToolbar` / `EditToolbar` (design pass), the edit field a port of AC 158 `InlineAutocompleteTextField` (MPL-2.0) |
| Tab thumbnails, favicons | AC `browser-thumbnails` (`BrowserThumbnails` + `ThumbnailsMiddleware`) and `browser-icons` (`BrowserIcons.install` = the `icons@mozac.org` built-in extension) |
| "Tab closed – Undo" | AC `UndoMiddleware` + `TabsUseCases.undo` |
| Tab tray (grid, swipe, multi-select, close all, predictive back) | hand-rolled Compose over four Fenix building blocks (`ui/tabs/*`, MPL-2.0, `THIRD_PARTY.md`) |
| Find in page | hand-rolled Compose row over `EngineSession.findAll/findNext/clearFindMatches`, drawn in the toolbar row instead of the toolbar while open |
| Saved logins (store, engine fill, save prompt) | AC `service-sync-logins` (`SyncableLoginsStorage`, SQLCipher key in `SecureAbove22Preferences`), `GeckoAutocompleteStorageDelegate` on the runtime, `PromptFeature` login parameters; the picker bar and the Saved logins screen are hand-rolled Compose (`ui/logins/*`) |
| Passkeys / WebAuthn | AC `feature-webauthn` (`WebAuthnFeature`, engine `ActivityDelegate`); inert until Google's allowlist entry lands |
| Site permissions storage + screen | AC `GeckoSitePermissionsStorage(OnDiskSitePermissionsStorage)` (what Fenix uses); the screen is hand-rolled (`ui/SitePermissionsScreen.kt`) |
| App links policy | AC `AppLinksFeature` / `AppLinksInterceptor` with `launchInApp` / `shouldPrompt` from `applinks/AppLinksPolicy` (Settings: stay / ask / always) |
| Menu, home content, site-info card, persona sheet, settings (root + sub-pages), self-check bar, rules list/editor/import, persona editor, saved-login detail, error page (`assets/errorpages/hatch_error.*` through AC `ErrorPages.createUrlEncodedErrorPage`) | hand-rolled Compose / HTML |
| `WebContent` composable | vendored from AC `compose-engine` (MPL-2.0, see `THIRD_PARTY.md`) and reduced to hosting the one Activity-owned `EngineView`; render/release belong to AC `SessionFeature` only |

Not used on purpose: `compose-tabstray` (a `LazyColumn` toy), `compose-menu` / `browser-menu2` as a UI (the
Fenix main menu; only pulled transitively), `compose-awesomebar` / `feature-awesomebar` / `browser-storage-sync`
(history and bookmarks are not in scope), `feature-toolbar` and `browser-toolbar` (View-based), `feature-top-sites`,
`feature-addons` (AMO catalog UI), `feature-webcompat` (its per-site UA interventions would fight personas),
`feature-findinpage` (View-based), `feature-pwa`, `feature-fxsuggest` / `service-firefox-accounts` (no sync).

## Persona pipeline

`Persona` → `PersonaToPrefs` (the single field→mechanism table) → three outputs applied by `PersonaManager`:
Gecko prefs (live, `GeckoPrefs`), per-session settings (UA override, desktop mode), and the shim config pushed to
the extension (`BridgeExtension.sendPersona`), plus the yaml rewrite for the next cold start. Details and the
table: `docs/persona-model.md`. Shim behaviour: `docs/extension.md`.

## Verification pipeline

The same harness page runs in three places (`docs/detection-harness.md`): headless Firefox 158 on the VPS
(`make headless`, prefs from the goldens, the real extension installed as a temporary add-on, negative controls
that must fail), the phone (Self-check screen: `LocalHttpServer` on 127.0.0.1 with a random token path serves
`assets/selfcheck/`, `/echo`, `/report`; the report zip = JSON + screenshot + meminfo), and a desktop browser for
manual poking. `summary.overall === "PASS"` iff no hard FAIL, no library FAIL and, on device, no device-scoped
SKIPPED.

## Upgrade checklist (AC/GeckoView train, every 2–3 trains)

1. Bump `mozilla` in `gradle/libs.versions.toml`; take the Kotlin and Compose BOM versions AC was built with
   (its POMs pin them); check AGP's `minCompileSdk` requirement.
2. Re-verify the Gecko facts the design rests on at the new tag: `GeckoPreferenceController` surface
   (`GeckoPrefs.kt` is the only caller), `DebugConfig` yaml keys, `contentScripts.register` `world: "MAIN"`,
   `rust_prepare_accept_languages` (header q-values), the `ui.*PointerCapabilities` / `dom.w3c_touch_events`
   prefs still exist.
3. Update the UA strings in `personas/identities.json` to the new Firefox major (and the Chrome major for
   `win-chrome`), regenerate goldens and expectations, bump `tool/headless/setup.sh` `FIREFOX_VERSION` and
   `REQUIRED_VERSION_PREFIX` in `run.mjs`.
4. `make check-full`; compare `build/harness-reports/*.json` with `reports/headless/<milestone>/`; run the
   device checklist.

## Decisions log (summary)

| Decision | Choice | Why |
|---|---|---|
| Engine | GeckoView 158 stable | engine-native, undetectable desktop forcing; pinned |
| UI | Kotlin + Compose, native | direct access to all Gecko/AC APIs; no Flutter wrapper exists for GeckoView |
| Live switches | `GeckoPreferenceController` + per-session settings | no restart except `TZ`; yaml + restart is the fallback |
| Persona delivery to pages | MV2 built-in extension, `contentScripts.register` MAIN world | only synchronous `document_start` path; MV3 `userScripts` is the documented fallback |
| Devtools | Eruda in-page + Gecko remote debugging over adb | GeckoView has no in-app toolbox |
| Testing | headless Firefox 158 on the VPS + in-app Self-check + 15-site audit | no emulator/device on the VPS |
| Build host | the 3.6 GB VPS with strict caps, optional remote NUC via `.buildhost` | Cory's call; same scripts on both |
| Name/package | `Hatch` / `app.hatch.browser` (namespace `app.hatch`) | open item; one-line rename |
| Native libs in the APK | `packaging.jniLibs.useLegacyPackaging = true` (compressed, extracted at install; what Fenix does for APK builds) | the AGP default stores `libxul.so` (153 MB) uncompressed and no release build could meet the APK budget (PERF-01); compressed the R8 release build measures 103.1 MB, so the budget is 110 MB; device footprint = APK + ~189 MB extracted |
| FileProvider scope | `cache/share/` and external `reports/` only; no `files-path` | `filesDir` holds the Gecko profile, `rules.json` and personas and must never become shareable through a future `getUriForFile` (SEC-6) |
| Popup sessions | the persona is applied in the parent's `EngineSession.Observer.onWindowRequest` (before GeckoView opens the child) and again when the tab is linked | AC's `onNewSession` copies only the UA from `DefaultSettings` and leaves `VIEWPORT_MODE_MOBILE` (HATCH-WC-05) |
| Touch input with `touch = false` | engine delivers the real input source; the shim masks trusted events to a mouse; the harness gates the device run on "a trusted tap arrived" and keeps `pointerType` soft | `dom.w3c_touch_events.enabled = 0` hides the TouchEvent surface but not the input source (HATCH-WC-02); gesture plumbing cost documented (HATCH-WC-06) with the "Keep touch events" setting as the escape |
| Address bar (M1) | **`compose-browser-toolbar` 158 adopted**, reversing the M0 exclusion | Cory's feedback ("doesn't look like regular Firefox"); it is the Fenix 158 toolbar (pill origin, edit mode with inline autocomplete, progress, tab counter, action slots) for ≈ 2.3 MB of AARs that R8 trims; the hand-rolled M0 bar had none of it. Its events are mapped in one middleware (`ui/toolbar/HatchToolbar.kt`). Design pass: only its store, actions and concept types stay (next row) |
| Toolbar drawing (design pass) | **Hand-rolled `DisplayToolbar` / `EditToolbar` over the same `BrowserToolbarStore`**; AC's `BrowserToolbar` composable is no longer called | AC 158's `BrowserToolbar` hard-codes a 64 dp row, a 48 dp pill without an outline, no composable action slots and a 5 dp gradient progress bar (`FullDisplayToolbar.kt:51,113`, `concept/Action.kt:15-170`, `BrowserEditToolbar.kt:96-98` at `FIREFOX_158_0_RELEASE`); the design needs 56 dp / 44 dp r22 / a 2 dp primary outline in edit mode / the persona chip / a 3 dp bar. The store, `HatchToolbarMiddleware`, autocomplete providers and `normalizeInput` are unchanged, so behaviour is AC's |
| Edit field (design pass) | **Port of AC 158 `compose/browser-toolbar/.../ui/InlineAutocompleteTextField.kt`** (`ui/toolbar/InlineAutocompleteTextField.kt`, MPL-2.0 header kept, `THIRD_PARTY.md`) | AC's field is `internal` (`InlineAutocompleteTextField.kt:122`) and only reachable through the fixed-size `BrowserEditToolbar`; porting it (only package, `textStyle` and colour parameters changed, each marked `Hatch:`) keeps AC's autocomplete transformations, IME wrappers and paste sanitizer verbatim. Re-diff it on every AC upgrade |
| Toolbar position (M1) | **Chrome-style top toolbar** (`ToolbarGravity.Top`); the M1 persona strip is gone (design pass: the persona chip at the start of the pill, the toolbar tint, the menu and the home cards carry it) | Cory's choice; bottom edge stays free for page content; Fenix's bottom-toolbar layouts are not used |
| Theme (design pass) | **Six fixed brand palettes** (`ui/theme/HatchPalette`: Graphite default, Phosphor, Indigo, Cobalt, Amber, Raspberry) × light/dark, Settings › Appearance (palette + System/Light/Dark); Geist + Geist Mono bundled (OFL); no dynamic colour, no Acorn | the design ("Signal") specifies exact tokens; dynamic colour would override them. The toolbar tint (primaryContainer for a desktop persona, surface for the real device) is the persona signal, so it must be stable. Mode changes go through `AppCompatDelegate.setDefaultNightMode` so AC's XML dialogs follow (they stay Graphite) |
| Settings IA (design pass) | root page with four groups (General, Desktop persona, Privacy & sites, More) whose rows open sub-pages (`SettingsPage`, a stack in `AppUiState`) or another module's screen; Self-check and Eruda moved to Settings › Developer (Eruda shows in the menu only when enabled there; Ctrl+Shift+I/P unchanged) | the one long column mixed everyday settings with diagnostics |
| Error page (design pass) | Hatch's own `assets/errorpages/hatch_error.{html,css,js}` through AC `ErrorPages.createUrlEncodedErrorPage`; "Try with persona off" is honoured only with the error page's one-time 128-bit token (`ErrorPageChannel.kt`) | AC's page could not be themed; a web page cannot read the error page's URL, so it cannot forge the link (`lastUri` is blanked for user-gesture loads, so it cannot be the check) |
| Tab tray (M1) | grid with thumbnails, built from four Fenix building blocks (MPL) | `compose-tabstray` is a toy; Fenix's tray is not extractable; `browser-thumbnails` + `browser-icons` supply the imagery |
| Scroll-to-hide toolbar (M1) | implemented (`DynamicToolbarController`, mirrors Fenix's `EngineViewClippingBehavior` for a top toolbar) but **default OFF** (`hideToolbarOnScroll`) | unvalidated on a device; the static toolbar is the reliable layout; a Settings switch turns it on |
| EngineView ownership (M1) | AC `SessionFeature` is the only caller of `render()` / `release()`; `WebContent` only hosts the view | M1 inventory risk 5: two owners raced on tab close |
| Session restore (M1) | process-scoped `Components.sessionRestore`, joined by every `MainActivity.onCreate` | M1 inventory risk 1: the `savedInstanceState == null` gate lost tabs after an Activity recreation in a fresh process |
| Second built-in extension (M1) | `browser-icons` installs `icons@mozac.org` (content script, isolated world, `document_end`, read-only `querySelectorAll` + one native message) | needed for favicons; verified tell-free in headless with the runner's `--extra-ext` flag (red-team 01/03/07/13 clean with it installed; see `docs/limitations.md`) |
| Credential autofill (M1) | Hatch's own logins store (Path 2 of `docs/research/google-signin-autofill.md`): `service-sync-logins` + the engine's `Autocomplete.StorageDelegate` + `PromptFeature` save/select prompts | Google Password Manager only fills web forms for browsers on its private package list; Chrome's account chooser (Mirror) cannot exist outside Chrome; FedCM is off in Gecko 158. The store key lives in AndroidKeyStore (`forceInsecure = false`, unlike Fenix release) and a lost key wipes the store instead of crashing |
| Passkeys (M1) | `WebAuthnFeature` + `CREDENTIAL_MANAGER_SET_ORIGIN` wired now; **blocked on Google's privileged-browser allowlist** (request for `app.hatch.browser` + release cert pending; debug builds can never be approved) | GeckoView 158 always uses the privileged GMS FIDO2 client; never shim `navigator.credentials` or disable WebAuthn (both detectable). Menu "Open in another browser" is the bridge |
| App links (M1) | default **Stay in Hatch** (`launchInApp = false`, `shouldPrompt = true`); "Ask" and "Always" are the Fenix behaviours | M1 inventory risk 3: `launchInApp = { true }` jumped into native apps, against the escape-hatch purpose |
| Overlay z-order and back chain (M1, design pass) | `HatchApp` draws Settings/Tabs → Saved logins → login picker bar → rules overlays (topmost; the menu and persona sheet are modal windows above all). Back and Esc share one chain, `AppUiState.onBack(rulesBack, loginsBack)`: site info → find → tray → menu → persona sheet → rules overlays → Saved logins (detail first) → Settings sub-page → Settings → URL edit → self-check; every `UiEvent` has exactly one handler in `HatchApp` | three modules' overlays must agree on who closes first; the rules screens open above Settings on purpose. URL edit and the find row share the toolbar row and are kept exclusive |

## M1 module notes

### Browser chrome (chrome, 2026-10-18)

- **`compose-browser-toolbar` 158 is adopted** (reverses the M0 exclusion; the AC table and the "Not used on
  purpose" list above are current). `ui/toolbar/HatchToolbar.kt` owns a `BrowserToolbarStore`
  (`ToolbarGravity.Top`) and a middleware that maps every `BrowserToolbarEvent` to use cases and keeps
  URL / progress / security badge / tab count / desktop flag in sync from the `BrowserStore`; edit mode has
  inline autocomplete (open tabs, then `ShippedDomainsProvider`) and `CommitUrl` goes through
  `normalizeInput`. (Design pass: the store stays, the drawing is Hatch's own, see "Design pass" below.)
- **Single owner of EngineView render/release**: AC `SessionFeature` (`EngineViewPresenter`). `WebContent`
  only hosts the view (risk 5). `BrowserScreen` is a `SubcomposeLayout` so the `AndroidView` node is stable
  across static/dynamic toolbar layouts and fullscreen.
- **Session restore + autosave are process-scoped** (`Components.sessionRestore`, run on every Activity
  start; risk 1). The launch intent is processed once per Activity instance.
- Tab tray (`ui/tabs/*`, Fenix building blocks under MPL), thumbnails (`browser-thumbnails`:
  `BrowserThumbnails` feature + `ThumbnailsMiddleware`), favicons (`browser-icons`, installs the
  `icons@mozac.org` built-in content-script extension), `UndoMiddleware` for "Tab closed – UNDO", find bar
  (`ui/find`, engine `findAll/findNext/clearFindMatches`), menu sheet, home content, site-info dialog,
  crash state (`CrashAction.RestoreCrashedSessionAction`).
- Scroll-to-hide (`ui/toolbar/DynamicToolbarController.kt`) is behind `AppSettings.hideToolbarOnScroll`,
  **default OFF**; it mirrors Fenix's `EngineViewClippingBehavior` for a top toolbar
  (`setDynamicToolbarMaxHeight(h)`, engine view translated by `h - hidden`, `setVerticalClipping(-hidden)`).
- Keyboard: `ui/keys/KeyShortcuts.kt` (JVM-tested). Esc / F5 / Ctrl+R / Ctrl+L reach the page unless a
  Hatch surface is open or a page input is focused (risk 6); Ctrl+F, Ctrl+Tab/Shift+Tab, Ctrl+1..9,
  Alt+←/→ added. Back order (design pass, `AppUiState.onBack`): site info → find → tray → menu → persona
  sheet → rules overlays → Saved logins → settings page → settings → URL edit → self-check → page history (the tray also has its own `PredictiveBackHandler` for the
  gesture animation). Esc follows the same order through `HatchApp.closeTopSurface()`.

### Credentials (credentials, 2026-10-18)

- `logins/LoginsStorageProvider.kt` owns `SyncableLoginsStorage(context, lazy SecureAbove22Preferences)`
  (prefs `hatch_logins_key`, `forceInsecure = false`), `DefaultLoginValidationDelegate`,
  `GeckoLoginStorageDelegate` and the `GeckoAutocompleteStorageDelegate` that `GeckoRuntimeFactory.create` installs
  on the runtime right after `GeckoRuntime.create` (Fenix `GeckoProvider.kt:73-87`); credit cards / addresses have
  a no-op delegate. `DefaultSettings.loginAutofillEnabled` and the Settings switch keep the engine's silent
  single-match fill in step with `AppSettings.loginAutofill`.
- `PromptFeature` gets `loginValidationDelegate`, `isLoginAutofillEnabled`, `isSaveLoginEnabled`,
  `loginExceptionStorage` (`logins/LoginExceptionStore.kt`, SharedPreferences) and `loginDelegate` whose
  `loginPickerView` is `logins/LoginPickerState.kt` (`AutocompletePrompt<Login>` as Compose state) rendered by
  `ui/logins/LoginPickerBar.kt`; "Manage logins" emits `UiEvent.OpenSavedLogins`. `ui/logins/SavedLoginsScreen.kt`
  (list / search / reveal / copy / delete / add) sits behind `ui/logins/DeviceAuth.kt` (`BiometricPrompt`
  `BIOMETRIC_WEAK | DEVICE_CREDENTIAL`, keyguard fallback, straight through without a lock screen).
- `WebAuthnFeature` is installed unconditionally in `MainActivity.installFeatures`; `onActivityResult` routes to it
  after `PromptFeature`. Manifest: `USE_BIOMETRIC`, `CREDENTIAL_MANAGER_SET_ORIGIN`,
  `CREDENTIAL_MANAGER_QUERY_CANDIDATE_CREDENTIALS`, plus the `APP_BROWSER`, typed-HTML, `content:`/`file:` HTML
  and `content:` PDF intent filters.
- `notificationsDelegate.bindToActivity(this)` in `onCreate` so AC's `FetchDownloadManager` can ask for
  `POST_NOTIFICATIONS` at the first download (API 33+). R8: `-keep class mozilla.appservices.** { *; }` (Fenix's
  rule; load-bearing because Hatch obfuscates) and `org.mozilla.gecko.util.DebugConfig`.

### Rules, permissions, app links, persona editor (rules, 2026-10-18)

- Screens live in `ui/rules/` (`SiteRulesScreen`, `RuleEditorScreen`, `ImportRuleDialog`, `RulesOverlays`),
  `ui/persona/PersonaEditorScreen.kt` and `ui/SitePermissionsScreen.kt`; the pure logic behind them is JVM-tested:
  `rules/RuleValidation.kt` (match-pattern grammar, rule problems), `rules/UserscriptImport.kt` (`.user.js` /
  `.user.css` → `SiteRule`, SEC refusals), `rules/RulesUiState.kt` (overlay stack), `applinks/AppLinksPolicy.kt`
  (mode → `launchInApp` / `shouldPrompt`), `hostrewrites/HostRewriteSettings.kt` (per-host filter),
  `sitepermissions/SitePermissionRows.kt`, `ui/persona/PersonaDrafts.kt` (editor validation).
- Overlay model: `Components.rulesUi` (`RulesUiState`, a stack) is rendered by `RulesOverlays` **above** the chrome's
  overlays and the Saved logins screen in `HatchApp`, so "Site rules…" from Settings opens on top of Settings and
  back returns there. The Activity back callback asks `rulesUi.onBack()` first; `HatchApp`'s event `when` routes
  `OpenSiteRules` / `OpenSitePermissions` / `OpenPersonaEditor` to `rulesUi.show(...)` and Esc to
  `closeTopSurface()` (rules → logins → chrome).
- Rules flow unchanged: `SiteRulesRepository` (persist) → `onChanged` → `BridgeExtension.sendRules` → one
  `contentScripts.register` per enabled rule. "Test on current site" saves the rule enabled, closes the overlays and
  reloads the selected tab after 500 ms (the extension registers rules on a serialised promise chain).
- Host rewrites: `Components.effectiveHostRewrites()` = `UrlRewriteRules.extensionHostRewrites` minus
  `AppSettings.disabledHostRewrites`; it feeds the `state` push at port connect and `sendHostRewrites` on every toggle.
  The wire contract is untouched (no disabled flag; `docs/extension.md`).
- Site permissions: `Components.sitePermissionsStorage` (`GeckoSitePermissionsStorage` over
  `OnDiskSitePermissionsStorage`, Fenix `Core.kt:306-307`) is passed to `SitePermissionsFeature(storage = …)`
  (`SitePermissionsFeature.kt:110`) and read by the screen. DAO calls are blocking → the screen runs storage calls
  on `Dispatchers.IO`; the Gecko half hops to Main itself.
- App links: `AppSettings.appLinksMode` (`stay` default / `ask` / `always`) → `AppLinksPolicy` →
  `AppLinksFeature(launchInApp, shouldPrompt)` (`AppLinksFeature.kt:99,105`) and
  `AppLinksInterceptor(launchInApp)` (`AppLinksInterceptor.kt:66`, constructed in `HatchRequestInterceptor` with a
  default lambda reading the setting live). Fenix's `Settings.shouldOpenLinksInApp/shouldPromptOpenLinksInApp`
  (`utils/Settings.kt:2047-2065`) is the reference mapping; Hatch's default is "never" (m1-inventory risk 3).
- Persona editor: presets read-only (`PersonaManager.saveIdentity/saveRegion` reject preset ids; "Duplicate" copies
  into a custom id from `PersonaDrafts.uniqueId`); saving or deleting the active identity/region re-applies the
  persona through the same `applyPersona` path as the sheet (restart snackbar for a time-zone change).

## Design pass (2026-10-21)

The "Signal" design (`docs/design/hatch-screens.dc.html`, 22 mockups + spec) is implemented in four areas:

- **Theme** (`ui/theme/`): `HatchPalette` (6 × light/dark), `HatchColors` (the design's token names: `tb`, `pl`,
  `acT`, `invAc`, `ok`, `ln`, `ln2`, …, CompositionLocal) mapped onto all 48 M3 roles, `HatchTypography` (Geist),
  `HatchType.mono` (Geist Mono, machine values only), `HatchShapes` / `HatchDimens` / `HatchMotion`
  (`tintCrossfade()` = 200 ms emphasized-decelerate), `Modifier.hatchFocusRing`. `HatchTheme(palette, mode)`;
  `HatchStaticTheme` (Graphite, no settings) for the crash screen. Nothing outside `ui/theme` reads
  `isSystemInDarkTheme()`; use `LocalIsDark` / `HatchTheme.isDark`.
- **Toolbar** (`ui/toolbar/`): `DisplayToolbar` (56 dp row, 44 dp r22 pill, 36 dp persona chip: security glyph |
  device glyph, tap → site info; host, long-press → Copy / Paste / Paste & go; tab counter; overflow; 3 dp
  progress) and `EditToolbar` (2 dp primary outline, ported `InlineAutocompleteTextField`), both over the AC
  store. Find in page replaces the toolbar in the same row. The status-bar strip takes the toolbar tint.
- **Surfaces**: home (Desktop / Phone cards, chips, "Recently open" grid), site-info card anchored under the
  toolbar, content-crashed state, login picker bar, tab tray (selection mode, empty state, FAB), main menu
  (Desktop/Phone segmented control, quick actions, grouped list), the HTML error page.
- **Screens** (`ui/settings/`, `ui/persona/`, `ui/rules/`, `ui/logins/`, `crash/`): Settings root + sub-pages
  (`ScreenKit.kt` holds the shared top bar, grouped cards, rows, fields, pills, FABs), persona sheet / editor with
  field-keyed validation (`PersonaDrafts.identityFieldProblems`), site rules / rule editor, site permissions with
  Allow / Ask / Block pills, Saved logins + the new detail page, crash recovery.
- **Renders**: `app/src/screenshots/kotlin/app/hatch/screenshots/DesignScreenshotTest.kt` (Paparazzi
  2.0.0-alpha05.1, 360 × 760 dp at 420 dpi) renders every mockup in Graphite light / dark and Phosphor light.
  Its plugin and source dir are only added for Paparazzi tasks (or `-Phatch.snapshots=true`), so
  `:app:testDebugUnitTest` and CI never see them; run `tool/remote_build.sh :app:recordPaparazziDebug
  --no-configuration-cache` (Paparazzi's resource task cannot be stored in the configuration cache next to the
  generated self-check assets); the PNGs come back to `app/build/paparazzi-snapshots/images/`.
  Tall pages (Settings root, Developer) render at 360 × 1320 dp; an accessibility set renders key screens at 200 %
  font scale, in RTL and in landscape (`*__a11y-*`). `tool/remote_build.sh` never falls back to a local build for a
  Paparazzi run (`tool/check_remote_build_guard.sh` checks it).

### Design-review fixes (2026-10-21)

Decisions made while closing the design-fidelity, accessibility and regression reviews:

| Area | Decision |
|---|---|
| Touch targets vs mockup widths | The toolbar's tab counter / overflow and the find row's buttons keep 48 dp targets; the pills are 16 dp (display / edit) and 12 dp (find) narrower than the mockups (`ToolbarTouchTargets`). Segmented controls keep the mockup heights (40 / 36 dp) and rely on hit expansion inside their track. Persona-sheet chips keep 48 dp targets (48 dp row pitch, not 42) |
| Focus ring | Every Hatch-drawn focus target carries `Modifier.hatchFocusRing` (card rows draw it 2 dp *inside*, `inside = true`, because the r20 card clips); `DesignReviewGuardsTest` fails on a `clickable` / `selectable` / `toggleable` / `combinedClickable` or `IconButton` without one |
| Switch rows | `toggleable(role = Switch)`, never `selectable` (TalkBack On/Off instead of "Selected") |
| Overlays | Settings, the tray, Saved logins, the rules screens and the site-info card are `Modifier.overlayPane(title)` roots: pane title for TalkBack, focus moves in on open, horizontal safe-drawing insets. While a full-screen overlay is open the browser is `coveredByOverlay` (no semantics, focus cannot enter) and the EngineView is `NO_HIDE_DESCENDANTS` |
| Back / Esc | The Settings search lives in `AppUiState.settingsSearch`; back pops a page, then closes the search, then Settings. No Compose `BackHandler` in `ui/settings` |
| Add-on prompts | `AddonPromptHost` (in `AddonsPage.kt`) is composed once in `HatchApp`, so install / permission prompts are answered wherever the user is; M1 only answered them while Settings was open, the first design build only on the Add-ons page |
| `prefers-color-scheme` | Pages see the **system** dark setting, as in M1: `engine/PageColorScheme` pins `GeckoRuntimeSettings.preferredColorScheme` to LIGHT / DARK from `Resources.getSystem()` and refreshes it on configuration changes, because with `COLOR_SCHEME_SYSTEM` GeckoView forwards the Activity's (AppCompat-overridden) uiMode on every rotation (GeckoView.java:788-797 → GeckoRuntime.java:1042-1045 → GeckoSystemStateListener.java:222-230; `usesDarkTheme`, GeckoRuntime.java:884-893) |
| Hosts shown | `engine/UrlHost` is the one parser for displayed hosts (toolbar, error page, open-tab autocomplete): authority ends at `/ \ ? #`, userinfo up to the last `@`, IPv6 brackets kept. Autocomplete still skips userinfo URLs |
| Saved-login titles | The registrable domain from AC's `lib-publicsuffixlist` 158 (`PublicSuffixList.getPublicSuffixPlusOne`, already in the APK through support-ktx; now declared); the full origin stays in the mono subtitle |
| Snackbar action colour | `invAc` = the design's value raised to 4.5:1 on `inv` (`Contrast.atLeast`); only Cobalt dark and Amber dark change |
| Form fields | Unfocused outline uses the `outline` token (`#85858F` / `#6E6E78`, ≥ 3:1), placeholders full-alpha `mu`; chips keep the design's `ln2` outline (their text labels identify them) |
| Error page type | Latin subsets of Geist 400/500/600 and Geist Mono 500 as woff2 in `assets/errorpages/fonts/` (65 KB), system fonts as fallback |
| Additions beyond the mockups | Rule editor: the Exclude patterns row, the page-world note and "Delete rule" (M1 functions the mockup does not draw). Developer: user-branch prefs carry a `*` (M1's `[user]` / `[default]`), explained under the card; the mockup's prefs have no marker. Site permissions: the mockup's "Storage" row is labelled "Persistent storage" (`LOCAL_STORAGE`); the remaining Gecko permissions follow the mockup's five rows. Persona editor rows: one-line `PersonaLabels.short` titles with the preset's parenthetical as a note line |
| Directional glyphs | Back, Forward and chevron-right `ImageVector`s are `autoMirror` |

