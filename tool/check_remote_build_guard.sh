#!/usr/bin/env bash
# RS-07 guard: a Paparazzi / snapshot invocation must never fall back to a local Gradle run on this box.
# Points tool/remote_build.sh at a host that cannot resolve and checks that it refuses (exit 3) without Gradle.
set -euo pipefail
cd "$(dirname "$0")/.."
# remote_build.sh rewrites build/nuc-auth-needed.txt on a failed probe; keep a real notice intact.
notice=$(mktemp)
had_notice=0
if [ -f build/nuc-auth-needed.txt ]; then cp build/nuc-auth-needed.txt "$notice"; had_notice=1; fi
restore() { if [ "$had_notice" = 1 ]; then cp "$notice" build/nuc-auth-needed.txt; else rm -f build/nuc-auth-needed.txt; fi; rm -f "$notice"; }
trap restore EXIT
for args in ":app:recordPaparazziDebug" ":app:testDebugUnitTest -Phatch.snapshots=true"; do
  set +e
  # shellcheck disable=SC2086
  out=$(HATCH_BUILD_HOST=hatch-guard.invalid tool/remote_build.sh $args 2>&1)
  rc=$?
  set -e
  if [ "$rc" != 3 ] || grep -q "Starting a Gradle Daemon\|BUILD SUCCESSFUL\|BUILD FAILED\|> Task" <<<"$out"; then
    echo "check_remote_build_guard: '$args' was not refused (exit $rc):" >&2
    echo "$out" >&2
    exit 1
  fi
done
echo "check_remote_build_guard: ok (snapshot runs refuse the local fallback)"
