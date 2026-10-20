# Roadmap

Milestones follow the plan agreed on 2026-10-18. Each milestone ends with `make check` green, an APK in
`dist/`, and a device report from Cory in `reports/device/<version>/` + `docs/m<N>-results.md`.

## M0 — spike: prove the thesis on device

Status: **code complete on the VPS; waiting for the device report.**

Done on the VPS:

- [x] Scaffold + empty Compose activity builds within the memory caps (second build < 3 min)
- [x] `Components.kt` lazy singletons, `GeckoRuntimeFactory`, `GeckoPrefs`, `PersonaToPrefs` + golden tests (35 JUnit tests)
- [x] One tab with URL bar / reload / back; persona sheet with live switch + restart flow
- [x] Bridge extension + persona shim (65 node tests, `web-ext lint` 0 errors); Eruda
- [x] Settings: install add-on from URL, remote-debugging toggle, tz-shim toggle, engine-prefs truth view
- [x] Harness served by the in-app `LocalHttpServer`; Self-check bar (Run / Run with persona off / Share report)
- [x] Headless harness green for every `scope: both` hard signal and every library verdict on all 8 golden personas; the three negative controls fail exactly as designed (`reports/headless/m0/`)
- [x] Debug APK built (`tool/build_apk.sh debug`), size budget checked
- [x] M0 fix pass: red-team RT-01…09 closed (harness signals added), web-compat WC-01…06 fixed in code (WC-07 documented), security SEC-1…8 closed, PERF-01/02/04/05 fixed (`docs/limitations.md` has the accepted vectors)

### M0 device checklist (Cory, ≈ 15 min per build)

Record results in `docs/m0-results.md`; the Self-check zip and audit screenshots go to
`reports/device/<version>/`. The step-by-step runbook is `docs/device-checklist.md`.

- [ ] **(a)** Self-check with `win-firefox__us-east`: `layout.clientWidth ≥ 1366`, CSS breakpoint bucket shows "≥ 1280 (Tailwind xl)", the demo grid has 3 columns
- [ ] **(b)** CSS-level `css.pointerFine` / `css.hoverHover` PASS (engine prefs, independent of the shim)
- [ ] **(c)** `maxTouchPoints 0`, no `ontouchstart` / `TouchEvent` / `window.orientation`, `touch.createEvent` false
- [ ] **(d)** `navigator.platform` / `oscpu` match the persona; `server.userAgent` and `server.acceptLanguage` echo the expected strings
- [ ] **(e)** Region `jp` → restart → `intl.timeZone = Asia/Tokyo` and `contexts.worker.timeZone` agree (fail → enable the tz-shim setting and re-run; note it in the results)
- [ ] **(f)** Switch `win-firefox` → `mac-firefox` → `real-device` without a restart; Self-check reflects each (screen 1440×900 / dpr 2 for mac; persona-off run FAILs on touch as designed)
- [ ] **(g)** With touch events off: scroll, fling, pinch-zoom, double-tap, long-press context menu **and long-press text selection with handles**, a page `contextmenu` handler (the harness tap box records a trusted tap; `pointerType: mouse` through the shim), external mouse hover, an input in the bottom third of a page stays visible above the keyboard, a `target=_blank` link keeps the desktop viewport
- [ ] **(g2)** Red-team pages on the phone (`docs/device-checklist.md` §3b): 09, 30 (with and without "Keep touch events"), 31 clean; 06/07/10/11/12 results recorded
- [ ] **(h)** Eruda opens on example.com and on github.com (strict CSP)
- [ ] **(i)** uBlock Origin installs from the AMO `.xpi` URL, blocks ads, survives a restart, its dashboard opens as a tab
- [ ] **(j)** Desktop Firefox `about:debugging` over adb inspects a tab (Settings → Remote debugging on, restart)
- [ ] **(k)** Three problem sites named by Cory render the desktop UI; failures explained with a harness compare (Self-check JSON vs `reports/headless/m0/win-firefox__us-east.json`)

Also useful: the 15-site audit (`docs/device-checklist.md`) with orientation noted, and the share zip's
`meminfo.json` (PSS with 3 tabs ≤ 600 MB budget).

### Open items for Cory (defaults apply)

- App name / package: `Hatch` / `app.hatch.browser` (one-line rename)
- Release keystore: generated locally in M1 (`key.properties` + `.jks` git-ignored, backed up over the tailnet)
- The three problem sites for (k)
- Remote: GitHub `corydouthat/browser` (set up 2026-10-18; CI in `.github/workflows/check.yml`)

## M1 — usable escape hatch

