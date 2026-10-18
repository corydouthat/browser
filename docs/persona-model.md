# Persona model

A **persona** is what a website is allowed to see of the device. It is the product of two independently
switchable halves:

```
Persona = Identity × Region
```

| Half | Answers | Preset ids |
|---|---|---|
| `Identity` | *What machine and browser is this?* — UA, platform, screen, touch, pointer, WebGL, client-hint brands | `win-firefox` (default), `mac-firefox`, `linux-firefox`, `win-chrome`, `real-device` |
| `Region` | *Where is it and what language does it speak?* — `navigator.languages` / `Accept-Language`, Intl locale, time zone, geolocation | `device` (default), `us-east`, `uk`, `de`, `jp` |

The model lives in the pure-JVM module `:persona` (`app.hatch.persona`, kotlinx-serialization, no Android
imports) so the same code can back the Android app, the JVM golden tests, and a future iOS port. The preset
JSON in `personas/identities.json` and `personas/regions.json` is the **source of truth**: the `:persona`
module exposes `personas/` as a resource directory, the harness expectation generator reads the same files,
and the golden files are derived from them.

## Data classes

```kotlin
@Serializable data class ScreenSpec(width, height, availWidth, availHeight, colorDepth = 24, dpr = 1.0)
@Serializable data class WebGlSpec(vendor, renderer)
@Serializable data class Brand(brand, version)
@Serializable data class UaDataSpec(brands: List<Brand>, platform, platformVersion, architecture, bitness, model)
@Serializable data class Identity(
    id, name, desktop: Boolean,
    userAgent?, platform?, oscpu?, appVersion?,
    layoutWidth = 1366, screen: ScreenSpec?, hardwareConcurrency: Int?, webgl: WebGlSpec?,
    chromeLike = false, uaData: UaDataSpec? = null, touch = false,
)
@Serializable data class GeoSpec(latitude, longitude, accuracyMeters = 50.0)
@Serializable data class Region(id, name, languages: List<String>, locale, timeZone?, geolocation: GeoSpec?)
@Serializable data class Persona(identity, region) { val desktop get() = identity.desktop; val key = "<identityId>__<regionId>" }
```

A `null` identity field means "leave the engine default alone" (the corresponding pref is *cleared*, the shim
does not touch that property). `real-device` is all-null with `touch = true` and `layoutWidth = 0`.

## Field → mechanism table (`PersonaToPrefs`)

`PersonaToPrefs` is the single table that turns a persona into engine state. Nothing else in the app decides
which pref a field maps to; the goldens lock the table.

| Field | Mechanism | Scope / when |
|---|---|---|
| `desktop` | `EngineSession.toggleDesktopMode(true)` on every tab (`USER_AGENT_MODE_DESKTOP` + `VIEWPORT_MODE_DESKTOP`), `DefaultSettings.getDesktopMode`; `browser.viewport.desktopWidth = layoutWidth` | per session, live; width on next load |
| `userAgent` | `EngineSession.settings.userAgentString` (GeckoView `userAgentOverride`, wins over the UA mode) + pref `general.useragent.override` (so headless desktop Firefox reports the same UA) | per session, live |
| `platform` / `oscpu` / `appVersion` | `general.platform.override` / `general.oscpu.override` / `general.appversion.override` | global, live |
| `touch = false` | `dom.w3c_touch_events.enabled = 0` (no `TouchEvent`, no `ontouch*`; taps still arrive as pointer/mouse events, the shim masks their input source), `dom.maxtouchpoints.testing.value = 0`, `ui.primaryPointerCapabilities = 6`, `ui.allPointerCapabilities = 6` (bits: 1 coarse, 2 fine, 4 hover → fine+hover, drives CSS `pointer`/`hover`), `layout.accessiblecaret.enabled = true` (long-press word selection + handles stay available although the touch pref is off, HATCH-WC-03) | global, live |
| `touch = true` (`real-device`) | the five prefs above are **cleared** | global, live |
| app setting "Keep touch events" | `PersonaToPrefs.prefs(p, keepTouchEvents = true)`: `dom.w3c_touch_events.enabled` is **cleared** (engine default) instead of 0 for a `touch = false` identity, nothing else changes; for gesture sites (maps, drawing, carousels). Goldens and `yaml()` default to `false`. | global, live |
| `languages` | `intl.accept_languages` in Gecko's own format (`"en-US, en"`); drives both the `Accept-Language` header and `navigator.languages` | global, live |
| `locale` | `intl.locale.requested` + `GeckoRuntimeSettings.locales` (Intl default locale, UI strings) | runtime, live |
| `timeZone` | `env: TZ` in `geckoview-config.yaml` → read once at `GeckoRuntime.create` → **restart** (the app offers "Restart now"); optional JS `Date`/`Intl` shim (`tzShim`, Settings) with a `java.time` offset table as fallback | process |
| `geolocation`, `screen.*`, `dpr`, `hardwareConcurrency`, WebGL vendor/renderer, `userAgentData` (chromeLike), plugins (chromeLike), `window.orientation` removal, `matchMedia` safety net, `outerWidth/Height` | JS shim (`persona-shim.js`, MAIN world, `document_start`, every frame) | per document |
| always for desktop | `GeckoRuntimeSettings`: `fontInflation(false)`, `automaticFontSizeAdjustment(false)`, `forceUserScalableEnabled(true)`, `inputAutoZoomEnabled(!desktop)` | runtime |

