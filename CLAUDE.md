# Hatch — project notes for Claude

Hatch is an Android browser (GeckoView 158 + Mozilla Android Components 158, Kotlin + Jetpack Compose,
package `app.hatch.browser`, namespace `app.hatch`) that forces websites into a **true desktop layout** by
spoofing a coherent desktop environment ("persona" = desktop identity × region) at engine level plus a JS shim,
and proves it with a detection harness that runs headless on the VPS and as a Self-check screen on the phone.
The approved design is the plan Cory confirmed on 2026-10-18; `docs/architecture.md` is its living copy.

## Docs index

| Doc | Read it when |
|---|---|
| `README.md` | what/why, build, sideload, status |
| `docs/architecture.md` | engine choice, repo layout, process model, AC-vs-hand-rolled table, upgrade checklist, decisions log |
| `docs/persona-model.md` | the persona data model, the field → mechanism table, Accept-Language algorithm, presets, goldens, how a switch is applied |
| `docs/extension.md` | the built-in MV2 extension: native messaging contract, shim rules, rules/GM subset, Eruda, host rewrites |
| `docs/detection-harness.md` | harness files, result schema v1, the full signal table, headless runner + controls, M0 results, how to add a signal |
| `docs/dev-environment.md` | the VPS rules, toolchain versions, every `make` target, build identity, memory budgets |
| `docs/limitations.md` | every known tell and web-compat cost, what tracks it |
| `docs/roadmap.md` | M0/M1/M2 with the M0 device checklist (a–k) for Cory |
| `docs/device-checklist.md` | the runbook: Self-check protocol, 15-site audit, gates |
| `docs/icon.md`, `THIRD_PARTY.md` | icon provenance, vendored third-party code |
| `harness/README.md`, `personas/README.md`, `tool/bin/README.md` | local READMEs for those trees |

## The lock rule (this box is a 2-vCPU / 3.6 GB VPS that also runs production containers)

- **Never** run Gradle, Firefox, ktlint/detekt jars, npm installs or any process over ~300 MB RSS without the
  lock: prefix the command with `flock /tmp/hatch-heavy.lock`. The `Makefile`, `tool/build_apk.sh` and
  `tool/headless/run.mjs` already do this; direct `./gradlew` calls must too.
- Always `./gradlew --no-daemon --max-workers=1 --console=plain` with `JAVA_HOME=/opt/android-studio/jbr` and
  `ANDROID_HOME=$HOME/Android/Sdk` exported. Never run two Gradle invocations at once.
- **Never edit the memory caps in `gradle.properties`** (`-Xmx1400m`, in-process Kotlin compiler, one worker).
- One headless Firefox at a time (`run.mjs` serialises itself and uses a 1.2 GB systemd scope).
- `tool/build_apk.sh` builds on the remote host named in `.buildhost` when present (rsync + ssh, light on
  this box); everything else runs here.

## Versioning & builds

- `version.properties` holds `versionName=MAJOR.MINOR.PATCH` (bump by hand). The Android `versionCode` is
  `git rev-list --count HEAD`, so every commit is an installable update.
- `tool/build_apk.sh [debug|release|install]` stamps `GIT_SHA` (`-dirty` when the tree is modified),
  `GIT_BRANCH`, `BUILD_TIME` into `BuildConfig` and copies the APK to
  `dist/hatch-<versionName>-<versionCode>-<sha>-<mode>.apk`. The Settings footer shows them.
- Debug builds: `applicationId app.hatch.browser.debug`, `-debug` version suffix, no R8. Release: R8 +
  `shrinkResources`, signed with `key.properties` + `.jks` (git-ignored; M1).
- Size budgets (`tool/check_apk_size.sh`, release): arm64 APK ≤ 110 MB (release measured 103.1 MB on 2026-10-18), `assets/selfcheck/` ≤ 2 MB,
  `assets/extension/` excluding `vendor/eruda.min.js` ≤ 200 KB. Debug APKs are ~240 MB (uncompressed
  `libxul.so`, unminified dex) and are not held to the APK budget.
- `make check` (lint → unit → headless) before every milestone; `make check-full` adds the release APK, the
  size check and Android lint. Headless reports for a milestone are copied to `reports/headless/<m>/`.
- Commits per module with conventional, descriptive messages; PR per milestone on GitHub (`corydouthat/browser`; CI = `.github/workflows/check.yml`). Never commit
  `build/`, `dist/`, `node_modules/`, `key.properties`, `*.jks`, `.buildhost`, `local.properties`.

## Key decisions (short; see docs/architecture.md)

- GeckoView over Chromium WebView: meta-viewport, CSS pointer/hover and touch removal are engine-native and
  undetectable; engine pinned in the APK.
- `PersonaToPrefs` (`:persona`) is the single field → mechanism table; goldens lock it; the headless runner
  reads the goldens and never recomputes prefs.
- Live switches via `GeckoPreferenceController` (only in `GeckoPrefs.kt`); yaml + restart is the full
  fallback; only `TZ` needs a restart (`AppRestart`, a `:restart` trampoline process).
- Persona delivery to pages through the built-in MV2 extension (`contentScripts.register`, MAIN world,
  `document_start`); the shim treats `null` as "do not touch"; `real-device` registers nothing.
- Region prefs are independent of the identity ("real phone, but German").
- Accept-Language follows Gecko 158's `rust_prepare_accept_languages`: `"en-US, en"` → `en-US,en;q=0.9`.
- `win-chrome` is UA-only and documented as detectable; `win-firefox` is the default.
- Never enable `privacy.resistFingerprinting`.
- `real-device` in headless: touch-host signals become SKIPPED (the host is a desktop); on the phone they are
  hard and the "persona off" run must FAIL on them.
- `ctl-touch` needs `dom.w3c_touch_events.legacy_apis.enabled=true` on desktop Firefox (Android's default)
  for `document.createEvent("TouchEvent")` to work.
- geckoview-omni and app-services both provide `glean-native`; `app/build.gradle.kts` selects GeckoView's
  (the Fenix rule) or the runtime classpath does not resolve.
- Kotlin block comments nest: never write `/*.json` inside a KDoc.

## Memory rules (how to work in this repo)

- Read the docs index above before changing a contract (shim config JSON, native message types, SiteRule
  wire format, harness result schema, golden format). Contracts are duplicated on purpose in Kotlin, JS and
  the harness; change all sides and the tests (`persona/src/test`, `harness/test`,
  `app/src/main/assets/extension/test`).
- Preset JSON in `personas/` is the source of truth. After changing it: regenerate goldens
  (`:persona:test -Dhatch.regenGoldens=true`) and expectations (`node tool/headless/gen-expectations.mjs`).
- Verify engine facts against primary sources at tag `FIREFOX_158_0_RELEASE` (Gecko, Android Components
  under `mobile/android/android-components`), the GeckoView javadoc and MDN; do not guess API shapes.
- No placeholders or TODO stubs in delivered code. Every finding from a review needs: id, severity, role,
  persona(s), repro, evidence, expected vs actual, root-cause hypothesis, proposed fix, regression test.
- Red-team pages live in `harness/redteam/<n>-<name>.html` and print `DETECTED`; a successful detector
  becomes a harness signal or a `docs/limitations.md` entry.
- Do not add `geckoview` (non-omni), `feature-webcompat`, `compose-browser-toolbar` or `feature-addons`.
- Keep `docs/limitations.md` and `docs/roadmap.md` current when behaviour changes.
