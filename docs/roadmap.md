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

- [ ] Tabs: sheet, restore, crash recovery
- [ ] Prompts incl. file upload with camera; downloads with the foreground service; fullscreen + PiP
- [ ] Find in page (hand-rolled, Compose)
- [ ] Context menu, app links / open-from-other-apps, error pages, site permissions (wired; verify on device)
- [ ] Hardware keyboard: Ctrl+T/W/L/F/R, Ctrl+Tab, Ctrl+1..9, Alt+←/→, Esc, Ctrl+Shift+I (Eruda), Ctrl+Shift+P (persona), F5
- [ ] Region switching + geolocation verified on a maps site
- [ ] Persona chip + one-tap force-desktop (present; polish)
- [ ] Rules screen + editor + GM subset + 3 bundled example rules (bundled rules exist; editor UI)
- [ ] Host rewrites with loop guard (extension side done; per-host disable in UI)
- [ ] Settings with build-info footer and "Engine prefs" truth view (done), release keystore
- [ ] Site audit ≥ 13/15; security findings closed
- [ ] Red-team round 1: `harness/redteam/<n>-<name>.html` pages; every successful detector becomes a harness signal or a `docs/limitations.md` entry

## M2 — polish

- [ ] Per-site persona overrides (sites that genuinely need touch)
- [ ] `.user.js` / `.user.css` import with header parsing (`UserscriptHeader` exists)
- [ ] Export / import of rules and personas
- [ ] Favicons / thumbnails, media notifications, private tabs
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