### Region prefs are independent of the identity

`intl.accept_languages` / `intl.locale.requested` are set by every region except `device`, **even with the
`real-device` identity** ("real phone, but German"). The `device` region clears both so the phone keeps its
own languages. Consequences:

- `real-device__device` clears all 12 owned prefs (the "persona off" control).
- `real-device__jp` clears the 10 identity prefs and sets the 2 intl prefs.
- `ExpectedReport.languages / acceptLanguage / locale` are `null` (not asserted) for the `device` region.

### Shim `enabled`

```
enabled = identity != real-device || region.geolocation != null || (tzShim && region.timeZone != null)
```

With `enabled = false` the extension registers **no** content script at all (nothing to detect). With
`real-device` + a geo region the shim is enabled but every identity field is `null`; the shim treats `null`
as "do not touch".

### Outputs

| Function | Output | Consumer |
|---|---|---|
| `prefs(p): PrefSet(set, clear)` | typed prefs to set on the user branch and prefs to clear | `GeckoPrefs.apply` (live), goldens, headless runner |
| `env(p)` | `{"TZ": zone}` or empty | yaml, headless runner (driver env), `needsRestart` |
| `yaml(p)` | `geckoview-config.yaml` (`prefs:`, `env:`, `args: []`; strings always double-quoted) parsed by `org.mozilla.gecko.util.DebugConfig` | `GeckoRuntimeFactory` (written atomically before `GeckoRuntime.create` and after every switch) |
| `shimConfig(p, clock, tzShim)` | the JSON object below | `BridgeExtension.sendPersona` → `background.js` → inlined as `__PERSONA__` |
| `needsRestart(old, new)` | `env(old) != env(new)` | persona sheet / snackbar |
| `goldenJson(p)` | `{"prefs","clear","env","shim"}`, keys sorted, 2-space indent, fixed clock | `personas/golden/*.prefs.json` |
| `Persona.expectedReport()` | `ExpectedReport` — the values the harness must observe | harness expectations (`tool/headless/gen-expectations.mjs` mirrors it), in-app Self-check |

### Shim config contract (what the extension receives)

```json
{
  "enabled": true, "desktop": true, "touch": false,
  "userAgent": "...", "platform": "Win32", "oscpu": "Windows NT 10.0; Win64; x64", "appVersion": "5.0 (Windows)",
  "layoutWidth": 1366,
  "screen": {"width": 1920, "height": 1080, "availWidth": 1920, "availHeight": 1040, "colorDepth": 24, "dpr": 1.0},
  "hardwareConcurrency": 8,
  "webgl": {"vendor": "...", "renderer": "..."},
  "chromeLike": false,
  "uaData": null,
  "languages": ["en-US", "en"], "locale": "en-US",
  "timeZone": "America/New_York", "tzShim": false,
  "tzOffsets": [{"fromEpochMs": 1735689600000, "offsetMinutes": -300}, ...],
  "geolocation": {"latitude": 40.7128, "longitude": -74.006, "accuracyMeters": 50.0}
}
```

`tzOffsets` is precomputed by `java.time` for now ± 1 year (first row = offset at the window start, then one
row per transition; `offsetMinutes` is east-positive, the opposite sign of `Date.getTimezoneOffset()`). The
app computes it with the real clock; goldens use `PersonaToPrefs.GOLDEN_CLOCK` (2026-01-01T00:00Z) so the
files are deterministic. `tzShim` is `false` in the goldens; the app flips it from Settings.

## Accept-Language: the exact Gecko 158 algorithm

Verified against tag `FIREFOX_158_0_RELEASE`: `nsHttpHandler::PrepareAcceptLanguages` delegates to
`rust_prepare_accept_languages` (`netwerk/base/rust-helper/src/lib.rs`): tokens split on `,`, trimmed of
space/tab and anything from `;`, canonicalised (ASCII-lowercase, 2-letter subtags upper-cased, 4-letter
title-cased, stop at a singleton), q decreasing 0.1 per position with floor 0.1, first entry without q. So the
pref `"en-US, en"` becomes the header **`en-US,en;q=0.9`** (not `;q=0.5` as older docs say), and
`"de-DE, de, en"` becomes `de-DE,de;q=0.9,en;q=0.8`. `navigator.languages` is produced by
`Navigator::GetAcceptLanguages` (split, trim, `xx_YY → xx-YY`, upper-case 2-letter subtags). Both
algorithms are implemented in `GeckoAcceptLanguage` (Kotlin) and `gen-expectations.mjs` (JS) and
table-tested against each other.

