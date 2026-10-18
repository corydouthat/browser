#!/usr/bin/env bash
# Size budgets for the release build (spec "Automated checks"): arm64 APK <= 110 MB (measured 103.1 MB for the
# R8 release build with compressed native libs, PERF-01; the original 95 MB assumed a smaller libxul), the bundled
# self-check harness (assets/selfcheck/) <= 2 MB, the built-in extension (assets/extension/, excluding
# vendor/eruda.min.js which has its own line) <= 200 KB. Writes build/size.json and exits 1 on a breach.
#
#   tool/check_apk_size.sh [path/to/app-arm64-v8a-release.apk]
set -euo pipefail
cd "$(dirname "$0")/.."

APK_LIMIT=$((110 * 1024 * 1024))
SELFCHECK_LIMIT=$((2 * 1024 * 1024))
EXTENSION_LIMIT=$((200 * 1024))
# resources.arsc is stored uncompressed (targetSdk >= 30); with the "en" locale filter (PERF-02) it must stay
# small. Without the filter the AC/androidx/material string tables for ~150 locales pushed it to 4.1 MB.
ARSC_LIMIT=$((2 * 1024 * 1024))

apk="${1:-}"
if [ -z "$apk" ]; then
  apk="$(ls -t app/build/outputs/apk/release/*arm64*.apk dist/*release*.apk 2>/dev/null | head -1 || true)"
fi
if [ -z "$apk" ] || [ ! -f "$apk" ]; then
  echo "no release APK found (build it with tool/build_apk.sh release or pass a path)" >&2
  exit 2
fi
command -v unzip >/dev/null || { echo "unzip missing" >&2; exit 2; }

# Uncompressed byte total of every APK entry under a prefix (0 when none). One `unzip -l` pass: the
# listing is "<length> <date> <time> <name>"; the per-entry -Zl form printed a file count, not bytes.
asset_bytes() {
  unzip -l "$apk" 2>/dev/null | awk -v p="$1" 'NF >= 4 && index($4, p) == 1 { s += $1 } END { printf "%d", s + 0 }'
}

apk_bytes=$(stat -c %s "$apk")
selfcheck_bytes=$(asset_bytes 'assets/selfcheck/')
extension_all=$(asset_bytes 'assets/extension/')
extension_vendor=$(asset_bytes 'assets/extension/vendor/')
extension_bytes=$((extension_all - extension_vendor))
abis=$(unzip -Z1 "$apk" | grep -E '^lib/[^/]+/' | cut -d/ -f2 | sort -u | paste -sd, -)
# Native libraries stored uncompressed ("Stored" in `unzip -v`): must be 0 with jniLibs.useLegacyPackaging
# (PERF-01); a regression to uncompressed packaging shows up here before the APK budget does.
lib_stored_bytes=$(unzip -v "$apk" 2>/dev/null | awk 'NF >= 8 && $2 == "Stored" && index($NF, "lib/") == 1 { s += $1 } END { printf "%d", s + 0 }')
lib_bytes=$(asset_bytes 'lib/')
arsc_bytes=$(asset_bytes 'resources.arsc')

mkdir -p build
status="ok"
problems=()
[ "$apk_bytes" -le "$APK_LIMIT" ] || { status="over"; problems+=("apk $apk_bytes > $APK_LIMIT"); }
[ "$selfcheck_bytes" -le "$SELFCHECK_LIMIT" ] || { status="over"; problems+=("selfcheck $selfcheck_bytes > $SELFCHECK_LIMIT"); }
[ "$extension_bytes" -le "$EXTENSION_LIMIT" ] || { status="over"; problems+=("extension $extension_bytes > $EXTENSION_LIMIT"); }
[ "$arsc_bytes" -le "$ARSC_LIMIT" ] || { status="over"; problems+=("resources.arsc $arsc_bytes > $ARSC_LIMIT (locale filter lost?)"); }
case "$abis" in *x86*|*armeabi-v7a*) status="over"; problems+=("unexpected ABIs: $abis");; esac

cat > build/size.json <<JSON
{
  "apk": "$apk",
  "abis": "$abis",
  "bytes": {
    "apk": $apk_bytes,
    "selfcheck": $selfcheck_bytes,
    "extension": $extension_bytes,
    "extensionVendor": $extension_vendor,
    "libUncompressed": $lib_bytes,
    "libStoredUncompressedInApk": $lib_stored_bytes,
    "resourcesArsc": $arsc_bytes
  },
  "limits": {
    "apk": $APK_LIMIT,
    "selfcheck": $SELFCHECK_LIMIT,
    "extension": $EXTENSION_LIMIT,
    "resourcesArsc": $ARSC_LIMIT
  },
  "status": "$status",
  "problems": [$(printf '"%s",' "${problems[@]+"${problems[@]}"}" | sed 's/,$//')]
}
JSON

mb() { awk -v b="$1" 'BEGIN { printf "%.2f MB", b / 1048576 }'; }
echo "APK       $(mb "$apk_bytes") (limit $(mb "$APK_LIMIT")) abis=$abis"
echo "selfcheck $(mb "$selfcheck_bytes") (limit $(mb "$SELFCHECK_LIMIT"))"
echo "extension $(mb "$extension_bytes") (limit $(mb "$EXTENSION_LIMIT"); vendor/eruda $(mb "$extension_vendor") excluded)"
echo "lib/      $(mb "$lib_bytes") uncompressed, $(mb "$lib_stored_bytes") of it Stored (uncompressed) in the APK"
echo "arsc      $(mb "$arsc_bytes") (limit $(mb "$ARSC_LIMIT"))"
echo "-> build/size.json"
if [ "$status" != "ok" ]; then
  printf 'SIZE BUDGET EXCEEDED: %s\n' "${problems[@]}" >&2
  exit 1
fi
