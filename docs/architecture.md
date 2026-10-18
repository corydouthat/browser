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
│   SiteRulesRepository (filesDir/rules.json)   SelfCheckController + LocalHttpServer      │
│   MainActivity (AppCompatActivity) ─ one EngineView ─ Compose UI (BrowserScreen, sheets)│
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
   the full state (persona shim config, rules, host rewrites).
4. `restoreOrOpenFirstTab`: `SessionStorage` restore + `AutoSave` (30 s in foreground, on background, on change),
   intent processing (`TabIntentProcessor` for VIEW/SEND), else the home URL.

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
| Toolbar, persona sheet, settings, self-check bar, rules (M1 editor), add-on list | hand-rolled Compose |
| `WebContent` composable | vendored 40 lines from AC `compose-engine` (MPL-2.0, see `THIRD_PARTY.md`) with one change: the `EngineView` is created once by the Activity and keyed on the selected tab, never recreated on tab switch |

Not used on purpose: `compose-browser-toolbar` (drags menu2/cfr/tabcounter in), `feature-addons` (AMO catalog UI),
`feature-webcompat` (its per-site UA interventions would fight personas), `feature-findinpage` (View-based;
find-in-page is hand-rolled in M1), `feature-pwa`.

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
