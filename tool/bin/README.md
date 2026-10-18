# tool/bin — lint CLI jars (not committed, not downloaded automatically)

`make lint` runs Kotlin lint from two self-contained jars placed here, each with `-Xmx512m` and under
`flock /tmp/hatch-heavy.lock` so they never overlap with Gradle or headless Firefox on this 3.6 GB box.
When a jar is missing the Makefile prints `!! tool/bin/<jar> missing` and skips that linter instead of
failing, so `make check` stays runnable on a fresh checkout. Nothing here is fetched by `setup.sh`;
install the jars deliberately:

| Jar | Version | Download | Invocation (what the Makefile runs) |
|---|---|---|---|
| `ktlint.jar` | 1.5.0 | `https://github.com/pinterest/ktlint/releases/download/1.5.0/ktlint` (the release asset is an executable jar; save it as `ktlint.jar`) | `java -Xmx512m -jar tool/bin/ktlint.jar "persona/src/**/*.kt" "app/src/**/*.kt" "*.kts"` |
| `detekt-cli.jar` | 1.23.8 | `https://github.com/detekt/detekt/releases/download/v1.23.8/detekt-cli-1.23.8-all.jar` | `java -Xmx512m -jar tool/bin/detekt-cli.jar --input persona/src,app/src --build-upon-default-config [--config detekt.yml]` |

Verify each download against the SHA-256 published on the release page before placing it here, e.g.

```
curl -fsSL -o tool/bin/ktlint.jar https://github.com/pinterest/ktlint/releases/download/1.5.0/ktlint
sha256sum tool/bin/ktlint.jar        # compare with the release notes
curl -fsSL -o tool/bin/detekt-cli.jar https://github.com/detekt/detekt/releases/download/v1.23.8/detekt-cli-1.23.8-all.jar
sha256sum tool/bin/detekt-cli.jar
```

Why jars instead of Gradle plugins: the ktlint/detekt Gradle plugins add a second JVM worker and
hundreds of MB of classpath to every build on a machine where the Gradle heap is already capped at
1.4 GB. The CLI jars run in their own small JVM, one at a time, and are trivially pinned.

`web-ext lint --self-hosted` (the extension linter) is a Node tool, not a jar: install it with
`npm i -D web-ext` in `harness/` (or globally) and `make lint` picks it up from `harness/node_modules/.bin`
or `PATH`. Both `*.jar` files in this directory are git-ignored by the global `build/`-style rules only
if you add them to `.gitignore`; keep them out of commits.
