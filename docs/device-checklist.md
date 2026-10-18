# Device checklist (runbook)

What Cory does with a fresh APK, in order. Budget ≈ 15 minutes. Results go to `docs/m<N>-results.md`; files
to `reports/device/<version>/` (the share zip, screenshots, `meminfo.json`).

## 0. Install

```
adb install -r dist/hatch-<version>-<build>-<sha>-debug.apk      # or sideload the file
```

Without adb, the reliable way to get a 140 MB debug APK onto the phone is Taildrop from the build box
(`tailscale file cp dist/<apk> <phone>:`); it needs no browser, runs in the background and stays on the
tailnet. Browser downloads from a `tailscale serve` share tend to stall when the tab is backgrounded.

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

## 7. Credentials gate (hatch:credentials, M1)

Build: a **release** APK for the passkey rows (debug can never be allow-listed); any build for the rest.

- **Google sign-in, `win-firefox`**: accounts.google.com → e-mail → password. Expect the plain web form (no
  Chrome-style account sheet; that is impossible in Hatch), no "This browser or app may not be secure"
  interstitial, 2FA prompt reachable. After submit: Hatch's **Save login?** dialog (feature-prompts
  `SaveLoginDialogFragment`); Save. Sign out, revisit: the username/password fields fill from Hatch's store
  (one saved login) or the **Use saved login** bar appears above the keyboard (several). Record which.
- **Google sign-in, `win-chrome`**: same flow. This is the persona most likely to trigger Google's
  "browser may not be secure" heuristic (Chrome UA on Gecko). Record the exact message if it appears and note
  that the fix is to use `win-firefox` for Google.
- **GIS popup flow**: a third-party site with "Sign in with Google" (e.g. stackoverflow.com or canva.com).
  Expect: a popup tab opens with the Google chooser (desktop viewport, HATCH-WC-05), completes, closes itself,
  the opener is signed in. Note if the popup tab lands behind the UI or stays open.
- **Autofill services**: on the same login form, note what the system autofill offers: Google Password
  Manager (expected: nothing or package-keyed entries, `docs/limitations.md`), Bitwarden/1Password if
  installed (expected: web-domain suggestions). Settings → **Autofill saved logins** off → the engine fill and
  the bar stop; on again → they return without a restart.
- **Saved logins screen**: menu → Saved logins (or Settings → Saved logins…). Expect the biometric / PIN
  sheet (or the keyguard screen on phones where the biometric stack reports the combination unavailable),
  then the list: search, eye reveals, copy shows "Password copied" and the clipboard preview is hidden
  (API 33+), delete asks first, + adds an entry that then fills on that site.
- **Never save**: on a throwaway site answer **Never save**; the dialog does not return for that origin;
  Settings → "Forget N never-save site(s)" brings it back.
- **Passkey attempt (expected to FAIL until Google approves the allowlist)**: webauthn.io → Register.
  Expect no Android passkey sheet and a page error (`AbortError` / `NotAllowedError`); on login
  `UnknownError`. Record the exact message and the Android version. Then menu → **Open in another browser**
  → the same page in Chrome completes with a passkey. When the allowlist entry lands, repeat: the Android
  "Create a passkey" sheet must appear inside Hatch (release build only).
- **Downloads notification permission (API 33+)**: first download of the session → the system
  "Allow Hatch to send notifications?" prompt; allow → progress notification; deny → the download still
  completes (check the Downloads folder).

## M1 rules module (rules, 2026-10-18)

- **Site rules**: menu → Site rules → enable "Example: hide cookie banners (CSS)" → open a site with a OneTrust
  banner → reload → banner gone. Open the example "log page info (JS, MAIN world)" → "Test on current site" →
  the page reloads; Eruda console shows `[hatch] {...}`. Import → From clipboard with a `.user.js` that has
  `@require` → refused with the reason; one with `@grant GM_addStyle` → preview shows @match / @grant → Add.
- **Host rewrites**: Settings → Host rewrites → switch `m.youtube.com` off → open `https://m.youtube.com/` →
  stays on the mobile host (desktop persona still applies); switch it on → reload → `www.youtube.com`.
- **App links** (YouTube app installed): default "Stay in Hatch" → a `youtube.com/watch` link stays in Hatch;
  "Ask" → AC's "Open in app?" dialog; "Always" → jumps to the app. An `intent://` link (e.g. a QR-code page)
  shows the dialog in every mode.
- **Site permissions / geolocation**: region `us-east` → a maps site shows New York with **no** prompt;
  region `device` → the real Android location prompt (first time) + Gecko's site prompt; answer "remember" →
  menu → Site permissions → the origin lists Location = Allowed → Clear → reload → the prompt returns.
- **Persona editor**: menu → Persona… → Duplicate `Windows · Firefox 158` → change hardwareConcurrency to 12 →
  Save → Switch… → select the copy → Self-check reports `hardwareConcurrency 12`. Delete the copy while active
  → the persona falls back to the default preset.

