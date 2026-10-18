#!/usr/bin/env bash
# Runs a Gradle invocation on the remote build host (the NUC) and copies the outputs back.
#
#   tool/remote_build.sh <gradle args...>      e.g. tool/remote_build.sh :app:assembleDebug -PgitSha=abc
#
# Host comes from $HATCH_BUILD_HOST or the gitignored file .buildhost (user@host). The working tree
# is rsynced to ~/build/hatch on the host (build dirs, node_modules and downloads excluded), Gradle runs
# there with a heap sized for 8 GB, and app/build/outputs + persona/build/reports come back.
set -euo pipefail
cd "$(dirname "$0")/.."

host="${HATCH_BUILD_HOST:-$(cat .buildhost 2>/dev/null || true)}"
[ -n "$host" ] || { echo "remote_build: no build host (set HATCH_BUILD_HOST or .buildhost)" >&2; exit 2; }
remote_dir="${HATCH_BUILD_DIR:-build/hatch}"

ssh -o BatchMode=yes "$host" "mkdir -p '$remote_dir'"
rsync -az --delete \
  --exclude '.git/' --exclude '**/build/' --exclude '.gradle/' --exclude '.kotlin/' --exclude 'dist/' \
  --exclude 'node_modules/' --exclude 'tool/headless/.cache/' --exclude 'local.properties' \
  ./ "$host:$remote_dir/"

# Gradle args are re-quoted for the remote shell (-PbuildTime="2026-10-18 09:29" carries a space).
args=$(printf ' %q' "$@")
# shellcheck disable=SC2029
ssh -o BatchMode=yes "$host" "set -euo pipefail; cd '$remote_dir'; \
  export JAVA_HOME=\$HOME/opt/jdk21 ANDROID_HOME=\$HOME/Android/Sdk PATH=\$HOME/opt/jdk21/bin:\$HOME/opt/node/bin:\$PATH; \
  echo \"sdk.dir=\$ANDROID_HOME\" > local.properties; chmod +x gradlew; \
  flock \$HOME/.hatch-build.lock ./gradlew --no-daemon --console=plain \
    -Dorg.gradle.jvmargs='-Xmx3g -XX:MaxMetaspaceSize=768m -XX:ReservedCodeCacheSize=256m' \
    -Dkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=3$args"

mkdir -p app/build persona/build
rsync -az "$host:$remote_dir/app/build/outputs/" app/build/outputs/ 2>/dev/null || true
rsync -az "$host:$remote_dir/persona/build/reports/" persona/build/reports/ 2>/dev/null || true
rsync -az "$host:$remote_dir/persona/build/test-results/" persona/build/test-results/ 2>/dev/null || true
