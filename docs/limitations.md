# Known limitations and tells

Everything a site could still use to tell Hatch from the desktop it claims to be, grouped by whether it is an
engine limit, a shim limit (some are M2 items), a persona-specific tell or a web-compat cost. Each entry names
the harness signal or red-team page that tracks it. Update this file whenever a red-team page succeeds or a
device run disagrees with headless. The M0 red-team round (pages `harness/redteam/01`–`13`, `30`, `31`) and
its fix pass are folded in below; vectors that were *fixed* are listed at the end so the reasoning survives.

## Engine-level (cannot be fixed from the app)

| Tell | Detail | Tracked by |
|---|---|---|
| Stylesheet `device-width` / `device-height` / `device-aspect-ratio` / `resolution` | Gecko evaluates these media features from the real display (`nsMediaFeatures GetDeviceSize` → `DeviceContext()->GetDeviceSurfaceDimensions()`, no desktop-viewport branch at FIREFOX_158_0_RELEASE). The shim keeps **JS** `matchMedia` coherent with `screen.*` / `devicePixelRatio` (RT-04), but a stylesheet `@media (max-device-width: 480px)` / `(min-resolution: 2dppx)` still applies on the phone (~412 px, ~2.6 dppx). Legacy mobile stylesheets gated this way keep their mobile rules. A page can also compare a JS query with a CSSOM-inserted rule. The one engine override path, `nsIDocShell.deviceSizeIsPageSize`, has no GeckoView API (unverified for a pref). | `css.maxDeviceWidth480`, `css.minResolution2` (soft, device: expected FAIL on the phone, recorded), `media.deviceWidthVsScreen` / `media.resolutionVsDpr` (hard, JS side), red-team 05 section 2 |
| Orientation in portrait | The forced desktop viewport keeps the display aspect ratio, so `(orientation: portrait)` and `screen.orientation` stay portrait when the phone is held upright, while `screen.width/height` say 1920×1080. Hold the phone sideways for sites that care. The shim reports `landscape-primary` only when the persona screen is landscape. | `media.orientation`, `css.orientation` (soft, INFO) |
| `outerHeight > screen.height` in portrait | `outerHeight = innerHeight + (mozInnerScreenY − screenY)` (the engine's own chrome offset, RT-09); in portrait `innerHeight` (~2800 CSS px at a 1366 px layout width) exceeds the spoofed 1080. | `layout.outerVsScreen` (soft), `layout.chromeGeometry` (soft; must PASS) |
| Fullscreen loses the desktop viewport | `Document::GetViewportInfo` ignores `ForceDesktopViewport()` while an element is fullscreen (`ignoreMetaTag` when `Fullscreen()`, bug 1696717, before the `browser.viewport.desktopWidth` branch at FIREFOX_158_0_RELEASE): the layout viewport becomes the display size, desktop sites switch to their mobile breakpoint and `innerWidth` contradicts `screen.width` while fullscreen. Video-only fullscreen (YouTube player) is mostly unaffected; fullscreen editors/games lay out for a phone. No pref-level override. Use landscape; a site rule can pin `min-width` on `html:fullscreen`. | device checklist site audit #14 note (HATCH-WC-07) |
| Fonts | Android ships a different font set than Windows/macOS: width probes find none of Segoe UI / Calibri / Tahoma / Consolas (headless Linux stands in: `DETECTED fonts.platformCore` with `linux: DejaVu Sans, Liberation Sans`), and on the phone Roboto / Noto Color Emoji / Droid Sans Mono are present. Not spoofable without a font-metrics shim (out of scope). `linux-firefox` is the persona whose font expectations a Noto-based Android comes closest to. | red-team 11 (permanent, expected fired; record the device result in `docs/m0-results.md`) |
| Speech voices, media codecs, API surface | `speechSynthesis.getVoices()` URIs are `moz-tts:android:<locale>` on GeckoView (`SpeechSynthesisService.java`) vs `urn:moz-tts:sapi|osx|speechd` on desktop; `MediaCapabilities`, Android-only or desktop-only Web APIs differ. Not spoofed. | red-team 12 (device) |
| WebGL capabilities | The shim replaces only `UNMASKED_VENDOR/RENDERER`; `MAX_TEXTURE_SIZE`, `ALIASED_LINE_WIDTH_RANGE` and the compressed-texture extension set (ETC/ASTC vs S3TC) still describe the Adreno/Mali GPU; `webgl.sanitize-unmasked-renderer` is already true. Workers (OffscreenCanvas) see the real strings too (M2). | `webgl.vendor/renderer` (device), red-team 10 (device) |
| Pointer input source | With `dom.w3c_touch_events.enabled = 0` Gecko still delivers pointer/mouse/click events with `pointerType "touch"` / `mozInputSource 5` (`PointerEventHandler::InitPointerEventFromTouch`, `APZCCallbackHelper` synthesized taps carry `MOZ_SOURCE_TOUCH`). The shim masks **trusted** events to `mouse` / `1` and clamps `width/height/pressure` (HATCH-WC-02), so JS sees a mouse; `pointerId` stays the touch id (info only) and the engine's gesture plumbing is unchanged (see web-compat). | `interactive.pointerEvents` (hard, device), `interactive.pointerType` (soft, device), red-team 09 (device), 30 |
| `win-chrome` is UA-only | Gecko cannot be made Blink: `navigator.vendor` is `""` (Chrome: `"Google Inc."`), `oscpu` exists, `uaFullVersion`/`fullVersionList` are `<major>.0.0.0`, no Chrome-only APIs, Gecko CSS/JS engine quirks. Use it for server-side UA sniffers only; default to `win-firefox`. | `ua.vendor` (soft, documented FAIL), library verdicts still PASS |
| `intl.locale` in headless | The Linux Firefox tarball is en-US only, so `Intl.DateTimeFormat().resolvedOptions().locale` and `(1234.5).toLocaleString()` stay en-US for `jp`/`de` in headless (red-team 07 `intl.dtfLocaleInLanguages`, `intl.toLocaleDefaultVsLang` fire there). GeckoView on Android carries all locales and `runtime.settings.locales` applies; the signal is **hard on the phone** (RT-12) and must PASS for `de`/`jp`. | `intl.locale` (soft headless / hard device), red-team 07 (device gate) |
| Time zone needs a restart | `TZ` is process environment read at `GeckoRuntime.create`. The app restarts via `AppRestart` (a `:restart` trampoline). If M0-e shows `TZ` not reaching content processes, enable the `tzShim` setting. | `intl.timeZone`, `contexts.worker.timeZone` |
| `document.createEvent("TouchEvent")` with "Keep touch events" | The Settings toggle (HATCH-WC-06) leaves `dom.w3c_touch_events.enabled` at the engine default so gesture sites work; the shim still removes `TouchEvent`/`ontouch*`/`maxTouchPoints`, but `createEvent("TouchEvent")` succeeds (engine-level, not shimmable). Off by default. | `touch.createEvent` (hard; FAILs while the toggle is on) |

## JS shim limits (documented, some are M2)

| Tell | Detail | Tracked by |
|---|---|---|
| Stack lie | Every shimmed getter/method is a JS function, so a brand-check `TypeError` (`getter.call({})`) or a wrong-receiver `matchMedia` call carries one extra frame `<name>@<anonymous code>:<line>` (`<anonymous code>` is Gecko's filename for `contentScripts` `code:` strings). No JS-level fix exists; a `file:` injection would print the `moz-extension://` URL, which is worse. Geolocation callbacks are clean (RT-06: they are the timer callback itself). | red-team 01 section 4 (info, documented), 08 `geo.callbackStack` (must be clean) |
| Worker scope not shimmed | `navigator.hardwareConcurrency`, WebGL (OffscreenCanvas), geolocation and the `Date`/`Intl` shim do not reach dedicated, shared or service workers. UA, platform, languages and `TZ` are pref/env-level and *do* agree. Gecko has no content-script-for-workers API; the only route is wrapping the `Worker`/`SharedWorker` constructors and `serviceWorker.register` to prepend a bootstrap, which changes `Worker.toString`, script URLs and CSP behaviour — evaluate before M2. Until then presets use `hardwareConcurrency: 8` (the common flagship core count) so the mismatch vanishes on most phones. | `contexts.worker.hardwareConcurrency`, `contexts.sharedWorker.hardwareConcurrency` (soft FAIL in headless: 8 vs the host's 2), `contexts.worker.*`, `contexts.sharedWorker` (hard, PASS), red-team 04 |
| Iframe before the shim | A page can read `contentWindow` of a freshly created `about:blank`/`srcdoc`/`javascript:` iframe before `contentScripts.register` injects (`document_start` still runs after the realm exists). Mitigated by `allFrames + matchAboutBlank + matchOriginAsFallback`, not eliminated; in headless the synchronous read already sees the shim. | `contexts.aboutBlankFrame` (immediate + 50 ms snapshot), red-team 03 `aboutBlank.sync` |
| Proxy plugins (`win-chrome`) | The Chrome-shaped `PluginArray`/`MimeTypeArray` are Proxies over `Object.create(<native proto>)`; `instanceof` and prototype methods work, a structured-clone trick could tell. Only used when Gecko reports no plugins. | `misc.plugins` (soft) |
| TZ shim scope | `Date.parse` / `new Date("2026-01-01T00:00")` (local ISO strings without offset) and `Temporal` still use the real zone; `toLocale*` gets the fake zone only when `timeZone` is a string; `Intl.DateTimeFormat` is wrapped (same prototype, constructor fixed up) and prints native. Fallback only. | `intl.dateOffsetCoherence` (soft), `contexts.worker.timeZone` (soft when `tzShim`) |
| Double injection | There is no idempotency marker any more (RT-07): if Gecko ever injected the shim twice into one document the accessors would be wrapped twice. Behaviour stays correct (verified in `shim.test.mjs`), only the extra frame appears. Registered content scripts inject once per document, so this does not happen in practice. | `misc.descriptorShape` FAILs on any `hatch`-like own symbol |
| Shim size budget | `persona-shim.js` is 30,716 bytes against the 30,720-byte (30 KB) budget after the M0 fix pass; the next red-team fix needs the M1 minified build (`PERF-03`). Cold compile+execute per frame: median ~2.5 ms (win-firefox), ~3.3 ms (win-chrome) on a 2-vCPU server core; expect 2–3× on a phone core. | `test/shim.test.mjs` size test, `test/shim-bench.test.mjs` |
| GM API subset | No `GM_xmlhttpRequest`, `GM_getResourceText`, `GM_openInTab`, `GM_notification`, `@require` loading in M1. `GM_*` values live in the extension's `storage.local` for ISOLATED rules (page-invisible, per rule, shared across origins) and in page `localStorage` for MAIN-world rules (page-readable; documented). | `security.test.mjs` SEC-8 |
| Eruda `_isInit` | `eruda-init.js` checks an internal eruda flag; a future eruda rename just re-runs `eruda.init` (eruda guards double init). | — |

## Web-compat (the price of `touch = false`)

| Behaviour | Detail | Mitigation |
|---|---|---|
| Gestures on `touch-action: none` / `preventDefault()` elements | `dom.w3c_touch_events.enabled = 0` is used as a "no touch screen" switch, but it also disables the APZ touch-action plumbing while the input keeps flowing: trusted `touchstart/touchmove` *and* pointer events still reach `addEventListener`, yet `EventListenerManager::IsApzAwareEvent` is false for touch listeners and `nsIFrame::GetCompositorHitTestInfo` skips the `touch-action` flags, so APZ pans/pinches the page over a canvas, map or carousel and may fire `pointercancel` (HATCH-WC-06). Taps are clean (pointer events, click). | Settings → **Keep touch events (sites that need gestures)**: leaves the pref at the engine default (gestures work, `document.createEvent("TouchEvent")` becomes a tell, everything else of the persona stays); one-tap "Persona off (real device)"; per-site persona overrides are M2 |
| Long-press selection | Gecko's touch selection UI (AccessibleCaret) is gated on the touch pref (`PresShell::AccessibleCaretEnabled`); Hatch sets `layout.accessiblecaret.enabled = true` for every `touch = false` identity so long-press selects a word and the handles appear (HATCH-WC-03). Caret dragging across APZ panning is an M0 device check. | golden prefs, device checklist M0-g, red-team 30 step 4 |
| Sticky `:hover` | With `hover: hover` forced, a tap leaves the element hovered until the next tap (desktop semantics). | expected desktop behaviour |
| On-screen keyboard | Edge-to-edge Compose ignores `adjustResize`; the browser applies the IME as a window inset (`Scaffold contentWindowInsets ∪ WindowInsets.ime`, HATCH-WC-04) so the EngineView shrinks, Gecko resizes its viewport and scrolls the focused field into view; `100vh` content reflows like a resized desktop window. | device checklist (input in the bottom third of a desktop page) |
| Popups / `target="_blank"` | AC builds popup sessions from `DefaultSettings` (UA only, `VIEWPORT_MODE_MOBILE`). Hatch applies the persona in the parent's `onWindowRequest` before GeckoView opens the child session and again when the tab is linked (HATCH-WC-05). | red-team 31 (device), device checklist |
| Long-press | Fires the page's `contextmenu` first (right-click semantics), then AC's context menu. | M0-g gate |
| `window.orientation` removed | Some mobile-only code paths check it; removal is intended. | `touch.orientation` (device) |
| Host rewrites on links | Followed links and typed URLs rewrite identically (`UrlRewriteRules.extensionHostRewrites` = exact hosts + Wikimedia + generic `m.`/`mobile.`/`touch.` strip as regex rules, HATCH-WC-01); Amazon `/gp/aw/` and `mf_useformat` / `app=m` query rules still apply to typed URLs only (M1: move into `onBeforeRequest`, add a per-host disable list). | `hostrewrite-gaps.test.mjs`, `UrlRewriteRulesTest`, site audit #2 |

## Headless harness vs. the phone

| Difference | Why it matters |
|---|---|
| Layout width, CSS breakpoints, `interactive.*`, `window.orientation`, WebGL renderer (no GL in headless), `css.maxDeviceWidth480`, `css.minResolution2` | `scope: "device"` signals — SKIPPED in headless, evaluated on the phone (`summary.overall` requires no device-scoped SKIPPED in `env=device`; the two css.* ones are soft) |
| `real-device` identity in headless | The host is a desktop, so the "real touch screen" expectations become SKIPPED (`touchHost` rule in `evaluate.js`); on the phone they are hard |
| `general.useragent.override` stands in for the per-session UA override | Same string, different mechanism; `server.userAgent` and `ua.userAgent` cover both |
| `geo.provider.network.url` is pointed at a dead endpoint and the prompt auto-allowed | Without the shim (`ctl-noext`) geolocation fails fast instead of hanging; with the shim the position is synthetic anyway |
| Memory-trim prefs (`dom.ipc.processCount=1`, no disk cache, telemetry off) | Not representative of GeckoView's process model; the context-consistency signals still exercise a separate content realm per frame/worker |
| Shim readiness | The runner waits for a shim-only value (`screen.width`, `hardwareConcurrency` or the granted geolocation permission) on `frame.html`; there is no marker symbol any more |

## Red-team vectors closed in the M0 fix pass (kept for the record)

| Finding | Vector | Fix | Regression |
|---|---|---|---|
| RT-01 | shimmed getters printed `function get name() {…}` (natives print the bare atom) | `natives` map stores the bare name; `fn.name` keeps `get name` | `misc.toStringNative` exact text, red-team 01 §1 |
| RT-02 | a fresh realm's `Function.prototype.toString` dumped the shim source | cross-realm delegation to the function's own realm's `toString` | `contexts.crossRealmToString` (hard), red-team 01 §5, 03 |
| RT-03 | `data:` / `blob:` / sandboxed frames never got the shim | `matchOriginAsFallback: true` (fallback to `matchAboutBlank` if rejected) | `contexts.dataFrame/blobFrame/sandboxFrame` (hard), red-team 03 §3 |
| RT-04 | JS `device-width/height/aspect-ratio/resolution/-webkit-device-pixel-ratio` contradicted `screen.*` | `matchMedia` rewrite evaluates them from the persona screen | `media.deviceWidthVsScreen` etc., red-team 05 §2 (stylesheet level stays an engine limit, above) |
| RT-05 | `MediaQueryList.media` echoed the raw query for rewritten queries | stores the engine's serialisation of the original query | `media.serialization` (soft), red-team 05 §4 |
| RT-06 | geolocation callbacks ran under `fire@<anonymous code>` | the callback is the timer callback itself | red-team 08 `geo.callbackStack` |
| RT-07 | `Symbol.for('hatch.persona')` own symbol on `window` | no marker; double injection tolerated | `misc.descriptorShape`, red-team 02 §1 |
| RT-08 | own-property order `[name, length]` (natives: `[length, name]`, lazy) | functions are born with computed names and declared arity | `misc.descriptorShape` `getterOwnShape`, red-team 01 §3 |
| RT-09 | `outerHeight − innerHeight` (85) ≠ `mozInnerScreenY − screenY` (86) | outer sizes derived from the engine chrome offset | `layout.chromeGeometry`, red-team 06 |
| RT-14 | the old row here claimed desktop Firefox exposes `share`/`vibrate` | corrected: `dom.webshare.enabled` is true only in `geckoview-prefs.js` (Android), `dom.vibrator.enabled` and `dom.netinfo.enabled` are false everywhere at 158, so deleting them is right | red-team 02 §5 |

## Security posture (M0, after the fix pass)

- The MAIN-world shim holds no privileged handle; native ports are only reachable from extension code.
- The manifest grants `nativeMessagingFromContent` (privileged, allowed for built-in extensions) so the per-tab
  `hatch-tab` port from `content/bridge.js` is accepted by GeckoView (SEC-1).
- ISOLATED-world rules cannot reach `browser.*` / `chrome.*` (hidden before the user script runs, SEC-2); the
  background restores only the cached persona at startup, rules and host rewrites wait for the app (SEC-3);
  template tokens are substituted in one pass so no persona string or rule metadata can change what runs
  (SEC-4); bridge self-check reports are accepted only from the session the controller asked, under the
  server's base URL and within the 2 MB cap (`BridgeReportFilter`).
- `LocalHttpServer` binds 127.0.0.1 only, uses a random 128-bit token path (never logged), lives only while
  the Self-check screen is open, caps request bodies at 2 MB and headers at 64 lines / 64 KB, runs at most 32
  connections, closes every socket on stop and refuses `..`/absolute asset paths (SEC-5, PERF-04).
- The FileProvider exposes only `cacheDir/share/` and the external `reports/` directory (SEC-6).
- "Install add-on from URL" accepts `https://` only (SEC-7); GeckoView still requires Mozilla signatures.
- `intent://` / APK downloads go through AC's `AppLinksFeature` / `DownloadsFeature` confirmations.
- Eruda is injected only on user action.
