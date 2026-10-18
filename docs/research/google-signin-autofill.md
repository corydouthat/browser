# Google sign-in UX and credential autofill (research, 2026-10-18)

Why: Cory's first-use feedback: "no system pop-up for Google account login"; nothing offered his saved
credentials. Verified at tag `FIREFOX_158_0_RELEASE` unless marked unverified.

## Chrome's account popup cannot exist in Hatch

Chrome-on-Android's account chooser on `accounts.google.com` is **Mirror account consistency**, a private
Chrome↔Google protocol, not a web API: Chrome adds `X-Chrome-Connected` on Google domains
(`components/signin/core/browser/chrome_connected_header_helper.cc:180-185`), Gaia answers with
`X-Chrome-Manage-Accounts`, and `chrome/browser/signin/chrome_signin_helper.cc:373-452` opens Chrome's own
account-picker bottom sheet, which mints web cookies via GMS with Chrome's OAuth client. None of it is
reachable from another browser; Fenix shows nothing comparable (no Mirror / credential-management code
anywhere under `mobile/android`). Google sign-in in Fenix and Hatch is the plain web form.

FedCM / One Tap: `dom.security.credentialmanagement.identity.enabled` is `false` at 158
(`StaticPrefList.yaml:5201`) and not overridden for GeckoView, so `navigator.credentials.get({identity})`
does not exist. Android Components already ships the prompt UI (`PromptRequest.IdentityCredential.*`,
`PromptFeature.kt:860-862, 1264-1285`), so the pref could be flipped, but Mozilla has not shipped it
anywhere; not for M1. Google's GIS docs list Firefox/Android as supported for the button and One Tap and
say the button falls back to the non-FedCM flow.

"Sign in with Google" on third-party sites uses `window.open` or redirect. Hatch handles it:
`Components.kt:57 supportMultipleWindows = true`, `MainActivity.kt:110` installs `WindowFeature`
(`WindowRequest.OPEN` → new tab with `parentId`, `CLOSE` consumed), `PersonaManager.kt:121-127` applies the
persona to the child window. Nothing in Hatch blocks Google sign-in. Device risks (unverified): Google's
"This browser or app may not be secure" heuristic (likeliest with `win-chrome`: Chrome UA on Gecko; the
default `win-firefox` is a real Firefox UA on a real Gecko), and the popup tab landing behind UI.

## Path 1: Android Autofill framework + Google Password Manager (already live, probably refused)

- `GeckoView.java`: `mAutofillEnabled = true` by default (`:104`); `setSession` installs
  `AndroidAutofillDelegate` (`:637-638`); `onProvideAutofillVirtualStructure` (`:1017-1027`),
  `autofill(SparseArray)` (`:1030-1047`); `Autofill.java:499` sets `setWebDomain(origin)` and
  `AUTOFILL_HINT_USERNAME/PASSWORD/EMAIL_ADDRESS` (`:545-594`).
- Android Components `GeckoEngineView.kt:68-70` sets `IMPORTANT_FOR_AUTOFILL_YES`. Fenix never calls
  `setAutofillEnabled`. Compose UI 1.12.1 (`AndroidComposeView.android.kt:2485-2496`) lets hosted
  `AndroidView` children provide autofill structure, so Hatch's `WebContent.kt:44-47` path is already live.
- **The catch**: a browser's reported domain is untrusted (`AssistStructure.ViewNode.getWebDomain()`
  javadoc; `AutofillService.java:302-345` "Web security"). Password managers keep hard-coded browser package
  lists; Mozilla bug 1538385 closed with "each app that uses GV will need to get itself whitelisted by the
  password manager vendors". Google's list is unpublished. Expected GPM behaviour for `app.hatch.browser`:
  no web suggestions or package-keyed suggestions, which matches what Cory saw. Bitwarden reads `webDomain`
  from any package, so Bitwarden/1Password-class services should work today (unverified on device).

## Path 2: in-app login store (what Fenix does)

- Engine: `GeckoRuntime.setAutocompleteStorageDelegate(Autocomplete.StorageDelegate)`; GeckoView calls
  `onLoginFetch(domain)` on login forms, autofills a single match (`signon.autofillForms`,
  `GeckoRuntimeSettings.loginAutofillEnabled`), otherwise raises `PromptDelegate.onLoginSelect`; submissions
  raise `onLoginSave` (`Autocomplete.java:40-105`). Works on every site including Google's two-step form,
  independent of any system allowlist.
- Fenix 158 wiring: `fenix/gecko/GeckoProvider.kt:75-87` (`GeckoAutocompleteStorageDelegate(
  creditCardsDelegate, GeckoLoginStorageDelegate(loginStorage, isLoginAutofillEnabled), scope)`),
  `Core.kt:182` (`DefaultSettings(loginAutofillEnabled)`), `Core.kt:537`
  (`SyncableLoginsStorage(context, lazySecurePrefs)`), `BaseBrowserFragment.kt:1048-1101` (`PromptFeature(
  loginValidationDelegate = DefaultLoginValidationDelegate(lazyPasswordsStorage), isLoginAutofillEnabled,
  isSaveLoginEnabled, loginExceptionStorage, loginDelegate.loginPickerView = LoginSelectBar)`).
- Hatch today: `PromptFeature` at `MainActivity.kt:111-123` passes none of these;
  `GeckoRuntimeFactory.kt:46-58` sets no storage delegate. The engine's own fill is inert.
- Dependencies: `org.mozilla.components:service-sync-logins:158.0` → `appservices:logins`, `sync15`,
  `db-crypto`, `lib-dataprotect` (AndroidKeyStore-backed `SecureAbove22Preferences`), `concept-sync`,
  `androidx.work`, `glean` (already on the classpath via remotesettings). The Rust native code is in
  `org.mozilla.appservices:full-megazord`, which Hatch already pulls through `feature-search`, so the add
  is Kotlin only (~300 KB of AARs). The `glean-native` capability rule in `app/build.gradle.kts` already
  covers it. `browser-storage-sync` (history/bookmarks) is not needed.
- UI: `LoginSelectBar` is a classic View in `feature-prompts` (host under Compose, or write a small Compose
  `LoginPickerView`); `SaveLoginDialogFragment` comes free once `isSaveLoginEnabled` and the validation
  delegate are set; a "Saved logins" screen (list/delete, gated by device credential/biometric like Fenix).
- Effort ~2-3 days. Risks: KeyStore unlock for `SecureAbove22Preferences`; R8 keep rules for JNA/uniffi
  (Fenix's proguard); WorkManager initializer from `androidx.work`. Credentials live in `filesDir`
  (covered by the SEC-6 FileProvider rule).

## Decision for M1

1. Keep the Android Autofill path as is; the device checklist records what GPM, Bitwarden/1Password offer.
   `docs/limitations.md`: GPM web-domain fill depends on Google's private browser list; use a third-party
   autofill service or Hatch's own store.
2. Implement Path 2 (in-app logins store + engine prompts + Compose picker + saved-logins screen).
3. Do not chase Chrome's account chooser (impossible) or enable FedCM.
4. Device checklist: Google sign-in on `win-firefox` and `win-chrome` (watch for "browser may not be
   secure"), GIS popup flow on a third-party site (popup tab opens, returns, closes).

Unverified: GPM behaviour for unknown packages; One Tap under GeckoView cookie defaults; Google's
"not secure" heuristic against Hatch's personas; Compose autofill hosting on device.
