# Device checklist (runbook)

What Cory does with a fresh APK, in order. Budget ≈ 15 minutes. Results go to `docs/m<N>-results.md`; files
to `reports/device/<version>/` (the share zip, screenshots, `meminfo.json`).

## 0. Install

```
adb install -r dist/hatch-<version>-<build>-<sha>-debug.apk      # or sideload the file
```

Debug builds install as `app.hatch.browser.debug` ("Hatch" with a debug version suffix in Settings), so a
release build can coexist. First start opens the home URL with the default persona
`Windows · Firefox 158 / Device (no override)`.

## 1. Self-check protocol

The Self-check screen is the harness page (`docs/detection-harness.md`) served from the app itself.

1. Menu (⋮) → **Self-check**. A bar appears: `Run`, `Run with persona off`, `Share report`, `Close`. The local
   server (`127.0.0.1:<port>/t/<token>/`) lives exactly as long as the bar is visible.
2. Persona chip → pick **Windows · Firefox 158** + **United States (New York)** → Apply (→ Restart if the time
   zone changed). Re-open Self-check.
3. **Run**. The page loads `index.html?persona=win-firefox__us-east&env=device`, probes everything, shows a
   PASS/FAIL banner and a table, POSTs the JSON to the app, and the bar shows
   `PASS · pass N fail N skipped N · libs 10/10`.
4. **Tap the dashed box** on the page ("Tap / click here") — this fills `interactive.pointerEvents` (hard: a
   trusted tap arrived) and `interactive.pointerType` (soft: `mouse` + `mozInputSource 1` through the shim's
   masking; Gecko itself reports `touch`/5, so a soft FAIL here means the shim did not run in that page, not a
   pref problem). The banner re-evaluates on the tap. Without the tap the device run cannot be PASS
   (device-scoped SKIPPED). `css.maxDeviceWidth480` / `css.minResolution2` are expected soft FAILs (engine-level
   `device-*`, `docs/limitations.md`).
5. **Share report** → zip with the JSON, a screenshot PNG and `meminfo.json` → send it to yourself / save it.
   The same files are under `Android/data/app.hatch.browser.debug/files/reports/` for `adb pull`.
6. **Run with persona off** (control): switches to `real-device` (keeps the region), reloads the harness as
   `real-device__<region>`. It must **FAIL** on the touch/pointer signals (the phone really has a touch
   screen) and show `libs` as INFO. This proves the harness can fail on this phone. Closing the bar restores
   the previous persona.
7. Repeat step 3 for `mac-firefox` (screen 1440×900, dpr 2, 30-bit colour) and `linux-firefox` if time
   allows; `win-chrome__uk` once to see the documented `ua.vendor` soft FAIL.

What to look at in the table when something is red:

| Signal group | Mechanism | If it fails |
|---|---|---|
| `ua.*`, `server.userAgent` | per-session UA override + prefs | Engine prefs view in Settings: are `general.*.override` user-set? |
| `touch.*`, `css.pointerFine/hoverHover` | prefs `dom.w3c_touch_events.enabled`, `ui.*PointerCapabilities` | same; `touch.createEvent` false is engine-level and not shimmable |
| `layout.clientWidth`, `css.bp*` | `browser.viewport.desktopWidth` + desktop viewport mode | needs a reload after a switch; check the persona chip says a desktop identity |
| `screen.*`, `geo.*`, `webgl.*`, `misc.toStringNative` | JS shim | Settings → Bridge extension: status must be `connected v0.1.0`; the log shows `persona shim registered` |
| `intl.timeZone`, `contexts.worker.timeZone` | `env: TZ` + restart | restart really happened? (Settings footer build time); else enable **Time-zone JS shim** |
| `intl.languages`, `server.acceptLanguage` | `intl.accept_languages` | region other than `device` selected? |
| `contexts.*` | shim in every frame (incl. data:/blob:/sandboxed) / worker | `contexts.worker.hardwareConcurrency` / `contexts.sharedWorker.hardwareConcurrency` soft FAIL means the phone has ≠ 8 cores (M2) |
| `interactive.pointerEvents` / `pointerType` | trusted-event masking in the shim | tap the box; `pointerType touch` (soft) means the shim did not run in that document; no event at all means the tap did not reach the page |
| `intl.locale` (hard on device) | `intl.locale.requested` + `runtime.settings.locales` | region `de`/`jp` selected and the app restarted? (RT-12) |

Compare with the headless baseline: `reports/headless/m0/<persona>.json` has the same `signals[]` ids; a
signal that is PASS in headless and FAIL on device points at a device-only mechanism (viewport, TZ env,
GeckoView process model).

## 2. Site audit (15 sites, tri-state + note + screenshot)

Persona `win-firefox__us-east` unless stated. For each site: ✅ desktop UI / ⚠️ partial / ❌ mobile or broken,
one line of notes, a screenshot (the share zip screenshot or a system screenshot). **Record the phone
orientation** — the forced desktop viewport keeps the display aspect ratio, so `(orientation: portrait)` stays
true when the phone is held upright (known limitation); try landscape for sites that misbehave.

