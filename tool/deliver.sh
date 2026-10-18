#!/usr/bin/env bash
# Sends an APK to a phone on the tailnet with Taildrop (no browser, resumable, stays on the tailnet).
#
#   tool/deliver.sh [apk] [device]     default: newest dist/*.apk, device from $HATCH_PHONE or "pixel-11-pro"
#
# The phone receives it in the Tailscale app (Android: lands in Downloads); tap to install.
set -euo pipefail
cd "$(dirname "$0")/.."
apk="${1:-$(ls -t dist/*.apk 2>/dev/null | head -1 || true)}"
device="${2:-${HATCH_PHONE:-pixel-11-pro}}"
[ -n "$apk" ] && [ -f "$apk" ] || { echo "deliver: no APK (build one with tool/build_apk.sh)" >&2; exit 2; }
tailscale file cp --targets | grep -q "$device" || { echo "deliver: $device is not a Taildrop target (tailscale file cp --targets)" >&2; exit 1; }
echo "deliver: $(du -h "$apk" | cut -f1) $apk -> $device"
exec tailscale file cp "$apk" "$device:"
