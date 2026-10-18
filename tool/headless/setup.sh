#!/usr/bin/env bash
# Idempotent setup for the device-free detection harness (see docs/detection-harness.md).
#
#   tool/headless/setup.sh            # Firefox 158.0 + geckodriver 0.37.1 + harness npm deps
#   tool/headless/setup.sh --no-npm   # binaries only
#
# What it does (nothing is executed except the Firefox / geckodriver binaries themselves):
#   1. Firefox 158.0 linux-x86_64 en-US tarball from ftp.mozilla.org, sha256 checked against the
#      release SHA256SUMS file, extracted to ~/opt/firefox-158.0 (the runner asserts browserVersion 158.*).
#   2. geckodriver v0.37.1 linux64 from GitHub releases into ~/opt/bin. The release carries only a
#      GPG .asc, no checksum asset, so the sha256 observed on 2026-10-18 is pinned here.
#   3. `npm ci` in harness/ (selenium-webdriver, esbuild, the vendored detector libraries).
#
# Every download lands in a fresh temporary directory and is verified before extraction. Re-running
# with everything in place is a no-op (a few stat calls).
set -euo pipefail

FIREFOX_VERSION="${FIREFOX_VERSION:-158.0}"
GECKODRIVER_VERSION="${GECKODRIVER_VERSION:-0.37.1}"
# sha256 of geckodriver-v0.37.1-linux64.tar.gz as downloaded from GitHub on 2026-10-18.
GECKODRIVER_SHA256="e815130ea95983e162ae91843b48d3a3ce991735635fce83a647afde21e09f7e"

OPT="${HATCH_OPT:-$HOME/opt}"
FF_DIR="$OPT/firefox-$FIREFOX_VERSION"
BIN_DIR="$OPT/bin"
FF_BASE="https://ftp.mozilla.org/pub/firefox/releases/$FIREFOX_VERSION"
FF_REL="linux-x86_64/en-US/firefox-$FIREFOX_VERSION.tar.xz"
GD_FILE="geckodriver-v$GECKODRIVER_VERSION-linux64.tar.gz"
GD_URL="https://github.com/mozilla/geckodriver/releases/download/v$GECKODRIVER_VERSION/$GD_FILE"

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
NPM=1
for a in "$@"; do
  case "$a" in
    --no-npm) NPM=0 ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "unknown flag: $a" >&2; exit 2 ;;
  esac
done

log() { printf '[setup] %s\n' "$*"; }
need() { command -v "$1" >/dev/null 2>&1 || { echo "missing tool: $1" >&2; exit 1; }; }
need curl; need tar; need xz; need sha256sum; need mktemp

mkdir -p "$OPT" "$BIN_DIR"

# ---- Firefox -------------------------------------------------------------------------------------------
if [ -x "$FF_DIR/firefox" ] && [ -f "$FF_DIR/application.ini" ] \
   && grep -q "^Version=$FIREFOX_VERSION\$" "$FF_DIR/application.ini"; then
  log "Firefox $FIREFOX_VERSION already at $FF_DIR"
else
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/hatch-firefox.XXXXXX")"
  trap 'rm -rf "$tmp"' EXIT
  log "downloading $FF_BASE/SHA256SUMS"
  curl -fsSL --retry 3 -o "$tmp/SHA256SUMS" "$FF_BASE/SHA256SUMS"
  expected="$(awk -v f="$FF_REL" '$2 == f { print $1 }' "$tmp/SHA256SUMS")"
  if [ -z "$expected" ]; then
    echo "SHA256SUMS has no entry for $FF_REL" >&2; exit 1
  fi
  log "downloading $FF_BASE/$FF_REL"
  curl -fsSL --retry 3 -o "$tmp/firefox.tar.xz" "$FF_BASE/$FF_REL"
  actual="$(sha256sum "$tmp/firefox.tar.xz" | awk '{ print $1 }')"
  if [ "$actual" != "$expected" ]; then
    echo "Firefox tarball sha256 mismatch: expected $expected got $actual" >&2; exit 1
  fi
  log "sha256 ok ($expected)"
  mkdir -p "$tmp/x"
  tar -xJf "$tmp/firefox.tar.xz" -C "$tmp/x"
  [ -x "$tmp/x/firefox/firefox" ] || { echo "tarball did not contain firefox/firefox" >&2; exit 1; }
  rm -rf "$FF_DIR"
  mv "$tmp/x/firefox" "$FF_DIR"
  log "Firefox $FIREFOX_VERSION installed at $FF_DIR"
  rm -rf "$tmp"; trap - EXIT
fi
"$FF_DIR/firefox" --version 2>/dev/null | sed 's/^/[setup] /' || true

# ---- geckodriver -----------------------------------------------------------------------------------
gd="$BIN_DIR/geckodriver"
if [ -x "$gd" ] && "$gd" --version 2>/dev/null | grep -q "geckodriver $GECKODRIVER_VERSION"; then
  log "geckodriver $GECKODRIVER_VERSION already at $gd"
else
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/hatch-geckodriver.XXXXXX")"
  trap 'rm -rf "$tmp"' EXIT
  log "downloading $GD_URL"
  curl -fsSL --retry 3 -o "$tmp/$GD_FILE" "$GD_URL"
  actual="$(sha256sum "$tmp/$GD_FILE" | awk '{ print $1 }')"
  if [ "$actual" != "$GECKODRIVER_SHA256" ]; then
    echo "geckodriver tarball sha256 mismatch: expected $GECKODRIVER_SHA256 got $actual" >&2
    echo "(upstream publishes no checksum asset; if the release was re-cut, verify the .asc signature and update GECKODRIVER_SHA256)" >&2
    exit 1
  fi
  log "sha256 ok ($actual)"
  tar -xzf "$tmp/$GD_FILE" -C "$tmp" geckodriver
  install -m 0755 "$tmp/geckodriver" "$gd"
  log "geckodriver installed at $gd"
  rm -rf "$tmp"; trap - EXIT
fi
"$gd" --version 2>/dev/null | head -1 | sed 's/^/[setup] /' || true

# ---- harness npm deps ------------------------------------------------------------------------------------
if [ "$NPM" = 1 ]; then
  need npm
  if [ ! -f "$REPO/harness/package-lock.json" ]; then
    echo "harness/package-lock.json missing; run 'npm install' in harness/ once (under flock /tmp/hatch-heavy.lock)" >&2
    exit 1
  fi
  # npm can exceed the 300 MB RSS budget of this box; it runs under the heavy-process lock.
  log "npm ci in $REPO/harness (waiting for /tmp/hatch-heavy.lock if a build is running)"
  ( cd "$REPO/harness" && flock /tmp/hatch-heavy.lock npm ci --no-audit --no-fund --loglevel=error )
  log "npm deps ready"
fi

log "done: FIREFOX_BIN=$FF_DIR/firefox GECKODRIVER=$gd"
