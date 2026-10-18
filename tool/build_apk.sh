#!/usr/bin/env bash
# Builds Hatch with a traceable build identity stamped in.
#
#   tool/build_apk.sh            # debug APK (default)
#   tool/build_apk.sh release    # release APK (needs key.properties)
#   tool/build_apk.sh install    # debug APK + adb install -r if a device is attached
#
# Version name comes from version.properties; the build number (Android versionCode) is the
# git commit count, so every commit produces an installable update. The git sha/branch/time are
# passed as Gradle properties and shown in the app (Settings footer; full detail in debug builds).
#
# Memory: this box is a 3.6 GB VPS. Every Gradle run goes through a lock so only one heavy
# process (Gradle, headless Firefox, lint jars) exists at a time. Never raise gradle.properties caps here.
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/opt/android-studio/jbr}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties

mode="${1:-debug}"
shift || true
extra=("$@")

sha=$(git rev-parse --short=9 HEAD 2>/dev/null || echo "nogit")
git diff --quiet 2>/dev/null || sha="${sha}-dirty"
branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "nogit")
number=$(git rev-list --count HEAD 2>/dev/null || echo 1)
name=$(grep '^versionName=' version.properties | cut -d= -f2)
when=$(date '+%Y-%m-%d %H:%M')

props=(-PgitSha="$sha" -PgitBranch="$branch" -PbuildTime="$when")

echo "Hatch $name (build $number) — $sha on $branch"

# With a remote build host configured (HATCH_BUILD_HOST or the gitignored .buildhost file), Gradle
# runs there via tool/remote_build.sh and the outputs are copied back; otherwise it runs here, under
# the lock that keeps this 3.6 GB box to one heavy process at a time.
run_gradle() {
  if [ -n "${HATCH_BUILD_HOST:-}" ] || [ -s .buildhost ]; then
    tool/remote_build.sh "${props[@]}" "$@"
  else
    flock /tmp/hatch-heavy.lock ./gradlew --no-daemon --max-workers=1 --console=plain "${props[@]}" "$@"
  fi
}

case "$mode" in
  debug|install)
    run_gradle :app:assembleDebug ${extra[@]+"${extra[@]}"}
    apk=app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
    ;;
  release)
    run_gradle :app:assembleRelease ${extra[@]+"${extra[@]}"}
    apk=app/build/outputs/apk/release/app-arm64-v8a-release.apk
    # Without key.properties AGP emits app-arm64-v8a-release-unsigned.apk (CI, fresh checkouts); a signed
    # build emits app-arm64-v8a-release.apk. Accept either and say which one in the dist name.
    if [ ! -f "$apk" ] && [ -f "${apk%.apk}-unsigned.apk" ]; then apk="${apk%.apk}-unsigned.apk"; mode="release-unsigned"; fi
    ;;
  *) echo "usage: tool/build_apk.sh [debug|release|install]" >&2; exit 1 ;;
esac

mkdir -p dist
out="dist/hatch-${name}-${number}-${sha}-${mode}.apk"
cp "$apk" "$out"
ls -l "$out"

if [ "$mode" = install ]; then
  if adb get-state >/dev/null 2>&1; then adb install -r "$out"; else echo "No device attached; sideload $out"; fi
fi
