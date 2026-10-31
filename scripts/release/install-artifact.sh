#!/usr/bin/env bash
# Install a verified Private Inbox Classic APK without ever destroying data.
#
# Usage:
#   scripts/release/install-artifact.sh <apk-path> [expected-sha256]
#
# Safety model (the whole point of this script):
#   * 'adb install -r' is used ONLY when the installed package's signature
#     matches the artifact's signature.
#   * When the signatures differ, the script STOPS and reports a certificate
#     mismatch. It never uninstalls, never wipes, and never force-installs.
#   * No cross-signature backup/restore is available (Portable Archive Import
#     is not complete). Data on the existing install cannot be migrated.
#   * The app has no INTERNET permission and this script performs no network
#     access of its own.
#
# Exit codes: 0 = installed, 1 = refused/failed, 2 = usage error.

set -uo pipefail

APK="${1:-}"
EXPECTED_SHA="${2:-}"
[ -n "$APK" ] || { echo "usage: $0 <apk-path> [expected-sha256]" >&2; exit 2; }
[ -f "$APK" ] || { printf 'FAIL: artifact not found: %s\n' "$APK" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 1..5 are all the read-only verification steps.
VERIFY_OUT="$("$SCRIPT_DIR/verify-artifact.sh" "$APK" "$EXPECTED_SHA" 2>&1)"
VERIFY_RC=$?
printf '%s\n' "$VERIFY_OUT"
if [ $VERIFY_RC -ne 0 ]; then
  echo ""
  echo "REFUSING TO INSTALL: the artifact failed verification."
  exit 1
fi
# The signature comparison below is the real gate; verify-artifact only reports.

# ---------------------------------------------------------------- device
command -v adb >/dev/null 2>&1 || { echo "FAIL: adb not found in PATH" >&2; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "FAIL: no device/emulator reachable" >&2; exit 1; }

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
APKSIGNER="$(ls -1 "$ANDROID_SDK_ROOT"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)"
[ -z "${JAVA_HOME:-}" ] && JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export JAVA_HOME

ARTIFACT_CERT="$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null \
  | awk '/certificate SHA-256 digest/{print $NF; exit}')"
AAPT2_BIN="$(ls -1 "$ANDROID_SDK_ROOT"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)"
[ -n "$AAPT2_BIN" ] || { echo "FAIL: aapt2 not found" >&2; exit 1; }
PKG_NAME="$("$AAPT2_BIN" dump badging "$APK" 2>/dev/null | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1)"
[ -n "$PKG_NAME" ] || { echo "FAIL: could not determine the package name" >&2; exit 1; }

echo ""
echo "== Device install decision =="
if adb shell pm path "$PKG_NAME" 2>/dev/null | grep -q '^package:'; then
  DEVPATH="$(adb shell pm path "$PKG_NAME" | head -1 | sed 's/^package://' | tr -d '\r')"
  TMP="/tmp/pi-installed-$$.apk"
  adb pull "$DEVPATH" "$TMP" >/dev/null 2>&1 || true
  if [ -s "$TMP" ]; then
    DEVICE_CERT="$("$APKSIGNER" verify --print-certs "$TMP" 2>/dev/null \
      | awk '/certificate SHA-256 digest/{print $NF; exit}')"
    rm -f "$TMP"
    if [ -n "$DEVICE_CERT" ] && [ "$DEVICE_CERT" = "$ARTIFACT_CERT" ]; then
      echo "  signatures MATCH -> upgrading in place, user data preserved"
      adb install -r "$APK"
      RC=$?
      [ $RC -eq 0 ] && echo "INSTALLED (upgrade, data preserved)"
      exit $RC
    fi
    echo ""
    echo "  SIGNATURE MISMATCH — refusing to install."
    echo ""
    echo "  The package on the device was signed with:"
    echo "    ${DEVICE_CERT:-<unknown>}"
    echo "  This artifact was signed with:"
    echo "    ${ARTIFACT_CERT:-<unknown>}"
    echo ""
    echo "  'adb install -r' would be rejected by Android, and uninstalling"
    echo "  would PERMANENTLY DELETE the user's archived messages."
    echo ""
    echo "  No supported in-place upgrade path exists yet."
    echo "  1. The Portable Archive Import feature is NOT complete — encrypted"
    echo "     backup/restore across signature changes is not available."
    echo "  2. Do NOT uninstall the installed app — its data cannot be recovered"
    echo "     without the archive import feature."
    echo "  3. Keep the currently installed version. To use this artifact, use a"
    echo "     separate device/emulator, or wait for the archive import feature."
    exit 1
  fi
  echo "  installed package found but could not be read for comparison — refusing."
  exit 1
fi

echo "  not currently installed -> clean install"
adb install "$APK"
RC=$?
[ $RC -eq 0 ] && echo "INSTALLED (clean install)"
exit $RC
