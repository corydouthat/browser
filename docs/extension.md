# The built-in "bridge" extension

`app/src/main/assets/extension/` is a Manifest V2 WebExtension (`bridge@hatch.app`, `strict_min_version
128.0`) that GeckoView loads at startup via `Engine.installBuiltInWebExtension("resource://android/assets/extension/")`.
It is the only way to run code **inside the page world synchronously at `document_start`** (needed for the
persona shim) and to inject Eruda on strict-CSP sites. MV2 is required because `contentScripts.register`
with `world: "MAIN"` and inline `code` has no MV3 equivalent in Gecko 158 (`userScripts` MV3 is the documented
fallback, with our own PromptDelegate auto-grant).

| File | World | Purpose |
|---|---|---|
| `manifest.json` | — | permissions `<all_urls>, tabs, storage, nativeMessaging, nativeMessagingFromContent, scripting, webNavigation, webRequest, webRequestBlocking`; persistent background; one static content script |
| `background.js` | extension | native port to the app, shim/rule registration, Eruda, host rewrites, `storage.local` cache |
| `content/bridge.js` | ISOLATED, top frame, `document_start`, `match_about_blank` | per-tab native port `hatch-tab` (Eruda toggle, Self-check report hand-over) |
| `shim/persona-shim.js` | MAIN, every frame, `document_start` | the persona shim; `__PERSONA__` is replaced by the config JSON before registration |
| `gm/gm-shim.js` | per rule (MAIN or ISOLATED) | Greasemonkey subset prepended to every JS rule |
| `eruda-init.js`, `vendor/eruda.min.js` | MAIN, on demand | Eruda 3.4.3 (MIT, see `THIRD_PARTY.md`) |
| `test/*.test.mjs` | node | unit tests (`node --test test/*.test.mjs` from the extension directory); excluded from the APK |

## Native messaging contract

Background port name **`hatch`**, per-tab content port name **`hatch-tab`**. The app side is
`app/src/main/kotlin/app/hatch/ext/BridgeExtension.kt` (`registerBackgroundMessageHandler` /
`registerContentMessageHandler`, Android Components `WebExtension` API).

| Direction | Message |
|---|---|
| app → background | `{"type":"state","persona":<shim config>,"rules":[SiteRule],"hostRewrites":[{"from","to"}],"settings":{}}` — sent on **every** port connect |
| app → background | `{"type":"persona","persona":<shim config>}`, `{"type":"rules","rules":[...]}`, `{"type":"hostRewrites","rules":[...]}` |
| background → app | `{"type":"hello","version":"0.1.0"}` on connect, `{"type":"log","level":"info"|"warn"|"error","message":"..."}` |
| app → content | `{"type":"eruda"}` (toggle), `{"type":"selfcheck-run"}` |
| content → app | `{"type":"ready","url":"..."}` when the port is up, `{"type":"selfcheck-report","report":<harness result or null>,"url":"...","error"?:"timeout"|"no result"}` |

`SiteRule` wire format: `{"id","name","matches":[],"excludeMatches":[],"js":string|null,"css":string|null,
"world":"MAIN"|"ISOLATED","runAt":"document_start"|"document_end"|"document_idle","enabled":bool,"source":string|null}`
(`app/src/main/kotlin/app/hatch/rules/SiteRulesRepository.kt`, persisted in `filesDir/rules.json`).

`nativeMessagingFromContent` is what GeckoView needs to accept a `connectNative` from a *content script*
(`GeckoViewWebExtension.sys.mjs exportFlags()` sets `ALLOW_CONTENT_MESSAGING` only with it; without it
`WebExtensionController.getDelegate()` answers "This NativeApp can't receive messages from Content Scripts").
It is a privileged permission; a built-in extension installed through `ensureBuiltIn` qualifies. `web-ext lint`
reports it as unknown (warning, not error).

### Startup race