## Presets

| Identity | UA | platform / oscpu / appVersion | screen | notes |
|---|---|---|---|---|
| `win-firefox` | `Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0` | `Win32` / `Windows NT 10.0; Win64; x64` / `5.0 (Windows)` | 1920×1080, avail 1040, 24-bit, dpr 1 | default; the most coherent on Gecko (everything that cannot be spoofed is already Firefox) |
| `mac-firefox` | `... (Macintosh; Intel Mac OS X 10.15; rv:158.0) ...` | `MacIntel` / `Intel Mac OS X 10.15` / `5.0 (Macintosh)` | 1440×900, avail 875, 30-bit, dpr 2 | WebGL `Apple` / `Apple M1` |
| `linux-firefox` | `... (X11; Linux x86_64; rv:158.0) ...` | `Linux x86_64` ×2 / `5.0 (X11)` | 1920×1080, avail 1053 | WebGL Mesa Intel UHD 620 |
| `win-chrome` | `... AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36` | `Win32` / `Windows NT 10.0; Win64; x64` (Gecko cannot make `oscpu` undefined) / full Chrome appVersion | as win-firefox | `chromeLike`: `navigator.userAgentData` with brands `Google Chrome 141 / Not?A_Brand 8 / Chromium 141`, Chrome-shaped `plugins`. **UA-only**: `navigator.vendor` is `""`, `oscpu` exists, no Chrome-only APIs — JS feature checks can tell it is Gecko. Use for server-side UA sniffers only. |
| `real-device` | engine defaults | `null` | `null` | touch on, `layoutWidth 0`, shim disabled unless a geo region is active |

All desktop presets: `layoutWidth 1366` (above Bootstrap 992 / Tailwind 1024 / MUI 1200 / Tailwind xl 1280),
`hardwareConcurrency 8`, `touch false`.

| Region | languages | locale | time zone | geolocation |
|---|---|---|---|---|
| `device` | device (`en-US, en` only as the shim's fallback) | device | device | none |
| `us-east` | `en-US, en` | `en-US` | `America/New_York` | 40.7128, −74.006 |
| `uk` | `en-GB, en` | `en-GB` | `Europe/London` | 51.5074, −0.1278 |
| `de` | `de-DE, de, en` | `de-DE` | `Europe/Berlin` | 52.52, 13.405 |
| `jp` | `ja-JP, ja, en` | `ja-JP` | `Asia/Tokyo` | 35.6762, 139.6503 |

User-defined identities and regions are stored as JSON in `filesDir/personas/{identities,regions}/<id>.json`
(`PersonaManager.saveIdentity/saveRegion`; ids must match `^[a-z0-9][a-z0-9_-]{0,63}$`, presets are read-only).

## Golden files

`personas/golden/<identityId>__<regionId>.prefs.json` = `PersonaToPrefs.goldenJson(persona)`. The JVM test
`GoldenTest` fails when the mapping drifts; the headless runner (`tool/headless/run.mjs`) reads `prefs`,
`clear`, `env` and `shim` from these files and **never recomputes them**, so a headless PASS proves the
Kotlin mapping, not a JS re-implementation of it. Eight combinations are locked: `win-firefox__us-east`,
`win-firefox__device`, `win-firefox__de`, `mac-firefox__device`, `linux-firefox__jp`, `win-chrome__uk`,
`real-device__device`, `real-device__jp`.

Regenerate after an intentional change:

```
flock /tmp/hatch-heavy.lock ./gradlew --no-daemon --max-workers=1 --console=plain :persona:test -Dhatch.regenGoldens=true
node tool/headless/gen-expectations.mjs      # harness/personas/*.json follow the same presets
```

## How a switch is applied (`PersonaManager.apply`)

1. `GeckoPrefs.apply(PersonaToPrefs.prefs(new))` — `GeckoPreferenceController.setGeckoPrefs` for the set
   half, `clearGeckoUserPref` for the clear half (Gecko UI thread, awaited). `GeckoPrefs.kt` is the only file
   that touches this `@ExperimentalGeckoViewApi`; if it ever breaks, yaml + restart is the full fallback.
2. Per tab: `session.settings.userAgentString`, `toggleDesktopMode(desktop, reload = false)`,
   `ContentAction.UpdateTabDesktopMode`; store-wide `DefaultDesktopModeAction`.
3. `engine.settings.userAgentString` (new tabs), `runtime.settings.locales`, `inputAutoZoomEnabled`.
4. `bridge.sendPersona(shimConfig(new))` — the extension re-registers the MAIN-world shim.
5. Rewrite `geckoview-config.yaml` (atomic rename) so the next cold start is already right; persist the
   selection; reload the selected tab.
6. If `env` changed (`TZ`): return `NeedsRestart`; the UI offers a restart (`AppRestart`, a `:restart`
   trampoline process — see `docs/architecture.md`).

Never enable `privacy.resistFingerprinting`: it overrides UA, platform, pointer, touch and time zone with its
own values and would fight every persona.
