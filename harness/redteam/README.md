# Red-team detector pages

Each page prints one line per check (`DETECTED <id>: <reason>` when the lie is visible; `clean`, `info`,
`needs-device` otherwise) and sets `window.__hatchPageDone` when finished. Run one against the real prefs +
extension in headless Firefox 158 (run.mjs takes /tmp/hatch-heavy.lock itself):

    node tool/headless/run.mjs --page harness/redteam/01-tostring.html --personas win-firefox__us-east

The runner fails (exit 1) when the body contains the word DETECTED; the body text lands in
`build/harness-reports/page-<name>-<persona>.txt`. `run-all.sh` runs every page for one persona serially.
Pages marked `needs-device` (09 tap, 10 WebGL, 12 voices, 30 gestures, 31 popups in headless) are opened on the
phone through the Self-check server (`/t/<token>/redteam/<page>`; the pages ship in the APK under
`assets/selfcheck/redteam/`). Checks closed by the M0 fix pass (toString text/shape/cross-realm, fresh
data:/blob:/sandboxed frames, device-* media features, `.media` serialisation, geolocation callback stack,
marker symbols, chrome geometry) must print `clean`; the documented tells (brand-check stack frames, fonts,
voices, WebGL capabilities, stylesheet-level device-*) print `info` or stay listed in `docs/limitations.md`.

| Page | Vector |
|---|---|
| 01-tostring | `Function.prototype.toString` of every shimmed function and of `toString` itself (7 spellings), own-property shape vs a native reference, brand-check `Error.stack` frame counts, cross-realm `toString` from a fresh iframe realm |
| 02-descriptors | marker symbols/names on `window`, own-vs-prototype, accessor descriptor shape, `for-in` order of `navigator`, Firefox-158 API surface coherence (`share`/`vibrate`/`connection`/plugins) |
| 03-fresh-iframes | sync `about:blank`, `document.write`, `srcdoc`, `javascript:`, `data:`, `blob:`, sandboxed frames, `window.open` — values and "pristine" natives captured from the frame realm |
| 04-worker-scope | dedicated / shared / service worker `navigator`, `Intl`, `Date`, OffscreenCanvas WebGL vs window |
| 05-media-vs-css | JS `matchMedia` vs CSS `@media` for 30 queries, `device-width`/`device-height`/`resolution` vs `screen.*`/`devicePixelRatio`, orientation coherence, `MediaQueryList.media` serialization vs CSSOM |
| 06-screen-vs-window | inner/outer/avail/screen geometry impossibilities, `mozInnerScreen*`, `visualViewport.scale` at load |
| 07-intl-language | Intl default locale vs `navigator.language` vs `Accept-Language` (`./echo`) |
| 08-tz-geo-language | Date vs Intl zone/DST, geolocation longitude/continent vs zone, callback stack frames, position object shape, language region vs zone |
| 09-pointer-tap | device only: `pointerType`, `mozInputSource`, contact geometry on a real tap |
| 10-webgl | renderer string vs platform, D3D11/M1/Mesa capability plausibility, worker OffscreenCanvas renderer |
| 11-fonts | platform core fonts present / Android-only fonts absent (width probes) |
| 12-voices | `speechSynthesis.getVoices()` URIs (`moz-tts:android:` vs `urn:moz-tts:sapi|osx|speechd`) |
| 13-timing | first inline `<script>` in `<head>` vs later values; natives captured early |
