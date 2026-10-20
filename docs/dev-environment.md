# Development environment

## The build box

Hatch is built on a **2 vCPU / 3.6 GB RAM Vultr VPS that also runs production containers** (typically
< 1 GB free, zram full, swap in use). Everything in the repo is sized for that:

| Rule | Where it is enforced |
|---|---|
| At most **one heavy process** (Gradle, headless Firefox, lint jars, npm) at a time: every such command runs under `flock /tmp/hatch-heavy.lock` | `Makefile`, `tool/build_apk.sh`, `tool/headless/run.mjs` (re-execs itself under the lock), `tool/headless/setup.sh` |
| Gradle heap `-Xmx1400m`, metaspace 384 MB, Kotlin compiler **in-process** (no second daemon), `workers.max=1`, `parallel=false`, `daemon=false`, build cache + configuration cache on | `gradle.properties` — never raise these on this box |
| `./gradlew --no-daemon --max-workers=1 --console=plain` | `Makefile` `GRADLE`, `tool/build_apk.sh` |
| `JAVA_HOME=/opt/android-studio/jbr` (JDK 21), `ANDROID_HOME=$HOME/Android/Sdk`, exported by the scripts, never hard-coded in properties | `Makefile`, `tool/build_apk.sh` |
| Headless Firefox runs inside `systemd-run --user --scope -p MemoryMax=1200M` when a user systemd instance is reachable, with memory-trim prefs (`dom.ipc.processCount=1`, no disk cache, no prelaunch) | `tool/headless/run.mjs` |
| No Robolectric / Compose UI tests (memory vs value); `:persona` JUnit, node tests and the headless harness carry the automated load | `Makefile` |

`:persona` must **not** request a JVM toolchain (`jvmToolchain(17)`): only JDK 21 is installed and there is no
toolchain download repository. It runs on 21 and targets 17.

### CI (GitHub Actions)

`.github/workflows/check.yml` runs `make check` and then the APK/size/android-lint steps on a 16 GB
GitHub-hosted runner. The repo `gradle.properties` caps still apply there, so the workflow writes a larger
`org.gradle.jvmargs` into `~/.gradle/gradle.properties` (GRADLE_USER_HOME wins over the project file);
`lintVitalAnalyzeRelease` OOMs under the 384 MB Metaspace cap otherwise. Never raise the project file.

### Remote build host (optional)

