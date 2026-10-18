# Passkeys / WebAuthn in GeckoView 158 (research, 2026-10-18)

Why: Cory tried a passkey login in the first Hatch build (debug-signed, `app.hatch.browser.debug`) and it
failed. Everything below was verified against the sources at tag `FIREFOX_158_0_RELEASE` and the shipped
`geckoview-omni-158.0.20261007201254.aar` unless marked unverified.

## How GeckoView 158 does WebAuthn

- `dom/webauthn/WebAuthnService.h:43-44` makes `AndroidWebAuthnService` the platform service on Android; the
  Rust `authrs` service is used only when `security.webauth.webauthn_enable_softtoken` is true.
- `AndroidWebAuthnService.cpp:196` and `:300-310` call Java
  `org.mozilla.geckoview.WebAuthnTokenManager.webAuthnMakeCredential/webAuthnGetAssertion`.
- Two backends, tried in order (`WebAuthnTokenManager.java:345-370`, `:547-600`):
  1. **Framework Credential Manager** (`android.credentials.CredentialManager`, API 34+). It always sets
     `origin`, which AOSP guards with `android.permission.CREDENTIAL_MANAGER_SET_ORIGIN`
     (`CredentialManagerService.java:604-606` → `SecurityException`). The permission is `normal`, but the
     GeckoView AAR manifest does **not** declare it; Fenix declares it itself
     (`fenix/app/src/main/AndroidManifest.xml:50-52`). On the exception Gecko falls through to GMS.
  2. **Play Services FIDO2.** `BuildConfig.MOZILLA_OFFICIAL ? Fido.getFido2PrivilegedApiClient(...) :
     Fido.getFido2ApiClient(...)` (`:201-224`). The shipped AAR has `MOZILLA_OFFICIAL = true` (javap of
     `org/mozilla/geckoview/BuildConfig.class`), so GeckoView **always calls the privileged API**, which takes
     an arbitrary `origin` and which Google refuses for callers not on its allowlist. Source comment:
     "Third party apps will need to get whitelisted themselves."
- Dependency: the `geckoview-omni` POM declares `com.google.android.gms:play-services-fido:21.3.1`; it is in
  Hatch's APK already.
- A successful GMS call returns a `PendingIntent` that Gecko hands to
  `GeckoRuntime.ActivityDelegate.onStartActivityForResult` (`GeckoRuntime.java:945-984`). Android Components
  wraps this as `Engine.registerActivityDelegate` (`GeckoEngine.kt:912-926`) and ships
  `feature-webauthn` (`WebAuthnFeature`: registers the delegate, calls `startIntentSenderForResult`, consumes
  `onActivityResult`, request code 10). Fenix installs it in `BaseBrowserFragment.kt:1373-1385` and routes
  `onActivityResult` at `:2118-2122`.
- Errors reach the page via `AndroidWebAuthnService.h:29-70`: `ABORT_ERR → AbortError`, `UNKNOWN_ERR →
  UnknownError`, `NOT_SUPPORTED_ERR → NotSupportedError`, `SECURITY_ERR → SecurityError`,
  `NOT_ALLOWED_ERR → NotAllowedError`. `isUserVerifyingPlatformAuthenticatorAvailable()` resolves `false` on
  any failure, so sites that gate the passkey button on it show "partial support".

## What happens in Hatch today

Request → Credential Manager → `SecurityException` (permission not declared) → GMS privileged client →
rejected (not allowlisted; and Hatch registers no `ActivityDelegate`, so even a granted `PendingIntent` would
never launch) → the page gets `AbortError` (create) or `UnknownError` (get). No Android passkey sheet
appears. Iceraven users report the same (`UnknownError: The operation failed for an unknown transient
reason`).

## Google's privileged-browser allowlist

- Google Password Manager validates `origin` callers against the public list
  `https://www.gstatic.com/gpm-passkeys-privileged-apps/apps.json` via
  `androidx.credentials.provider.CallingAppInfo.getOrigin(privilegedAllowlist)`; a non-match throws
  (`CallingAppInfo.kt:243-252`). The FIDO2 privileged API is gated the same way.
- `apps.json` (2026-10-18): 73 entries (package + SHA-256 signing cert + `build: release|userdebug`).
  Mozilla: `org.mozilla.firefox`, `firefox_beta`, `fenix`, `focus/klar`, `reference.browser`,
  `org.mozilla.fenix.debug` (userdebug only). Third-party GeckoView browsers present: only
  `net.waterfox.android.release`. Absent: iceraven, IronFox, Fennec F-Droid, Mull, Hatch.
- Process: "Google Password Manager requires approval to handle those credentials … complete the request
  form" (`developer.android.com/identity/sign-in/privileged-apps`); the form asks for package name + release
  signing-cert SHA-256. Form: `https://docs.google.com/forms/d/e/1FAIpQLScRuk9tTg0QPfSWl9SbpbIorX8xx2FCXlmoUYftCX2MxG4qyg/viewform`.
- **Debug-signed builds can never work** on a retail (`user`) phone: `userdebug` entries only count on
  userdebug devices.
- Non-privileged apps can only do passkeys for their own origin (Digital Asset Links). A browser cannot do
  passkeys for arbitrary sites without the allowlist.

## Alternatives considered

| Option | Verdict |
|---|---|
| `androidx.credentials` without origin | Own origin only; useless for a browser. |
| USB/NFC security keys via `authenticator-rs` | Not built for Android (`third_party/rust/authenticator/src/transport/mod.rs:58-67` selects the stub); `security.webauth.webauthn_enable_usbtoken` defaults false on mobile; no embedder hook. Needs a GeckoView fork. |
| `security.webauth.webauthn_enable_softtoken` | Routes to the WebDriver virtual-authenticator test token (`authrs_bridge/src/test_token.rs`), in-memory, only populated by WebDriver. Not a fallback. |
| Forks | Iceraven: "Google checks to see if we're on their list … and we aren't." Waterfox got listed with its release cert, so a small GeckoView browser can be approved. |

## Decision for M1

1. Submit the allowlist form with `app.hatch.browser` and the release cert
   `FA:BB:DB:E6:7D:A6:5A:F0:DE:A2:F6:F2:BA:91:61:CB:77:24:25:EB:8A:B0:6B:95:38:50:9F:52:AB:ED:FF:EA`
   (keystore generated 2026-10-18; Cory submits, it is his Google account).
2. Wire WebAuthn now so it works the day approval lands: `feature-webauthn` dependency, `WebAuthnFeature`
   in `MainActivity.installFeatures`, `onActivityResult` routed to it after `PromptFeature`, manifest
   permissions `CREDENTIAL_MANAGER_SET_ORIGIN` and `CREDENTIAL_MANAGER_QUERY_CANDIDATE_CREDENTIALS`.
3. Menu action "Open in another browser" (`ACTION_VIEW` chooser) so passkey logins remain reachable.
4. Do **not** override `navigator.credentials` in the shim or set `security.webauth.webauthn=false`: both
   are detectable tells against the desktop persona. Document in `docs/limitations.md`.

Unverified: the exact GMS status code for a non-allowlisted privileged caller; whether Bitwarden/1Password
honour `apps.json` or stricter lists; Google's approval criteria and turnaround.
