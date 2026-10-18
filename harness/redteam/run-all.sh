#!/usr/bin/env bash
# Runs every red-team page for one persona (default win-firefox__us-east), serially, one Firefox at a time.
# run.mjs re-execs itself under flock /tmp/hatch-heavy.lock (+ systemd MemoryMax), so no outer flock here.
#   harness/redteam/run-all.sh [persona-id] [extra run.mjs flags...]
set -u
cd "$(dirname "$0")/../.."
persona="${1:-win-firefox__us-east}"; shift || true
status=0
for page in harness/redteam/[0-9][0-9]-*.html; do
  echo "=== $page ($persona)"
  node tool/headless/run.mjs --page "$page" --personas "$persona" "$@" || status=1
done
exit $status