`tool/build_apk.sh` runs Gradle on a remote host when `HATCH_BUILD_HOST` is set or the git-ignored file
`.buildhost` contains `user@host` (`tool/remote_build.sh`: rsync the tree to `~/build/hatch`, run Gradle with
an 8 GB-class heap under the host's own lock, copy `app/build/outputs` and `persona/build/{reports,test-results}`
back). The host needs JDK 21 at `~/opt/jdk21`, Node at `~/opt/node`, the Android SDK at `~/Android/Sdk`, and
passwordless SSH (BatchMode). Delete `.buildhost` to build locally again. The Makefile's Gradle steps
(`unit-persona` = `:persona:test`, `android-lint`) follow the same rule, so with a build host configured
`make check` runs **no Gradle on this box**; ktlint/detekt, the node tests and headless Firefox always run here.
`tool/remote_build.sh` serialises itself end to end with `flock /tmp/hatch-remote.lock` (a run may wait for
another caller's build; that is normal).

Do not wrap `make check` (or `run.mjs`) in an outer `flock /tmp/hatch-heavy.lock`: the Makefile targets and the
runner take that lock themselves per step, and `flock` is not re-entrant across processes, so the outer holder
deadlocks the inner one.

## Toolchain

| Tool | Version | Why this version |
|---|---|---|
| Android Gradle Plugin | 9.4.1 | AC 158 AARs declare `minCompileSdk 37.2`; Compose 1.12 needs AGP ≥ 9.1; AGP 9 has built-in Kotlin (no `kotlin.android` plugin anywhere) |
| Gradle | 9.6 (wrapper) | AGP 9.4 needs ≥ 9.6 |
| Kotlin | 2.4.20 | exactly what Android Components 158 was built with (stdlib pin in its POMs) |
| Compose BOM | 2026.09.00 | exactly AC 158's BOM → no skew with AC's compose artifacts |
| GeckoView | `geckoview-omni` 158.0.20261007201254 (transitive) | pinned by AC 158.0; never add non-omni `geckoview` (duplicate classes) |
| Android Components | 158.0 | from `https://maven.mozilla.org/maven2` |
| kotlinx-serialization / coroutines | 1.11.0 | |
| compileSdk / targetSdk / minSdk | 37.2 / 36 / 26 | |
| Node | ≥ 22 | harness tests, vendor bundle, headless runner |
| Firefox (headless) | 158.0 Linux x86_64 at `~/opt/firefox-158.0` | same engine train as GeckoView; the runner asserts `browserVersion` 158.* |
| geckodriver | 0.37.1 at `~/opt/bin/geckodriver` | |
| selenium-webdriver | 4.51.0 (harness devDependency) | Playwright was rejected: cannot load extensions, cannot pin Firefox 158 |
| esbuild / modernizr | 0.28.2 / 3.13.1 | vendor bundle |
| ktlint / detekt CLI jars | 1.5.0 / 1.23.8, optional, in `tool/bin/` | see `tool/bin/README.md`; skipped when absent |
| web-ext | 10 (`npx --yes web-ext@10`) | extension lint |

## First-time setup

```
make setup            # Firefox 158.0 (sha256-verified) + geckodriver 0.37.1 into ~/opt, npm ci in harness/
make vendor           # (only after changing harness/vendor/entry.js or library versions)
```

## Everyday commands

| Command | What |
|---|---|
| `make check` | `lint` → `unit` → `headless`, serial, < 1.3 GB peak |
| `make check-full` | `check` + release APK + size budget + Android lint |
| `make unit` | `:persona` JUnit (goldens, URL rewrites, userscript headers), harness node tests, extension node tests (`make unit-ext`: contract tests + the shim size/time budgets, `HATCH_SHIM_BENCH_ITER` defaults to 200 there), vendor-check |
| `make headless` | headless Firefox 158, all goldens + negative controls → `build/harness-reports/` (`PERSONAS=id,id` to narrow) |
| `make lint` | ktlint + detekt (if jars present), `web-ext lint`, expectation freshness |
| `make expectations` | regenerate `harness/personas/*.json` from `personas/*.json` |
| `tool/build_apk.sh [debug\|release\|install]` | APK with build identity stamped in → `dist/hatch-<version>-<build>-<sha>-<mode>.apk` |
| `tool/check_apk_size.sh [apk]` | size budgets → `build/size.json` (also reports the bytes of `lib/` left *Stored* uncompressed — must be 0 with `jniLibs.useLegacyPackaging`) |
| `tool/remote_build.sh :app:testDebugUnitTest` | the app module's plain-JVM tests (`AddonUrl`, `BridgeReportFilter`, `LocalHttpServer`) on the remote build host; results come back to `app/build/test-results/` |
| `node tool/headless/server.mjs` | serve the harness at `http://127.0.0.1:8765/index.html?persona=win-firefox__us-east` for manual poking |
| `cd app/src/main/assets/extension && node --test test/*.test.mjs` | extension unit tests |

Direct Gradle (always under the lock, always `--no-daemon --max-workers=1 --console=plain`):

```
export JAVA_HOME=/opt/android-studio/jbr ANDROID_HOME=$HOME/Android/Sdk
flock /tmp/hatch-heavy.lock ./gradlew --no-daemon --max-workers=1 --console=plain :persona:test
flock /tmp/hatch-heavy.lock ./gradlew --no-daemon --max-workers=1 --console=plain :app:assembleDebug
```

## Build identity

- `versionName` from `version.properties` (MAJOR.MINOR.PATCH, bumped by hand).
- `versionCode` = `git rev-list --count HEAD` (every commit is an installable update; 1 when not in a git
  checkout, e.g. on the remote build host).
- `GIT_SHA` (`-dirty` suffix when the tree is modified), `GIT_BRANCH`, `BUILD_TIME` as `BuildConfig` fields from
  `-P` properties set by `tool/build_apk.sh`; shown in the Settings footer (full detail in debug builds).
- Debug builds use `applicationId app.hatch.browser.debug`, so a debug and a release build can coexist.
- Release: R8 + resource shrinking, signed with `key.properties` + `.jks` (git-ignored; generated in M1,
  backed up over the tailnet). If R8 OOMs under the heap cap, set `isMinifyEnabled = false`.
- Native libraries are compressed in the APK (`jniLibs.useLegacyPackaging = true`) and extracted at install:
  the APK budget (110 MB) counts the compressed size: the signed R8 release build measured 100.63 MiB
  (105,513,437 B) after the M1 fix pass on 2026-10-18 (M1 before the pass: 103.9 MiB; M0: 103.1 MiB), with
  `resources.arsc` at 0.65 MiB (4.1 MiB before the `localeFilters = ["en"]` filter, PERF-02; budget 2 MiB in
  `tool/check_apk_size.sh`). The on-device footprint is the APK plus ~189 MB of extracted libraries (`libxul.so`
  153 MB). Debug APKs (~140 MB, unminified dex) are not held to the budget.

## Release builds, R8 and crashes on the road

- **Release (R8) builds only fail on a device.** The VPS and CI build them but cannot run them; the first
  release that ran on the Pixel (build 20, 2026-10-20) died in `GeckoRuntime.create` because R8 had moved
  SnakeYAML (GeckoView's `geckoview-config.yaml` reader) into the unnamed package and its `TypeDescription`
  static initialiser calls `getPackage().getName()`. `app/proguard-rules.pro` keeps the package names
  (`-keeppackagenames org.yaml.snakeyaml.**`) and `tool/check_release_mapping.sh` (run by
  `tool/build_apk.sh release`) fails the build if the mapping ever shows SnakeYAML outside its package. Every
  new release-only crash gets the same treatment: rule + mapping guard + a line here.
- **Mapping next to the APK.** `tool/build_apk.sh release` copies the R8 mapping to
  `dist/<apk>.mapping.txt`. De-obfuscate a trace with
  `JAVA_HOME=/opt/android-studio/jbr $ANDROID_HOME/cmdline-tools/latest/bin/retrace dist/<apk>.mapping.txt trace.txt`.
- **Startup-crash capture** (`app/hatch/crash/`): `CrashCapture.install` runs first in
  `HatchApplication.onCreate`; a startup marker left behind by the previous start puts the next one in safe mode,
  where `MainActivity` opens `CrashRecoveryActivity` (Share / Copy the trace, Try again, Clear Hatch data)
  instead of touching Gecko. The uncaught-exception handler writes `files/crashes/last.txt` and then lets the
  system handler run. The marker clears 5 s after `MainActivity.onCreate`. Without adb, this is the only crash
  evidence that leaves the phone; Cory shares it via Tailscale or pastes it into the chat.
- **Build number on the remote host.** `tool/remote_build.sh` rsyncs the tree without `.git`, so Gradle's
  `git rev-list --count` fallback would stamp `versionCode = 1`; `tool/build_apk.sh` passes
  `-PversionCode=<commit count>` and `app/build.gradle.kts` prefers it. Builds 1–21 from the NUC carried
  versionCode 1 (the dist file names were right); from build 22 the APK's versionCode matches the name.

## Memory budget cheatsheet

| Process | Peak RSS observed |
|---|---|
| `:persona:test` | ~0.9 GB (Gradle JVM + in-process Kotlin compiler) |
| `:app:assembleDebug` | ~1.4 GB heap cap (+ aapt2/R8 helpers); build on the remote host when one is configured |
| headless Firefox + geckodriver + node | ~0.5 GB per run, one at a time, 1.2 GB scope cap |
| `web-ext lint` | ~0.3 GB |
| ktlint / detekt | `-Xmx512m` |

## Disk

GeckoView AAR 244 MB + exploded ~600 MB, Firefox tarball ~280 MB, project build dirs ~2 GB. Keep the NDK,
`~/.gradle/caches` and the Firefox install; `build/`, `dist/`, `node_modules/` are regenerable.
