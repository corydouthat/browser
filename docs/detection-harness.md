# Detection harness

`harness/` is a static page that measures every signal a site can use to tell a phone from a desktop and
compares it with what a persona promises. One set of files runs in three places:

| Where | How it is served | Expectations | Env |
|---|---|---|---|
| **Headless Firefox 158** on the VPS (`make headless`) | `tool/headless/server.mjs` on two ephemeral ports (page origin + cross-origin frames), the real extension installed as a temporary add-on, prefs from `personas/golden/*.prefs.json` | `harness/personas/<id>.json` | `headless` |
| **The phone** (Self-check screen) | copied into the APK as `assets/selfcheck/` by the Gradle task `syncSelfcheckAssets`, served by `LocalHttpServer` at `http://127.0.0.1:<port>/t/<token>/` | same files, from the APK | `device` |
| **A desktop browser** for manual poking | `node tool/headless/server.mjs` → `http://127.0.0.1:8765/index.html?persona=win-firefox__us-east` | same | whichever |

The page uses **relative URLs only** (`./echo`, `./report`, `./personas/<id>.json`, `./frame.html`,
`./worker.js`, `./vendor/detectors.bundle.js`, `./breakpoints.css`) so any base path works. The host page or
server injects `window.__hatchCrossOrigin` (the second origin's base) into `<head>`; without it the
cross-origin signal is SKIPPED.

Query: `?persona=<identityId>__<regionId>&env=device|headless` (`persona=none` = no expectations, every value
becomes INFO; `env` defaults to `headless` when `navigator.webdriver` is true). On completion the page sets
`window.__hatchResult`, dispatches `hatch:done`, POSTs the JSON to `./report` and renders the table. Buttons:
Copy JSON, Copy table, Send report, Re-run. Tapping the dashed box re-evaluates `interactive.pointerType`.

## Files

| File | Role |
|---|---|
| `index.html`, `harness.js` | page + orchestration (ES modules); `harness.js` runs the probes with a 10 s timeout each |
| `evaluate.js` | pure compare logic: the signal table (`SIGNALS`), library evaluation, `summarize`, `buildResult`, `formatTable`; unit-tested in node |
| `probes/*.js` | `ua`, `touch`, `media` (JS `matchMedia` + CSS-level + device-* vs `screen.*` + `.media` serialisation), `screen` (+ layout incl. `mozInnerScreen*`), `intl`, `geo`, `webgl`, `misc` (exact toString text vs a never-shimmed reference getter / descriptors / plugins / marker symbols), `contexts` (same-origin, cross-origin, about:blank, srcdoc, data:, blob:, sandboxed frames, dedicated + shared worker, cross-realm toString), `server` (`./echo` twice), `interactive`, `libraries` |
| `breakpoints.css` | `@media` rules that flip `--hit` on probe elements: evaluated by the style engine, so a JS-only spoof cannot touch them |
| `frame.html`, `worker.js` | snapshot sources for the context-consistency probes (also the runner's readiness page) |
| `redteam/*.html` | red-team detector pages (`DETECTED <id>: …` lines; see `redteam/README.md`); shipped in the APK under `selfcheck/redteam/` for the device-only vectors |
| `personas/*.json` | expectations generated from `personas/*.json` by `tool/headless/gen-expectations.mjs` (mirrors Kotlin `Persona.expectedReport()`; `make lint` checks freshness) |
| `vendor/entry.js` → `vendor/detectors.bundle.js` + `vendor/MANIFEST.json` | exact-version detector libraries bundled with esbuild (`npm run vendor-build`; `npm run vendor-check` rebuilds in memory and diffs) |
| `test/*.test.mjs` | node tests: evaluate logic against desktop / phone / partial-spoof fixtures, the Gecko Accept-Language algorithm |
| `tool/build-vendor.mjs` | the bundle builder (Modernizr custom build with `touchevents` + `pointerevents`) |

Server endpoints (both `server.mjs` and `LocalHttpServer.kt`): `GET ./echo[?second=1]` → `{"headers":{lowercase
request headers}}` with `Accept-CH: Sec-CH-UA-Mobile, Sec-CH-UA-Platform, Viewport-Width, DPR` on the
response (the second fetch proves Gecko still sends nothing); `POST ./report` stores the JSON
(`build/harness-reports/posted/` or the phone's `files/reports/`).

## Result JSON (schema v1)

```
{ schema: 1, harnessVersion, persona, env, url, crossOrigin, startedAt, finishedAt, durationMs,
  harness: { version, probes[], libraryManifest },
  expected: <personas/<id>.json | null>,
  observed: { ua, touch, media, css, layout, screen, intl, geo, webgl, misc, contexts, server, interactive, libraries[] },
  signals:   [ { id, group, tier: "hard"|"soft", scope: "both"|"device", desc,
                 status: "PASS"|"FAIL"|"SKIPPED"|"INFO", expected, actual, note? } ],
  libraries: [ { name, version, mobile, expectedMobile?, status, detail, note? } ],
  summary:   { overall: "PASS"|"FAIL", pass, fail, skipped, info, hardFail[], softFail[], deviceSkipped[],
               libraryFail[], libraryTotal, libraryPass },
  runner?:   { runId, control, ext, tz, browserVersion, window, prefs, clear, durationMs }   // headless only
}
```

`summary.overall === "PASS"` iff no hard FAIL, no library FAIL and, in `env=device`, no device-scoped
SKIPPED. Statuses: PASS/FAIL (an expectation exists), SKIPPED (device-scoped in headless, nothing to measure,
or a touch-host signal for a touch identity off-device), INFO (no expectation, recorded for the record).

### Signal table

| Group | Signals (tier, scope) |
|---|---|
| ua | `userAgent`, `platform`, `oscpu`, `appVersion`, `userAgentData` (present iff chromeLike), `uaDataBrands`, `uaDataMobile` (hard, both); `vendor` (soft; documented FAIL for win-chrome), `webdriver` (INFO) |
| touch | `maxTouchPoints`, `ontouchstart` (window), `ontouchstartElement`, `TouchEvent`, `createEvent` (`document.createEvent("TouchEvent")`, engine-level, not shimmable) (hard, both); `orientation` (`"orientation" in window`, hard, device); `pointerEvents` (INFO) |
| media (JS `matchMedia`) | `pointerFine`, `hoverHover`, `anyPointerCoarse` (hard, both); `anyHoverHover` (soft); `orientation` (INFO); `deviceWidthVsScreen`, `deviceHeightVsScreen`, `resolutionVsDpr` (hard, both: `(device-width: <screen.width>px)` etc. must match, RT-04); `deviceAspectRatio`, `serialization` (`.media` equals the CSSOM `mediaText` for 5 spellings, RT-05) (soft) |
| css (stylesheet `@media`) | `pointerFine`, `hoverHover`, `anyPointerCoarse` (hard, both); `anyHoverHover` (soft); `bp992`, `bp1024`, `bp1200`, `bp1280` (hard, device); `minDeviceWidth1024`, `maxDeviceWidth480`, `minResolution2` (soft, device; the last two are the documented engine-level `device-*` limit and are expected to FAIL on the phone); `orientation` (INFO) |
| layout | `clientWidth ≥ layoutWidth` (hard, device); `innerWidth` (INFO); `outerVsScreen`, `chromeGeometry` (`mozInnerScreenY − screenY ≤ outerHeight − innerHeight`, RT-09) (soft) |
| screen | `width`, `height`, `availWidth`, `availHeight`, `colorDepth`, `dpr` (hard, both); `pixelDepth`, `orientationType` (soft) |
| intl | `languages`, `language`, `timeZone` (hard, both); `locale` (soft in headless — en-US-only build — and **hard on the phone**, RT-12), `dateOffsetCoherence` (soft) |
| geo | `position` (hard, both; within ~1 km); `accuracy`, `permission` (soft) |
| webgl | `vendor`, `renderer` (hard, both; SKIPPED when no GL context, as in headless); `available` (INFO) |
| misc | `toStringNative` (hard, both: every shimmed getter/method prints **exactly** the text of a never-shimmed reference getter with its bare name — `function hardwareConcurrency() {\n    [native code]\n}` — and `fn.name` is `get <name>`, RT-01); `descriptorShape` (no own props on navigator/screen, accessors present, no `hatch`-like own symbol on `window`, getter own-property order `[length, name]`), `plugins`, `pdfViewerEnabled`, `oscpuCoherence` (soft); `deviceMemory` (INFO) |
| contexts | `sameOriginFrame`, `crossOriginFrame`, `aboutBlankFrame`, `srcdocFrame`, `dataFrame`, `blobFrame`, `sandboxFrame` (hard: UA, platform, languages, hardwareConcurrency, timeZone, maxTouchPoints, appVersion, oscpu, screen size, dpr, touch surface and `(pointer: fine)` agree with top — RT-03 promoted); `crossRealmToString` (hard: a fresh realm's `Function.prototype.toString` prints native text for the top document's shimmed getters and vice versa, RT-02); `worker.userAgent`, `worker.platform`, `worker.languages`, `worker.timeZone`, `sharedWorker` (hard; soft when `tzShim`); `worker.hardwareConcurrency`, `sharedWorker.hardwareConcurrency` (soft, M2) |
| server | `userAgent`, `acceptLanguage` (hard, both; exact Gecko q-value string); `clientHintsFirst`, `clientHintsAfterAcceptCH`, `viewportHints` (hard: no `Sec-CH-UA*`, `Viewport-Width`, `DPR`, `Device-Memory`, `Save-Data`); `reachable` (INFO) |
| interactive | `pointerEvents` (hard, device: a *trusted* pointerdown reached the box — tap it); `pointerType` (soft, device: `mouse` + `mozInputSource 1` through the shim's trusted-event masking; the engine itself reports `touch`/5, HATCH-WC-02) |

Signals marked `touchHost` (`touch.*` except orientation, `media.*`/`css.*` pointer & hover) derive their
expectation from `expected.touch`; when a touch identity (`real-device`) is evaluated off-device a FAIL on them
becomes SKIPPED, because the headless host has no touch screen. On the phone they are hard.

### Library verdicts

Vendored exact versions (see `vendor/MANIFEST.json`): ua-parser-js 2.0.10 (`withFeatureCheck` +
`withClientHints`), mobile-detect 1.4.5, ismobilejs 1.1.1, is-mobile 5.0.0, detect-it 4.0.1, Modernizr 3.13.1
(custom build), bowser 2.14.1, platform 1.3.6, react-device-detect 2.2.3 (+ react 18.3.1), device-detector-js
3.0.3. Each returns `mobile: true|false|null`; for a desktop persona every verdict must be `false`
(`librariesMobile: false`), for `real-device` they are INFO.

## Headless runner (`tool/headless/run.mjs`)

```
make headless                                   # all goldens + controls -> build/harness-reports/
node tool/headless/run.mjs --personas win-firefox__us-east --controls --out build/harness-reports
node tool/headless/run.mjs --page harness/redteam/01-tostring.html   # red-team page: body "DETECTED" fails
node tool/headless/run.mjs --list
```

Per run: Firefox 158.0 (`~/opt/firefox-158.0`, asserted), geckodriver 0.37.1, headless, `TZ` from the golden
`env`, window sized from the persona screen, memory-trim prefs, prefs from the golden (`clear` = fresh-profile
defaults), the extension copied with `headless-native.js` prepended (stands in for the app on the `hatch`
port) and installed as a temporary add-on before the first navigation; the runner waits on `frame.html` until a
shim-only value from the golden config shows up (`screen.width`, else `hardwareConcurrency`, else the granted
geolocation permission — there is no marker symbol), loads `index.html?persona=<id>&env=headless`, polls `window.__hatchResult`, writes
`<out>/<id>.json` (+ `runner` block) and prints the table. The whole run re-execs itself under
`flock /tmp/hatch-heavy.lock` and, when available, `systemd-run --user --scope -p MemoryMax=1200M`.
Exit 1 on any hard/library FAIL, a failed control assertion or a DETECTED page; 2 on setup errors.

### Negative controls (the harness must be able to fail)

| Control | Setup on top of `--base` (default `win-firefox__us-east`) | Must FAIL | Must still PASS |
|---|---|---|---|
| `ctl-coarse` | `ui.primaryPointerCapabilities = ui.allPointerCapabilities = 1` | `css.pointerFine`, `css.hoverHover`, `css.anyPointerCoarse` (the shim cannot hide CSS-level media) | everything else hard |
| `ctl-touch` | `dom.w3c_touch_events.enabled = 1`, `dom.w3c_touch_events.legacy_apis.enabled = true` (Android's default; desktop default is false), `dom.maxtouchpoints.testing.value = 5` | `touch.createEvent` (engine-level, the shim hides the rest) | everything else hard |
| `ctl-noext` | no extension | `screen.width/height/availHeight`, `geo.position` (shim-only values) | `ua.*`, `touch.maxTouchPoints`, `touch.createEvent`, `css.pointerFine`, `css.hoverHover`, `intl.languages`, `intl.timeZone`, `server.acceptLanguage` (pref/env-level) |

A control run is OK only when every `mustFail` id failed, nothing outside `mustFail`/`mayFail` failed, every
`mustPass` id passed and `summary.overall === "FAIL"`.

## Red-team pages (`harness/redteam/`)

`node tool/headless/run.mjs --page harness/redteam/<n>-<name>.html --personas <id>` loads one page with the
real prefs + extension and fails on the word `DETECTED`; `harness/redteam/run-all.sh` runs every page. Pages
09, 10, 12, 30, 31 (taps, GL, voices, gestures, popups) print `needs-device` in headless and are opened on the
phone through the Self-check server (`/t/<token>/redteam/<page>`). A detector that fires either becomes a
harness signal (RT-01…09 did) or a `docs/limitations.md` row (fonts, voices, stack lie, stylesheet `device-*`).

## M0 headless results (2026-10-18 fix pass, Firefox 158.0, harness 1.1.0, reports in `reports/headless/m0/`)

| Run | Overall | Hard FAIL | Libraries | Soft FAIL (documented) |
|---|---|---|---|---|
| `win-firefox__us-east` | PASS | — | 10/10 | `contexts.worker.hardwareConcurrency`, `contexts.sharedWorker.hardwareConcurrency` (M2; 8 vs the host's 2 cores) |
| `win-firefox__device` | PASS | — | 10/10 | same two |
| `win-firefox__de` | PASS | — | 10/10 | `intl.locale` (en-US-only headless build), same two |
| `mac-firefox__device` | PASS | — | 10/10 | same two |
| `linux-firefox__jp` | PASS | — | 10/10 | `intl.locale`, same two |
| `win-chrome__uk` | PASS | — | 10/10 | `ua.vendor` (UA-only persona), same two |
| `real-device__device` | PASS | — | INFO (no expectation) | — (touch-host signals SKIPPED off-device) |
| `real-device__jp` | PASS | — | INFO | `intl.locale`; touch-host signals SKIPPED |
| `ctl-coarse` | FAIL as designed | `css.pointerFine`, `css.hoverHover`, `css.anyPointerCoarse` | 10/10 | `css.anyHoverHover`, the two worker ones |
| `ctl-touch` | FAIL as designed | `touch.createEvent` | 10/10 | the two worker ones |
| `ctl-noext` | FAIL as designed | `screen.width`, `screen.height`, `screen.availWidth`, `screen.availHeight`, `geo.position` | 10/10 (prefs alone satisfy every library) | `geo.accuracy`, `geo.permission` |

Every `scope: both` hard signal (incl. the new `contexts.dataFrame/blobFrame/sandboxFrame/crossRealmToString/sharedWorker`
and `media.device*/resolution*` rows) and every library verdict passes for the desktop personas; device-scoped
signals (`layout.clientWidth`, `css.bp*`, `touch.orientation`, `interactive.*`, `css.maxDeviceWidth480`,
`css.minResolution2`, WebGL strings without a GL context) are SKIPPED in headless and are evaluated on the phone.
Red-team pages 01, 02, 03, 05, 06, 07, 08, 13 (and 04 with the documented worker gap as info) print no
`DETECTED` for `win-firefox__us-east`, `win-chrome__uk`, `mac-firefox__device`, `linux-firefox__jp`
(`reports/redteam/m0/`).

## Adding a signal

1. Add a probe value in `probes/<group>.js` (pure measurement, no expectations).
2. Add a row to `SIGNALS` in `evaluate.js` with `id`, `group`, `tier`, `scope`, `desc`, `check`.
3. Add the expected value to `gen-expectations.mjs` **and** to Kotlin `Report.kt` if it is persona-derived;
   regenerate `harness/personas/`.
4. Cover it in `test/evaluate.test.mjs` with the desktop, phone and partial-spoof fixtures.
5. If a control should break it, add it to `mustFail` in `run.mjs`.
6. Run `make unit headless`; copy the reports into `reports/headless/<milestone>/` when the set is final.