| # | Site | Expect |
|---|---|---|
| 1 | youtube.com | `www.` host, the guide rail on the left, survives a force-stop + relaunch (no `m.`, no `app=desktop` loop) |
| 2 | en.wikipedia.org | a *followed* link to `en.m.wikipedia.org` (and `m.wikihow.com`) is rewritten to the desktop host by the extension, not only a typed one |
| 3 | facebook.com | `www.` host, desktop login/feed (not `m.`/`mbasic.`/`touch.`) |
| 4 | amazon.com | no `/gp/aw/` URLs; desktop header and product pages |
| 5 | google.com "weather" | desktop SERP with the weather card |
| 6 | x.com | three-column layout |
| 7 | reddit.com | no "open in app" interstitial, desktop layout |
| 8 | mail.google.com | standard (desktop) view, not the basic HTML / mobile view |
| 9 | getbootstrap.com/docs | expanded navbar (≥ 992 px) |
| 10 | github.com repo | repo tab row + right sidebar |
| 11 | tailwindcss.com/docs | left sidebar visible (≥ 1024 px) |
| 12 | docs.google.com | desktop editor; typing with the on-screen keyboard works: tap an input in the **bottom third** of the page — the field must stay visible above the keyboard and `innerHeight` must shrink (IME inset, HATCH-WC-04) |
| 13 | google.com/maps | desktop UI; drag and pinch inside the map: with the default prefs APZ may pan the page instead (HATCH-WC-06) → retry with Settings → "Keep touch events" on and note both results |
| 14 | photopea.com | no "unsupported" overlay; finger drawing works through pointer events (strokes may be interrupted by APZ panning: see #13); in fullscreen the layout drops to the display width (engine limit, HATCH-WC-07) — note it, try landscape |
| 15 | excalidraw.com | freehand drawing works (same caveat as #13) |
| 16 | any site with `target="_blank"` links / `window.open` (github.com README links, an OAuth login) | the new tab keeps the 1366 px desktop viewport (`documentElement.clientWidth ≥ 1366`, red-team 31) |

Also try the **Persona off (real device)** menu item on one site to confirm the one-tap escape works.

## 3. Interaction gate (M0-g)

With a desktop persona: scroll, fling, pinch-zoom, double-tap zoom, long-press on a link (page `contextmenu`
fires first, then the context menu with Open in new tab / Copy link / Save), **long-press on plain text** (a
word gets selected, selection handles appear, Copy/Select all; drag a handle — `layout.accessiblecaret.enabled`,
HATCH-WC-03), a page with its own `contextmenu` handler (e.g. excalidraw), an external Bluetooth/USB mouse
hover over a dropdown menu.

## 3b. Red-team pages on the phone

Open these through the Self-check server (`http://127.0.0.1:<port>/t/<token>/redteam/<page>` — copy the base
from the Self-check bar's status line into the address bar) with `win-firefox__us-east`; they print one line per
check and must show no `DETECTED` except where noted:

| Page | What to do | Expected |
|---|---|---|
| `09-pointer-tap.html` | tap the box 3 times | clean (`pointerType mouse`, `mozInputSource 1`, no `touch*` events) |
| `10-webgl.html` | nothing | `DETECTED` on capability plausibility is a documented limit (record it) |
| `12-voices.html` | nothing | `DETECTED` on `moz-tts:android:` voices is a documented limit (record it) |
| `11-fonts.html` | nothing | `DETECTED fonts.*` is a documented limit (record which fonts) |
| `06-screen-vs-window.html` | portrait, then landscape | only the documented portrait geometry lines fire |
| `07-intl-language.html` | with region `de`, then `jp` (restart) | clean (if it fires, `intl.locale` is broken on device: RT-12) |
| `30-compat-touch-gestures.html` | tap, draw, swipe, long-press; repeat with "Keep touch events" on | `compat.*` lines say `=> ok` with the toggle on; note the default-prefs result |
| `31-popup-viewport.html` | tap `window.open()` and the `target=_blank` link | `popup.report … => ok` |

## 4. Devtools gates (M0-h/i/j)

- Eruda: menu → Eruda devtools on example.com and github.com; the console shows page logs; toggle again
  destroys it.
- uBlock Origin: Settings → "Install add-on from URL" with the AMO `.xpi` link → accept the permission dialog →
  an ad-heavy page is clean → force-stop + relaunch → still enabled → the uBO dashboard opens as a tab.
- Remote debugging: Settings → Remote debugging on → Restart → `adb forward` is not needed with USB;
  desktop Firefox `about:debugging` → This Firefox / USB devices → the Hatch tab is inspectable.

## 5. Time zone gate (M0-e)

Persona chip → region **Japan (Tokyo)** → Apply → "Restart to apply time zone" → Restart → Self-check Run →
`intl.timeZone = Asia/Tokyo` and `contexts.worker.timeZone` agree. If `TZ` does not reach the content process,
Settings → **Time-zone JS shim** on → Run again (`intl.timeZone` PASS, `contexts.worker.timeZone` becomes a
soft signal) and note it in `docs/m0-results.md` so the shim fallback is scheduled.

## 6. Memory snapshot

Open three tabs (youtube, github, maps), then Self-check → Share report: `meminfo.json` in the zip has the
per-process PSS; the budget is ≤ 600 MB total. Note cold-start time (launch to first paint) and persona
switch time (Apply → page reloaded) by stopwatch.
