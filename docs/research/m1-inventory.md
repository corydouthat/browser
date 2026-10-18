# M1 inventory of the app module (2026-10-18, after M0)

Read-only audit of `app/src/main/kotlin/app/hatch/` against the M1 list in `docs/roadmap.md`. Paths are
relative to that package unless noted.

| Item | Status | Evidence | Notes |
|---|---|---|---|
| Tabs: sheet | MISSING | BrowserScreen.kt:226-238 | Only "New tab" / "Close tab (N open)". No tray, no switch action; a background or popup tab can never be selected. |
| Tabs: restore | DONE (bug) | MainActivity.kt:99-101, 202-217; Components.kt:93 | `TabsUseCases.restore(SessionStorage)` + `autoSave`, but only when `savedInstanceState == null` (see risk 1). |
| Tabs: crash recovery | MISSING | WebContent.kt:38 | `engineState.crashed` only re-renders. No crash UI, no `CrashAction.RestoreCrashedSession`. |
| Prompts | DONE | MainActivity.kt:111-123 | `PromptFeature` + `PromptMiddleware`; back press, permission and activity results routed. |
| File upload with camera | PARTIAL | MainActivity.kt:117,120; AndroidManifest.xml:12,21 | Through PromptFeature + `FileUploadsDirCleaner`; unverified on device. |
| Downloads foreground service | DONE (unverified) | MainActivity.kt:140-156; downloads/HatchDownloadService.kt:17; AndroidManifest.xml:73-76 | `DownloadsFeature` + `FetchDownloadManager` + `DownloadMiddleware`; service `foregroundServiceType=dataSync`. |
| Fullscreen | DONE | MainActivity.kt:157-165, 234-251 | `FullScreenFeature`; hides toolbar (BrowserScreen.kt:76,85). |
| PiP | DONE (minimal) | MainActivity.kt:63,199,253-261 | `PictureInPictureFeature` called from `onUserLeaveHint` and the PiP callback. |
| Find in page | MISSING | — | No composable, no Ctrl+F; engine `findAll` unused. |
| Context menu | DONE | MainActivity.kt:124-139 | `ContextMenuFeature` default candidates. |
| App links / open from other apps | DONE | MainActivity.kt:189-198, 219-230; engine/HatchRequestInterceptor.kt:19-39; AndroidManifest.xml:51-62 | `AppLinksFeature` + `AppLinksInterceptor`, both `launchInApp = { true }` (risk 3). |
| Error pages | DONE | engine/HatchRequestInterceptor.kt:41-42 | |
| Site permissions | PARTIAL | MainActivity.kt:166-188 | ASK for camera/location/mic/notifications/storage; no screen to view or revoke. |
| Hardware keyboard shortcuts | PARTIAL | MainActivity.kt:288-306; ui/UiEvents.kt:7-16; ui/HatchApp.kt:71-93 | Present: Ctrl+T/W/L/R, F5, Esc, Ctrl+Shift+I, Ctrl+Shift+P. Missing: Ctrl+F, Ctrl+Tab, Ctrl+1..9, Alt+Left/Right. |
| Region switching | DONE (UI) | ui/PersonaSheet.kt:95-98,142-158; persona/PersonaManager.kt:145-178 | TZ change → restart via persona/AppRestart.kt. |
| Geolocation | PARTIAL | assets/extension/shim/persona-shim.js:371-414 | JS shim only; unverified on a maps site. |
| Persona chip | DONE | BrowserScreen.kt:241-246 | |
| One-tap force desktop | DONE | BrowserScreen.kt:105-108,230-233; PersonaSheet.kt:67-86 | |
| Rules screen + editor | MISSING | rules/SiteRulesRepository.kt:51-63 | Repository has upsert/delete/setEnabled/replaceAll; no UI. |
| GM subset | DONE | assets/extension/gm/gm-shim.js:48-128 | `GM_registerMenuCommand` no-op; no `GM_xmlhttpRequest`. |
| 3 bundled example rules | DONE | rules/SiteRulesRepository.kt:93-161; HatchApplication.kt:16 | Ship disabled; no UI to enable. |
| Host rewrites with loop guard | PARTIAL | assets/extension/background.js:35,229-251; Components.kt:118 | Guard exists; no per-host disable UI; `BridgeExtension.sendHostRewrites` (ext/BridgeExtension.kt:128) never called. |
| Settings: build-info footer | DONE | ui/SettingsScreen.kt:166-170 | |
| Settings: engine prefs truth view | DONE | ui/SettingsScreen.kt:154-155,352-371 | |
| Release keystore | DONE 2026-10-18 | app/build.gradle.kts:57-77 | `key.properties` + `hatch-release.jks` on the VPS (git-ignored), backed up to nuc-main `~/backup/hatch/`. |
| Site audit 13/15 | NOT IN CODE | docs/device-checklist.md | Manual; Cory tests ad hoc instead. |
| Security findings | DONE in M0 | docs/limitations.md:66-79 | |