The background script restores the last cached **persona** from `storage.local` and registers the shim
**before** it opens the native port, so a cold start is never unshimmed even if the app's handler is not ready
yet. Cached rules and host rewrites are *not* restored (code-bearing / navigation-altering state waits for the
app's `state` message, SEC-3); a malformed cached persona is ignored. The port reconnects with exponential
backoff (500 ms → 30 s, reset by any traffic); the app pushes the full state on every `onPortConnected`.

### Template substitution

`persona-shim.js` and `gm-shim.js` are templates; `background.js substitute()` replaces every token
(`__PERSONA__`, `__RULE_ID__`, `__RULE_META__`, `__GM_VALUES__`, `/*__USERSCRIPT__*/`) in **one** left-to-right
pass, so substituted text is never re-scanned and the header comments never mention a token: a `*/`, a token
name or the userscript marker inside persona strings or rule metadata stays inside its JSON string literal
(SEC-4, locked by `security.test.mjs`).

The headless harness stands in for the app with `tool/headless/run.mjs`'s `headless-native.js`, prepended to
`background.scripts`: it overrides `browser.runtime.connectNative("hatch")` with a fake port that delivers the
same `{type:"state"}` message built from the golden `shim` object.

## Persona shim (`shim/persona-shim.js`, ≤ 30 KB, ~2–3 ms cold per frame)

Registered with
`contentScripts.register({matches:["<all_urls>"], allFrames:true, matchAboutBlank:true, matchOriginAsFallback:true,
runAt:"document_start", world:"MAIN", js:[{code}]})`; unregistered and re-registered on every persona push;
**not registered at all** when `enabled` is `false` (`real-device` with no geo region). `matchOriginAsFallback`
(toolkit `content_scripts.json` @158) also injects into `about:`, `data:`, `blob:` and sandboxed (opaque-origin)
frames created by a matching page, which `matchAboutBlank` alone does not cover (RT-03); if an engine rejects
the option the background logs a warning and registers without it.

Rules the shim follows (checked by `test/shim.test.mjs` and the harness `misc.*` signals):

- Prototype accessors only (`Navigator.prototype`, `Screen.prototype`, …), `configurable: true`,
  `enumerable` copied from the original descriptor; no own data properties on `navigator` / `screen`.
- One `Function.prototype.toString` patch backed by a `WeakMap`, installed only when the shim is enabled, so
  every shimmed getter/method prints exactly what SpiderMonkey prints for a native: `function name() {\n    [native
  code]\n}` with the **bare** name even for accessors (`fn.name` stays `get name`, RT-01). Functions are created
  with computed names and declared arity, so `Object.getOwnPropertyNames(fn)` is `[length, name]` and lazy like
  a native (RT-08). The patch is cross-realm aware: a function from another realm is printed by *that* realm's
  (possibly patched) `toString`, so the "pristine iframe natives" trick prints native text in both directions
  (RT-02). Getters have no `.prototype`, are not constructible and stay brand-checked (`TypeError` on a wrong
  `this`).
- No page-visible marker (RT-07): nothing is added to `window` (no own symbols, no named globals); the headless
  runner and the harness detect "shim active" from shim-only values. A second injection would re-wrap harmlessly.
- Synchronous, no network, every section wrapped so one failing section cannot kill the rest.
- `null` in the config means "leave the engine value alone".

What it overrides (only when the field is present):

| Config field | Effect |
|---|---|
| `desktop && !touch` | `maxTouchPoints → 0`; delete `ontouch*` from `window`, `Window/Document/HTMLElement/SVGElement/MathMLElement.prototype`; delete `TouchEvent/Touch/TouchList`, `window.orientation/onorientationchange`; `screen.orientation.type/angle → landscape-primary/0`; **trusted** `PointerEvent.pointerType "touch" → "mouse"`, `width/height → 1`, `pressure → buttons ? 0.5 : 0`, `MouseEvent.mozInputSource 5 → 1` (synthetic events untouched, HATCH-WC-02); `matchMedia` rewrites `pointer`/`hover`/`any-pointer`/`any-hover` to a forced true/false and, when a `screen` is configured, evaluates `(min-\|max-)device-width/height`, `device-aspect-ratio`, `resolution` (dppx/x/dpi/dpcm) and `-webkit-/-moz-(min-\|max-)device-pixel-ratio` from the persona screen (RT-04); everything else is delegated and `.media` is the engine's own serialisation of the original query (RT-05). Engine prefs already do the pointer/touch part; the shim is the belt-and-braces layer. |
| `screen` | `Screen.prototype.width/height/availWidth/availHeight/colorDepth/pixelDepth/availLeft/availTop`, `devicePixelRatio`, `outerWidth = innerWidth + 2·max(0, mozInnerScreenX − screenX)`, `outerHeight = innerHeight + (mozInnerScreenY − screenY)` (85 when the engine reports nothing usable, RT-09) |
| `hardwareConcurrency` | `Navigator.prototype.hardwareConcurrency` |
| `desktop` | delete `navigator.vibrate/share/canShare`; `pdfViewerEnabled → true` |
| `userAgent/platform/oscpu/appVersion/languages` | left **native** when the engine value already matches (prefs did the work); overridden only on a mismatch |
| `uaData` | `navigator.userAgentData` (`NavigatorUAData` with frozen `brands`, `mobile:false`, `platform`, `getHighEntropyValues` resolving only requested known hints, `toJSON`, `Illegal constructor`) |
| `chromeLike` | 5 Chrome-shaped `plugins` / 2 `mimeTypes` (Proxies over the native prototypes) only when Gecko reports none |
| `webgl` | `UNMASKED_VENDOR_WEBGL` / `UNMASKED_RENDERER_WEBGL` only after `getExtension('WEBGL_debug_renderer_info')` (native behaviour); the extension is added to `getSupportedExtensions` |
| `geolocation` | `getCurrentPosition/watchPosition/clearWatch` return `GeolocationPosition`/`GeolocationCoordinates` instances with ≤ 3 m jitter and the configured accuracy; the user callback is the timer's own callback (no shim frame on its stack, RT-06); `permissions.query({name:'geolocation'})` → `granted` |
| `tzShim && timeZone` | `Date` local getters/setters/`getTimezoneOffset`/`toString`/`toDateString`/`toTimeString`/`toLocale*`, `Intl.DateTimeFormat` default zone, from the `tzOffsets` table (or Intl when absent). Fallback only; `TZ` env is the primary mechanism. |

Known tells are listed in `docs/limitations.md` (worker scope, iframe-before-shim, stack lie, Proxy plugins,
stylesheet-level `device-*`, win-chrome API surface).

## Site rules

Each enabled rule becomes one `contentScripts.register` call: `matches`, `excludeMatches`, `runAt`
(default `document_idle`), `js` (wrapped in `gm/gm-shim.js`, `world` default ISOLATED) and/or `css`
(`cssOrigin: "user"`). Invalid rules (no id, no matches, neither js nor css, bad match pattern) are skipped with
a `{type:"log",level:"warn"|"error"}` back to the app, never fatal.

GM subset provided by `gm-shim.js`: `unsafeWindow`, `GM_addStyle`, `GM_getValue/setValue/deleteValue/listValues`,
`GM_log`, `GM_info` (script name/id/source/matches/runAt/world, extension version, `storage` backend), `GM.*`
promise variants, `GM_registerMenuCommand` (no-op that logs). The user script is the *first statement* of an
inner function so its own `'use strict'` applies only to it. Not provided in M1: `GM_xmlhttpRequest`,
`GM_getResourceText`, `GM_openInTab`, `GM_notification`, `@require`.

GM storage (SEC-8): for **ISOLATED** rules values live in the extension's `storage.local` under
`gm:<ruleId>:<key>` — per rule, shared across every origin the rule matches, invisible to the page. The
background inlines the rule's current values (`__GM_VALUES__`) when it registers the rule, so `GM_getValue` is
synchronous from that snapshot; writes go to `storage.local` and reach other tabs through
`storage.onChanged`. **MAIN**-world rules have no extension API and keep page `localStorage`
(`__hatch_gm_<ruleId>_<key>`, page-readable; documented).

Sandbox (SEC-2): a rule has userscript privileges, not the bridge's. The wrapper captures the `storage.local`
handle it needs and then redefines `browser` and `chrome` on the content-script global as `undefined`,
non-configurable (the sandbox defines them as configurable lazy getters, `Schemas.exportLazyGetter` @158), so
`typeof browser`, `globalThis.browser` and `this.browser` are all `undefined` inside the user script. In the
MAIN world nothing is defined (the page's `window.browser`, if any, is untouched).

## Eruda

`{type:"eruda"}` on the tab port → `bridge.js` → `runtime.sendMessage({type:"eruda-toggle"})` → background
`scripting.executeScript({target:{tabId}, world:"MAIN", files:["vendor/eruda.min.js","eruda-init.js"]})`.
`executeScript` is CSP-exempt, so Eruda works on strict-CSP sites (GitHub). A second toggle runs
`eruda.destroy()`; navigation (`webNavigation.onCommitted`, frame 0) resets the per-tab state. Eruda is only
ever injected on user action (menu item or Ctrl+Shift+I).

## Host rewrites

`{type:"hostRewrites"}` installs a blocking `webRequest.onBeforeRequest` on `main_frame` http(s) requests
that redirects known mobile hosts to their desktop host **only while `persona.enabled && persona.desktop`**.
Rule shape: `{from, to}` exact hostnames (case-insensitive) or `from = "/regex/"` with `$n` groups in `to`.
A per-tab, per-host 10 s guard stops redirect loops; the guard is cleared when the tab closes. The app sends
`UrlRewriteRules.extensionHostRewrites` (`:persona`, HATCH-WC-01): the 12 exact hosts (`m.youtube.com`,
`m.facebook.com`, `mobile.twitter.com`, `m.twitter.com`, `mobile.x.com`, `mbasic.`/`touch.facebook.com`, …)
followed by two regex rules, `/^([a-z-]+)\.m\.(wikipedia|wiktionary|…|wikidata)\.org$/` → `$1.$2.org` and
`/^(?:m|mobile|touch)\.([^.]+\.[^.]+.*)$/` → `$1` (one leading label, only when ≥ 2 labels remain).
`UrlRewriteRulesTest` proves the table equals `UrlRewriteRules.desktopHost` on a host corpus, so followed links
and typed URLs rewrite identically. Amazon `/gp/aw/` and the `mf_useformat` / `app=m` query rules still apply
to address-bar input only (`BrowserScreen.normalizeInput`); moving them into `onBeforeRequest` and a per-host
disable list are M1 items.

## Lint and tests

```
cd app/src/main/assets/extension && node --test test/*.test.mjs        # 65 tests (make unit-ext runs them)
flock /tmp/hatch-heavy.lock npx --yes web-ext@10 lint --source-dir app/src/main/assets/extension --self-hosted --ignore-files 'test/**'
```

`web-ext lint` reports 0 errors; the remaining warnings are inherent to the eruda build (`DANGEROUS_EVAL`,
`UNSAFE_VAR_ASSIGNMENT`), `MISSING_DATA_COLLECTION_PERMISSIONS` (the key needs Firefox 140, above our
minimum; built-in extensions never go through AMO) and the unknown-permission warning for
`nativeMessagingFromContent` (GeckoView-only, privileged).

## Security notes

- The MAIN-world shim carries no privileged handle: it is a plain string of code with the persona JSON
  inlined; it cannot reach `browser.*`.
- Native ports are opened only by extension code (`bridge.js` runs in the ISOLATED world); pages cannot reach
  them, and rules cannot either (`browser`/`chrome` hidden, SEC-2). The app accepts `selfcheck-report` only from
  the session it asked, under the self-check server's base URL and within 2 MB (`BridgeReportFilter`).
- Rules are user-authored; the GM shim grants no cross-origin network access; GM values of ISOLATED rules are
  page-invisible (`storage.local`).
- Only the cached persona is restored at startup (SEC-3); template substitution is single-pass (SEC-4).
- `assets/extension/test/**` is excluded from the APK (`androidResources.ignoreAssetsPatterns` in
  `app/build.gradle.kts`), verified with `unzip -l`.

## The second built-in extension: `icons@mozac.org` (M1)

`Components.icons.install(engine, store)` (`browser-icons` 158) installs Android Components' favicon collector
from the AAR's `assets/extensions/browser-icons/`: MV2, one content script `icons.js` at `document_end` on
`*://*/*` in the isolated world, permissions `geckoViewAddons`, `nativeMessaging`, `nativeMessagingFromContent`.
It reads `<link rel="icon|shortcut icon|apple-touch-icon|…">` and `<meta property="og:image…">` /
`<meta name="twitter:image|msapplication-TileImage">` with `querySelectorAll` and posts
`{url, icons:[{type, href, sizes, mimeType}]}` to the native app `MozacBrowserIcons`, which `BrowserIcons` answers
with `UpdateIconAction`. It never touches the page world. The headless runner can install it next to the Hatch
extension (`--extra-ext <dir>`, see `docs/detection-harness.md`) and the red-team pages stay clean with it; if a
future AC train changes `icons.js`, re-run that check before upgrading (`docs/architecture.md` upgrade checklist).
It is not the same mechanism as the bridge: the bridge's `hatch-tab` port is per session and carries Hatch
messages; the icons port is AC-internal.

## Rules UI and host-rewrite toggles (rules, 2026-10-18)

The app-side UI for this contract is `ui/rules/*` (site rules list, editor, `.user.js` / `.user.css` import) and the
Settings "Host rewrites" section. Nothing in the wire format changed: rules still travel as the `SiteRule` JSON above
(`RuleValidation` mirrors `ruleProblem()` and adds the match-pattern grammar so a bad pattern is caught before
`contentScripts.register` throws), and host rewrites are still `{type:"hostRewrites", rules:[{from,to}]}` with no
disabled flag: the app sends `UrlRewriteRules.extensionHostRewrites` **minus** the hosts the user switched off
(`AppSettings.disabledHostRewrites`, `HostRewriteFilter.effective`) both in the `state` push at port connect and via
`sendHostRewrites` on every toggle. Imported userscripts are refused when they need `@require`, `GM_xmlhttpRequest`
or a `@grant` outside the subset `gm-shim.js` provides (`UserscriptImport.SUPPORTED_GRANTS`).