## 8. M1 fix-pass gates (fixer, 2026-10-18)

Device-only checks for the review findings closed in the fix pass; each line names the finding so the result can
be filed against it in `docs/m1-results.md`. Any build unless stated.

- **UXP-01 home first tap**: tab counter → `+` (home, keyboard up) → tap the "Force desktop" switch once: it toggles
  (persona snackbar) and the keyboard goes; same for "Change…" and the "Homepage" chip. A tap on blank home area only
  dismisses the keyboard.
- **UXP-02 snackbars over overlays**: Settings → toggle "Remote debugging" → the "restart recommended / Restart"
  snackbar is visible over Settings; menu → Saved logins → unlock → copy a password → "Password copied" visible over
  the list; tray → swipe a tab → "Tab closed – Undo" visible over the tray.
- **UXP-03/04 undo window**: long-press the tab counter → "Close tab" (and Ctrl+W with a keyboard): "Tab closed –
  Undo" appears and disappears by itself after ~4 s; tapping Undo while visible restores the tab.
- **UXP-05/06 find bar**: Ctrl+F, type, tap a link → the bar closes; Ctrl+F then Ctrl+L then Esc → the URL edit
  cancels first and the bar stays; Ctrl+L inside Settings leaves Settings and focuses the bar.
- **UXP-07 select mode**: tray → ⋮ → "Select tabs": "0 selected", close/share disabled; select then unselect one: still
  in select mode.
- **UXP-08 font scale**: Android Settings → Display → largest font + display size → Hatch: the "Win · Firefox · US"
  and "Desktop" chips are fully visible (no clipped text / icons), the strip grows instead.
- **UXP-09 theme switch**: quick-settings dark theme tile while Hatch is in front: the Activity recreates (tabs intact),
  status/navigation-bar icons and GeckoView's cover follow the theme immediately.
- **UXP-10 cutout**: landscape on a punch-hole phone: the site-info badge and the ⋮ button are not under the cutout.
- **UXP-11/12/13**: edit mode shows a clear (x) once text is typed; menu items animate the sheet closed; Settings →
  tap the "Home URL" field at the bottom → it stays above the keyboard.
- **UXP-14 / ENG-06 fresh thumbnail**: open a site, scroll, tap the tab counter: the current card shows the scrolled
  page within a moment of the tray opening (not the previous capture / favicon).
- **SEC-1 secure screen**: menu → Saved logins → unlock → reveal a password → take a screenshot: the system refuses
  ("can't take screenshot"); Recents shows a blank card; press Home, return: locked again, nothing revealed.
- **SEC-6 key loss (optional, rooted/test device only)**: with saved logins, delete
  `shared_prefs/hatch_logins_key_kp_post_m.xml`, open Saved logins: the "could not be unlocked … reset" snackbar
  shows and `databases/logins2.sqlite.undecryptable-*` exists.
- **ENG-01 memory trim**: open 6 tabs, `adb shell am send-trim-memory app.hatch.browser RUNNING_LOW` (debug:
  `app.hatch.browser.debug`), `adb shell dumpsys meminfo app.hatch.browser`: fewer content processes; the tray still
  lists 6 tabs and a suspended one reloads on tap in desktop mode.
- **ENG-02 cold-start desktop mode**: cold start → tray `+` → load a site → Self-check (a): `layout.clientWidth ≥ 1366`
  without a reload.
- **ENG-03 dynamic toolbar (switch on)**: on a page that logs `resize` events (Eruda console), open and close the
  keyboard: one resize per transition, not three; scroll until hidden: content starts below the status bar; switch it
  off again afterwards.
- **ENG-05 restart keeps tabs**: switch region to Japan → immediately open a new tab from the tray → tap Restart within
  2 s → after relaunch the new tab is there.
- **PERF-03 StrictMode (debug build)**: `adb logcat | grep StrictMode` during a cold start: no violation tagged
  `ThumbnailStorage` / `DiskLruCache`; note any other main-thread disk violation for M2.
- **PERF-07 disk**: after 20 tabs, `adb shell du -sh /data/data/app.hatch.browser/files/no_backup/mozac_browser_thumbnails`
  ≤ 20 MB.
- **PERF-09 numbers** (release build, record in `docs/m1-results.md`): `adb shell am start -W app.hatch.browser/app.hatch.MainActivity`
  ×3 (TotalTime ≤ 2.5 s); `adb shell dumpsys meminfo app.hatch.browser` with 3 loaded tabs before and after opening the
  tray once (PSS ≤ 600 MB; if > 550 MB the first lever is the thumbnail request size, the second the icon memory
  cache); stopwatch a persona switch from the sheet (Apply → page reloaded ≤ 2 s).
- **PERF-05 (M2, release build)**: only after the narrowed keep rule lands: save / fill / list / delete / "Forget
  never-save" all work.