## Compose screens

`HatchApp` (ui/HatchApp.kt:43; `AppUiState` :27 handles back), `BrowserScreen` (ui/BrowserScreen.kt:63),
private `Toolbar` (:159), `WebContent` (ui/WebContent.kt:24, vendored), `PersonaSheet` (ui/PersonaSheet.kt:44),
`SettingsScreen` (ui/SettingsScreen.kt:61; `AddonsSection` :209, `PermissionDialog` :336,
`EnginePrefsSection` :352), `SelfCheckBar` (ui/SelfCheckBar.kt:31), `HatchTheme` (ui/Theme.kt:14).

## Android Components features and lifecycle

MainActivity.kt:104-200 wraps in `ViewBoundFeatureWrapper` (owner = Activity, view = `android.R.id.content`):
SessionFeature, WindowFeature, PromptFeature, ContextMenuFeature, DownloadsFeature, FullScreenFeature,
SitePermissionsFeature, AppLinksFeature. `PictureInPictureFeature` unbound (fine). `LifecycleObserver(
engineView)` at MainActivity.kt:71. Process-wide in Components.kt: GeckoEngine, BrowserStore (Download,
Prompt, Engine middleware), `WebExtensionSupport.initialize(openPopupInTab = true)` (:86), SessionStorage,
use cases, NotificationsDelegate, FileUploadsDirCleaner, GeckoViewFetchClient. App-lifetime store scopes:
BridgeExtension.kt:102, PersonaManager.kt:106.

Popups: `WindowFeature(store, tabsUseCases)` (MainActivity.kt:110) + `supportMultipleWindows = true`
(Components.kt:57); PersonaManager.kt:121-127 applies persona to child sessions.

## Risks found (all addressed in M1)

1. **Tabs lost after process death**: `restoreOrOpenFirstTab` runs only when `savedInstanceState == null`
   (MainActivity.kt:99). After Android recreates the Activity the store is empty, nothing is restored,
   autosave is not registered, and the next autosave may overwrite the saved session. Move restore +
   autosave to process start (Application/Components) or run whenever the store is empty.
2. **No tab switching** (BrowserScreen.kt, UiEvents.kt).
3. **App links always open the other app**: `launchInApp = { true }` in both places plus
   `interceptsAppInitiatedRequests = true`; YouTube/Reddit links jump into native apps, against the
   escape-hatch purpose. Needs a setting (default: stay in Hatch, ask on user-initiated).
4. **`POST_NOTIFICATIONS` never requested** at runtime (API 33+): download notifications may be hidden.
5. **Possible double rendering**: `SessionFeature` (MainActivity.kt:107) and `WebContent`
   (WebContent.kt:47-63) both render/release the same EngineView.
6. **Esc and F5 always swallowed** (`dispatchKeyEvent` MainActivity.kt:297,300); pages never see Escape.
   Ctrl+R/Ctrl+L also fire while typing in page inputs.
7. **dataSync foreground-service limit** (6 h on API 35+) for large downloads.
8. **Dead code awaiting UI**: `BridgeExtension.sendHostRewrites`, `hasContentPort`, `isConnected`,
   `SiteRulesRepository` mutators, `PersonaManager.saveIdentity/saveRegion/delete*`.
9. **Geolocation vs SitePermissions**: the shim fakes position while `SitePermissionsFeature` still asks
   for real location; interaction unverified.
10. Manifest: VIEW filter has http/https only (no `content:`/`file:`/html mime); no `WEB_SEARCH`,
    `PROCESS_TEXT` or ASSIST filters.
