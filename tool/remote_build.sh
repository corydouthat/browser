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

# One remote build at a time, end to end: the rsync would otherwise rewrite the remote tree while another
# caller's Gradle is still compiling it (the remote flock only covers Gradle). Re-exec under a local lock.
if [ -z "${HATCH_REMOTE_LOCKED:-}" ]; then
  HATCH_REMOTE_LOCKED=1 exec flock /tmp/hatch-remote.lock "$0" "$@"
fi

host="${HATCH_BUILD_HOST:-$(cat .buildhost 2>/dev/null || true)}"
[ -n "$host" ] || { echo "remote_build: no build host (set HATCH_BUILD_HOST or .buildhost)" >&2; exit 2; }
remote_dir="${HATCH_BUILD_DIR:-build/hatch}"
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=20 -o ServerAliveInterval=15 -o ServerAliveCountMax=4)

# The NUC uses Tailscale SSH in "check" mode: every so often a login.tailscale.com approval is needed and
# ssh fails with that link. Then the link is saved to build/nuc-auth-needed.txt (for whoever can approve it)
# and, unless HATCH_REMOTE_NO_FALLBACK=1, the same Gradle invocation runs on this box under the heavy lock
# with the project's memory caps, so work never stalls on an approval.
probe_err=$(mktemp)
if ! timeout 25 ssh "${SSH_OPTS[@]}" "$host" true 2>"$probe_err"; then
  mkdir -p build
  if grep -o 'https://login\.tailscale\.com/[^ ]*' "$probe_err" > build/nuc-auth-needed.txt; then
    echo "remote_build: $host needs a Tailscale SSH approval: $(cat build/nuc-auth-needed.txt)" >&2
  else
    rm -f build/nuc-auth-needed.txt
    echo "remote_build: $host unreachable: $(tr '\n' ' ' < "$probe_err")" >&2
  fi
  rm -f "$probe_err"
  if [ "${HATCH_REMOTE_NO_FALLBACK:-0}" = 1 ]; then exit 255; fi
  # Paparazzi runs need a 2 GB test JVM next to Gradle (too much for this 3.6 GB box) and would write their goldens
  # into app/src/test/snapshots in the source tree: never fall back for them (RS-07).
  for arg in "$@"; do
    case "$arg" in
      *[Pp]aparazzi*|-Phatch.snapshots=true)
        echo "remote_build: '$arg' runs only on the build host; not falling back locally (approve the link above and retry)" >&2
        exit 3 ;;
    esac
  done
  echo "remote_build: falling back to a local build (heavy lock, project memory caps)" >&2
  export JAVA_HOME="${JAVA_HOME:-/opt/android-studio/jbr}" ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
  [ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
  exec flock /tmp/hatch-heavy.lock ./gradlew --no-daemon --max-workers=1 --console=plain "$@"
fi
rm -f "$probe_err" build/nuc-auth-needed.txt

ssh "${SSH_OPTS[@]}" "$host" "mkdir -p '$remote_dir'"
rsync -az --delete -e "ssh -o BatchMode=yes -o ConnectTimeout=20 -o ServerAliveInterval=15" \
  --exclude '.git/' --exclude '**/build/' --exclude '.gradle/' --exclude '.kotlin/' --exclude 'dist/' \
  --exclude 'node_modules/' --exclude 'tool/headless/.cache/' --exclude 'local.properties' \
  ./ "$host:$remote_dir/"

# Gradle args are re-quoted for the remote shell (-PbuildTime="2026-10-18 09:29" carries a space).
args=$(printf ' %q' "$@")
# shellcheck disable=SC2029
ssh "${SSH_OPTS[@]}" "$host" "set -euo pipefail; cd '$remote_dir'; \
  export JAVA_HOME=\$HOME/opt/jdk21 ANDROID_HOME=\$HOME/Android/Sdk PATH=\$HOME/opt/jdk21/bin:\$HOME/opt/node/bin:\$PATH; \
  echo \"sdk.dir=\$ANDROID_HOME\" > local.properties; chmod +x gradlew; \
  flock \$HOME/.hatch-build.lock ./gradlew --no-daemon --console=plain \
    -Dorg.gradle.jvmargs='-Xmx3g -XX:MaxMetaspaceSize=768m -XX:ReservedCodeCacheSize=256m' \
    -Dkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=3$args"

mkdir -p app/build persona/build
rsync -az -e "ssh -o BatchMode=yes -o ConnectTimeout=20 -o ServerAliveInterval=15" "$host:$remote_dir/app/build/outputs/" app/build/outputs/ 2>/dev/null || true
rsync -az -e "ssh -o BatchMode=yes -o ConnectTimeout=20 -o ServerAliveInterval=15" "$host:$remote_dir/persona/build/reports/" persona/build/reports/ 2>/dev/null || true
rsync -az -e "ssh -o BatchMode=yes -o ConnectTimeout=20 -o ServerAliveInterval=15" "$host:$remote_dir/persona/build/test-results/" persona/build/test-results/ 2>/dev/null || true
rsync -az -e "ssh -o BatchMode=yes -o ConnectTimeout=20 -o ServerAliveInterval=15" "$host:$remote_dir/app/build/reports/" app/build/reports/ 2>/dev/null || true
rsync -az -e "ssh -o BatchMode=yes -o ConnectTimeout=20 -o ServerAliveInterval=15" "$host:$remote_dir/app/build/test-results/" app/build/test-results/ 2>/dev/null || true
# Paparazzi goldens (recordPaparazziDebug writes them under src/test/snapshots on the host): copied into the
# git-ignored build dir, never into the source tree. The next rsync --delete clears them on the host.
rsync -az --delete -e "ssh -o BatchMode=yes -o ConnectTimeout=20 -o ServerAliveInterval=15" "$host:$remote_dir/app/src/test/snapshots/" app/build/paparazzi-snapshots/ 2>/dev/null || true
