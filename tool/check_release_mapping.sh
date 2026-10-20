#!/usr/bin/env bash
# Guards the R8 mapping of a release build against the obfuscations that only fail on a device, where
# the VPS and CI cannot run the APK. Called by tool/build_apk.sh release; usage: check_release_mapping.sh <mapping.txt>
#
# 1. SnakeYAML (GeckoView's geckoview-config.yaml reader) must keep its package: TypeDescription's static
#    initialiser calls getPackage().getName(), which is null for a class R8 moved to the unnamed package.
set -euo pipefail
mapping="${1:-app/build/outputs/mapping/release/mapping.txt}"
[ -f "$mapping" ] || { echo "check_release_mapping: no mapping at $mapping" >&2; exit 1; }
fail=0
line=$(grep -m1 '^org\.yaml\.snakeyaml\.TypeDescription -> ' "$mapping" || true)
if [ -z "$line" ]; then
  echo "check_release_mapping: org.yaml.snakeyaml.TypeDescription is missing from the mapping (removed or renamed out of reach?)" >&2
  fail=1
elif ! grep -q '^org\.yaml\.snakeyaml\.TypeDescription -> org\.yaml\.snakeyaml\.' <<<"$line"; then
  echo "check_release_mapping: SnakeYAML left its package ($line); release builds crash in GeckoRuntime.create — see proguard-rules.pro" >&2
  fail=1
fi
if [ "$fail" = 0 ]; then echo "check_release_mapping: ok ($line)"; fi
exit "$fail"