Status: **code complete and green on the VPS (2026-10-18); every row marked "unverified on device" waits for
the M1 device checklist below.** Cory's first-use feedback drove the scope: tab switching, a browser that looks
like a browser (Chrome-style top toolbar, Material You), Google sign-in / saved logins, passkeys (blocked on
Google's allowlist, wired anyway).

- [x] Tabs: tray (grid, thumbnails, swipe/undo, multi-select, close all), process-scoped restore, crash recovery (chrome; unverified on device)
- [x] Browser chrome: `compose-browser-toolbar` at the top with inline autocomplete, security badge, tab counter, persona strip, menu sheet, home content, site-info dialog (chrome; unverified on device; scroll-to-hide shipped **off**)
- [x] Prompts incl. file upload with camera; downloads with the foreground service (`POST_NOTIFICATIONS` asked at the first download); fullscreen + PiP (wired since M0; unverified on device)
- [x] Find in page (hand-rolled, Compose; chrome)
- [x] Context menu, error pages (M0), app links with a Settings mode (default "Stay in Hatch"), open-from-other-apps (`APP_BROWSER`, typed HTML, `content:` PDF filters; the `content:`/`file:` HTML filters were removed in the fix pass, SEC-4), Site permissions screen on `GeckoSitePermissionsStorage` (rules + credentials; unverified on device)
- [x] Hardware keyboard: Ctrl+T/W/L/F/R, Ctrl+Tab, Ctrl+1..9, Alt+←/→, Esc, Ctrl+Shift+I (Eruda), Ctrl+Shift+P (persona), F5 (chrome; Esc/F5 pass-through rules in `docs/limitations.md`; unverified on device)
- [ ] Region switching + geolocation verified on a maps site (device only; the shim path is headless-green)
- [x] Persona chip + one-tap force-desktop (toolbar page action, persona strip, menu switch, home card) + persona editor for custom identities/regions (chrome + rules)
- [x] Rules screen + editor + GM subset + 3 bundled example rules + `.user.js`/`.user.css` import with header preview (rules; pulled forward from M2)
- [x] Host rewrites with loop guard + per-host switches in Settings (rules)
- [x] Saved logins: in-app store (`service-sync-logins`), engine autofill, save prompt, Compose picker, Saved logins screen behind device credential, "never save" list (credentials; unverified on device)
- [x] Passkeys wired (`WebAuthnFeature`, Credential Manager permissions); **expected to fail until Google's privileged-browser allowlist lists `app.hatch.browser` + the release cert** (request pending; debug builds never). "Open in another browser" is the bridge
- [x] Settings with build-info footer and "Engine prefs" truth view; release keystore (`key.properties` + `hatch-release.jks`, git-ignored, synced to the build host; signed release APK built 2026-10-18)
- [x] Red-team round 1 (M0 pages 01–13, 30, 31) re-run after M1 with the second built-in extension (`icons@mozac.org`) installed in headless: 01/03/07/13 clean, full `win-firefox__us-east` run identical to the M0 baseline (`reports/headless/m1/`)
- [ ] Site audit ≥ 13/15 (device); security findings closed for M1 (review pending: logins store, rules import, app links)
- [x] Release APK after the fix pass: 100.63 MiB signed (budget 110; `resources.arsc` 0.65 MiB with the "en" locale filter), selfcheck 1.02 MiB, extension 0.05 MiB (`build/size.json`, PERF-01/02)
- [x] M1 fix pass (fixer, 2026-10-18): UXP-01…08/10…15, SEC-1/2/3/4/6, ENG-01…06, PERF-02/03/04/06/07/08 fixed in code with JVM tests; UXP-09 by Activity recreation; ENG-07, SEC-5, PERF-01/09/10 documented; PERF-05 (narrow the app-services keep rule) deferred to M2 behind a device run of the logins flows (`docs/limitations.md` "M1 fix pass")

- [x] Release build runs on the device (2026-10-20): build 20/21 crashed in `GeckoRuntime.create` (R8 vs SnakeYAML, `docs/limitations.md` "Release-only failures"); fixed in build 22 with a mapping guard in `tool/build_apk.sh`. Startup-crash capture + safe-mode recovery screen (`app/hatch/crash/`) shipped with build 21 and produced the trace; `versionCode` from the remote host fixed (was 1)

### M1 device checklist (Cory; use-case driven, ≈ 20 min; runbook sections in `docs/device-checklist.md`)

Install the **release** APK from `dist/` (the debug one cannot ever do passkeys). Record results in
`docs/m1-results.md`; the Self-check zip goes to `reports/device/<version>/`.

- [ ] **Tabs**: open three sites, tab counter → tray shows thumbnails + favicons; swipe one away → "Tab closed – Undo" works; long-press → select two → close; "Close all" → undo; + opens the Hatch home with the toolbar in edit mode; force-stop Hatch → relaunch → the tabs are back
- [ ] **Toolbar**: tap the origin → inline autocomplete (type `git` → `github.com`), Enter loads; lock/globe badge → site info; the desktop/phone chip toggles the persona and reloads; Settings → "Hide toolbar when scrolling" on → scroll a long page → the toolbar hides/reappears without the page jumping (note any glitch, then switch it off again)
- [ ] **Find**: menu → Find in page (or Ctrl+F) → type a word → "n/m" counter, prev/next scroll the match into view, close clears highlights
- [ ] **Google sign-in** (`win-firefox`): accounts.google.com → plain web form, no "browser may not be secure" interstitial, 2FA reachable; after submit **Save login?** → Save; sign out, revisit → the fields fill (one login) or the picker bar shows above the keyboard (several). Repeat once with `win-chrome` and record Google's reaction (`docs/device-checklist.md` §7)
- [ ] **Saved logins**: menu → Saved logins → biometric/PIN sheet → list, reveal, copy ("Password copied", clipboard preview hidden on API 33+), delete asks, + adds an entry that then fills on that site; Settings → "Autofill saved logins" off → nothing fills; on → fills again without a restart
- [ ] **Passkey (expected failure)**: webauthn.io → Register → no Android passkey sheet, page shows `AbortError`/`NotAllowedError` (login: `UnknownError`); record the exact text and Android version; menu → **Open in another browser** → Chrome completes it. When Google approves the allowlist, repeat: the "Create a passkey" sheet must appear inside Hatch
- [ ] **Rules import**: menu → Site rules → Import → paste a `.user.js` with `@grant GM_addStyle` → preview shows @match/@grant → Add (arrives disabled) → enable → reload target site; a script with `@require` is refused with the reason; "Test on current site" on the bundled "hide cookie banners" rule reloads the page without the banner
- [ ] **Persona editor**: menu → Persona… → Duplicate `Windows · Firefox 158` → hardwareConcurrency 12 → Save → Switch… → the copy → Self-check reports `hardwareConcurrency 12`; delete it while active → falls back to the default preset
- [ ] **Open in another browser**: on any https page → menu → Open in another browser → the chooser lists Chrome/Firefox but not Hatch; the page opens there
- [ ] **App links**: with the YouTube app installed, a `youtube.com/watch` link stays in Hatch (default); Settings → App links "Ask" → the "Open in app?" dialog; "Always" → jumps to the app
- [ ] **Red-team on the phone** (`docs/device-checklist.md` §3b) with the M1 build: 01, 02, 03, 07, 13 must print no `DETECTED` with favicons now collected by `icons@mozac.org`
- [ ] **Memory**: three tabs + tray opened (thumbnails loaded) → Self-check → Share report → `meminfo.json` PSS ≤ 600 MB
- [ ] **Fix-pass gates** (`docs/device-checklist.md` §8): home controls respond on the first tap; snackbars visible over Settings / Saved logins; Saved logins refuses screenshots and re-locks after Home; Ctrl+W / long-press "Close tab" offer Undo; the tray's current-tab card is fresh on first open; chips not clipped at the largest font size; landscape toolbar clear of the cutout; theme switch consistent after recreation; `am send-trim-memory` suspends tabs; TZ restart keeps the last-opened tab; cold start / PSS / persona-switch numbers recorded

## M2 — polish

- [ ] PERF-05: try the narrow app-services keep rule (`UniffiLib`, `RustBuffer*`, `uniffi*` members instead of `-keep class mozilla.appservices.** { *; }`) on a release build and run the logins flows on the phone; record the dex delta next to the rule either way
- [ ] Compose UI tests (Robolectric + `compose-ui-test`) for the UXP findings that only have pure-helper locks today (`docs/limitations.md` "M1 fix pass")
- [ ] Harness `/echo`: record the favicon request's `Sec-Fetch-Dest` as an info signal (ENG-07) so the app-originated fetch is measured, not assumed

- [ ] Per-site persona overrides (sites that genuinely need touch)
- [x] `.user.js` / `.user.css` import with header parsing (done in M1 by rules, 2026-10-18)
- [ ] Export / import of rules and personas
- [x] Favicons / thumbnails (done in M1 by chrome: `browser-icons`, `browser-thumbnails`)
- [ ] Media notifications, private tabs
- [ ] Scroll-to-hide toolbar validated on a device and switched on by default
- [ ] Passkeys live once Google's allowlist entry lands (no code change expected; re-run the passkey row)
- [ ] Security review of the M1 surfaces (logins store, `.user.js` import, app-links prompts, Saved logins gate)
- [ ] Worker-scope shim (`contexts.worker.hardwareConcurrency` becomes hard)
- [ ] TZ / Intl shim fallbacks if M0-e needs them
- [ ] Self-check PASS/FAIL against `expectedReport()` with per-signal history
- [ ] Update channel (`latest.json` on the VPS, in-app "check for update")
- [ ] Red-team round 2; `docs/limitations.md` current
- [ ] MV3 `userScripts` fallback plan if MV2 is retired for built-in extensions

## Budgets (performance reviewer)

| Budget | Target |
|---|---|
| shim | ≤ 30 KB, < 5 ms sync work (today 30,716 bytes — 4 bytes of headroom, minified build is the M1 follow-up —, ~2.5–3.3 ms cold median in node) |
| PSS with 3 tabs | ≤ 600 MB |
| cold start | ≤ 2.5 s |
| persona switch | ≤ 2 s live / ≤ 4 s with restart |
| arm64 APK | ≤ 110 MB (release, compressed native libs; measured 103.1 MB unsigned R8 build on 2026-10-18); selfcheck assets ≤ 2 MB; extension (excluding eruda) ≤ 200 KB |
