# personas/

Source of truth for the built-in persona presets and the golden pref files, owned by the `:persona` module.

- `identities.json` — `Identity` presets (`win-firefox` default, `mac-firefox`, `linux-firefox`, `win-chrome`,
  `real-device`). `layoutWidth: 0` on `real-device` means "engine default" (the pref is cleared).
- `regions.json` — `Region` presets (`device`, `us-east`, `uk`, `de`, `jp`). The `device` region clears
  `intl.accept_languages` / `intl.locale.requested` so the phone keeps its own languages; its `languages`
  list is only the shim's fallback.
- `golden/<identityId>__<regionId>.prefs.json` — `PersonaToPrefs.goldenJson(persona)`:
  `{"clear":[...],"env":{...},"prefs":{...},"shim":<shim config>}` with keys sorted recursively, 2-space
  indent. The headless harness (`tool/headless/run.mjs`) reads `prefs`/`env`/`shim` from here and never
  recomputes them. The `shim.tzOffsets` table is computed with a fixed clock (`PersonaToPrefs.GOLDEN_CLOCK` =
  2026-01-01T00:00Z, window 2025-01-01 .. 2026-12-31) so the files are stable; the app passes the real clock.

The module exposes this directory as classpath resources (`persona/build.gradle.kts`,
`resources.srcDir("../personas")`), so `Presets` loads `/identities.json` and `/regions.json`.

Regenerate the goldens after an intentional mapping change:

```
flock /tmp/hatch-heavy.lock ./gradlew --no-daemon --max-workers=1 --console=plain :persona:test -Dhatch.regenGoldens=true
```

then review the diff and commit. `GoldenTest` fails on any drift otherwise.
