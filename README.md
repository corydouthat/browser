# Hatch

An Android browser that forces websites into their **true desktop layout**. Built on GeckoView 158 (the
Firefox engine, pinned inside the APK) with Mozilla Android Components 158, Kotlin and Jetpack Compose.

## Why

"Request desktop site" in Chrome or Firefox only swaps the User-Agent and lays the page out at 980 px. Modern
sites look at much more: client-hint headers, viewport width against 992/1024/1200/1280 px breakpoints,
`maxTouchPoints`, `ontouchstart`, `(pointer: coarse)` / `(hover: none)`, `navigator.platform`, screen size,
time zone and language — and keep serving the mobile layout or bounce to `m.` hosts. When the mobile version
lacks a feature you need on the road, there is no escape hatch. Hatch is that hatch: it spoofs the **whole
desktop environment coherently** (a *persona*: desktop identity × region), at engine level where possible and
with a JS shim where not, and proves it with a detection harness that runs headless on a server and as a
Self-check screen on the phone.

## What it does (M0 + M1)

- **A real browser chrome (M1, redesigned 2026-10-21)**: Chrome-style top address bar (Hatch's own toolbar
  over Android Components' toolbar store: inline autocomplete, security badge, progress, tab counter) whose
  persona chip and tint show desktop vs. phone at a glance, six brand palettes in light and dark (Geist type),
  one-tap desktop/phone switches in the menu and on the home page, a grid tab tray with thumbnails and favicons (swipe to close, undo, multi-select, restore
  after process death, crash recovery), find in page in the toolbar row, a main menu, a home page for new tabs, a site-info card.
- **Saved logins (M1)**: Hatch's own password store fills sign-in forms (Google's two-step form included),
  offers to save after a submit, keeps a per-site "never save" list and shows the list behind your fingerprint /
  PIN. Google Password Manager cannot fill web forms in a third-party browser; passkeys are wired but wait for
  Google's privileged-browser allowlist (release signature only) — "Open in another browser" bridges the gap.
- **Site rules UI (M1)**: list / editor / "test on current site", `.user.js` / `.user.css` import with header
  preview, per-host switches for the mobile→desktop host rewrites, a site-permissions screen, an app-links mode
  (default: links stay in Hatch), and an editor for custom identities and regions.

- **Personas**: identity (`win-firefox` default, `mac-firefox`, `linux-firefox`, `win-chrome`, `real-device`)
  × region (`device`, `us-east`, `uk`, `de`, `jp`), switched live from the persona chip; only a time-zone
  change restarts the app. One-tap "Persona off (real device)" for sites that need touch.
- **Engine-level desktop forcing**: 1366 px layout viewport that ignores `<meta viewport>`, `pointer: fine` /
  `hover: hover` at CSS level, no `TouchEvent` / `ontouchstart` / `window.orientation`, desktop
  `navigator.platform` / `oscpu` / `appVersion` / `languages` / `Accept-Language`, no client hints (Gecko
  sends none), `TZ` for the whole process.
- **JS shim** (built-in extension, MAIN world, `document_start`, every frame): screen, DPR, cores, WebGL
  renderer, geolocation, `userAgentData` for the Chrome persona, native-looking getters.
- **Dev tools**: Eruda in any tab (also on strict-CSP sites), Gecko remote debugging over adb, an "Engine
  prefs" truth view, a bridge-extension log.
- **Add-ons**: install Mozilla-signed `.xpi` files from a URL (uBlock Origin).
- **Per-site rules**: userscripts / userstyles with a Greasemonkey subset (three bundled, disabled examples).
- **Self-check**: the detection harness served by the app itself, with a share-able report zip.
- The usual browser plumbing via Android Components: prompts incl. file upload, downloads, context menu,
  fullscreen + PiP, site permissions, app links, error pages, session restore, hardware-keyboard shortcuts.

## Build

Everything is sized for the 3.6 GB build VPS; see `docs/dev-environment.md` for the rules (one heavy process
at a time under `flock /tmp/hatch-heavy.lock`, capped Gradle heap, `--no-daemon --max-workers=1`).

```
export JAVA_HOME=/opt/android-studio/jbr ANDROID_HOME=$HOME/Android/Sdk
make setup                    # once: Firefox 158 + geckodriver for the harness, npm deps
make check                    # lint -> unit (:persona JUnit, node tests, vendor-check) -> headless harness
tool/build_apk.sh debug       # -> dist/hatch-<version>-<build>-<sha>-debug.apk
tool/build_apk.sh release     # R8 + shrink, needs key.properties; then tool/check_apk_size.sh
```

`tool/build_apk.sh` runs Gradle on a remote host when `.buildhost` (git-ignored, `user@host`) exists, and
locally under the lock otherwise.

## Sideload

```
adb install -r dist/hatch-0.1.0-<build>-<sha>-debug.apk
```

or copy the APK to the phone and open it (allow installs from the file manager). Debug builds install as
`app.hatch.browser.debug` next to a release build. Then follow `docs/device-checklist.md`: persona chip →
pick an identity and region → Self-check → Run → tap the box → Share report.

## Repository map

| Path | What |
|---|---|
| `persona/` | engine-neutral persona model, presets, the persona → Gecko pref table, goldens (JVM tests) |
| `app/` | the Android app; `app/src/main/assets/extension/` is the built-in extension |
| `harness/` | detection harness (page, probes, vendored detector libraries, node tests) |
| `personas/` | preset JSON (source of truth) + `golden/*.prefs.json` |
| `tool/` | build / size / headless scripts |
| `reports/` | headless reports per milestone (`reports/headless/m0/`), device reports |
| `docs/` | `architecture.md`, `persona-model.md`, `extension.md`, `detection-harness.md`, `dev-environment.md`, `limitations.md`, `roadmap.md`, `device-checklist.md`, `icon.md` |

## Status

M0 (spike) is code-complete and green on the VPS after the adversarial fix pass: 35 `:persona` JVM tests,
9 app JVM tests, 34 harness node tests, 65 extension node tests (incl. the shim size/time budgets),
`web-ext lint` 0 errors, headless harness 11/11 (8 personas PASS on every hard signal and every library
verdict, 3 negative controls fail exactly as designed), red-team pages 01–08/13 clean, debug APK built
(~143 MB with compressed native libs). Waiting for the device report (`docs/roadmap.md`, checklist a–k + g2).
Known tells and limits: `docs/limitations.md`.

## License

Hatch's own code: to be decided by the owner before the first public release. Third-party code vendored into
the tree is listed in `THIRD_PARTY.md` (eruda, MIT; Android Components `WebContent`, MPL-2.0). GeckoView and
Android Components are MPL-2.0 dependencies resolved by Gradle. The launcher icon is derived from a CC0
clip-art (`docs/icon.md`).
