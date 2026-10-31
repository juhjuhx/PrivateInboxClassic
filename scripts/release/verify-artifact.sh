#!/usr/bin/env bash
# Verify a Private Inbox Classic release artifact end to end.
#
# Usage:
#   scripts/release/verify-artifact.sh <apk-path> [expected-sha256]
#
# Checks, in order:
#   1. SHA-256 of the artifact (against an optional expected value)
#   2. APK signature validity (apksigner verify)
#   3. The signing certificate fingerprints (so they can be compared with
#      what a user has installed)
#   4. Package id / version code / version name / permissions from the APK
#   5. INTERNET permission must be ABSENT
#   6. The currently installed package on the device (if one is connected)
#      and whether this APK could replace it (signature compatibility)
#
# Exit codes: 0 = all checks passed, 1 = a check failed, 2 = usage error.
# No network access is performed by this script.

set -uo pipefail

APK="${1:-}"
EXPECTED_SHA="${2:-}"

die() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }
info() { printf '%s\n' "$1"; }

[ -n "$APK" ] || { echo "usage: $0 <apk-path> [expected-sha256]" >&2; exit 2; }
[ -f "$APK" ] || die "artifact not found: $APK"

# ---------------------------------------------------------------- tooling
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$ANDROID_SDK_ROOT" ]; then
  die "ANDROID_SDK_ROOT is not set. Source scripts/_env.sh first."
fi

APKSIGNER="$(ls -1 "$ANDROID_SDK_ROOT"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)"
AAPT2="$(ls -1 "$ANDROID_SDK_ROOT"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)"
[ -n "$APKSIGNER" ] || die "apksigner not found under $ANDROID_SDK_ROOT/build-tools/*"
[ -n "$AAPT2" ] || die "aapt2 not found under $ANDROID_SDK_ROOT/build-tools/*"

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  # apksigner is a shell wrapper around java; find a usable JDK.
  for c in /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
           /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/temurin-17-jdk; do
    if [ -x "$c/bin/java" ]; then JAVA_HOME="$c"; export JAVA_HOME; break; fi
  done
fi
[ -n "${JAVA_HOME:-}" ] || die "JAVA_HOME is not set and no JDK 17 was found."

# ---------------------------------------------------------------- 1. SHA-256
info "== 1. SHA-256 =="
if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL_SHA="$(sha256sum "$APK" | awk '{print $1}')"
elif command -v shasum >/dev/null 2>&1; then
  ACTUAL_SHA="$(shasum -a 256 "$APK" | awk '{print $1}')"
else
  die "neither sha256sum nor shasum is available"
fi
info "  actual   : $ACTUAL_SHA"
if [ -n "$EXPECTED_SHA" ]; then
  # tr keeps this POSIX-compatible: macOS ships bash 3.2, which has no ${x,,}.
  A_NORM="$(printf '%s' "$ACTUAL_SHA" | tr 'A-F' 'a-f')"
  E_NORM="$(printf '%s' "$EXPECTED_SHA" | tr 'A-F' 'a-f')"
  if [ "$A_NORM" = "$E_NORM" ]; then
    info "  expected : $EXPECTED_SHA  -> MATCH"
  else
    die "SHA-256 mismatch. expected $EXPECTED_SHA, got $ACTUAL_SHA"
  fi
else
  info "  expected : (not supplied) -> recorded, not verified"
fi

# ---------------------------------------------------------------- 2. signature
info "== 2. Signature =="
if ! "$APKSIGNER" verify "$APK" >/tmp/pi-verify.$$ 2>&1; then
  cat /tmp/pi-verify.$$ >&2
  die "apksigner verify failed — the APK signature is invalid"
fi
info "  apksigner verify: OK"
CERTS="$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null)"
CERT_SHA256="$(printf '%s\n' "$CERTS" | awk '/certificate SHA-256 digest/{print $NF; exit}')"
CERT_SHA1="$(printf '%s\n' "$CERTS" | awk '/certificate SHA-1 digest/{print $NF; exit}')"
info "  cert SHA-256: ${CERT_SHA256:-<unknown>}"
info "  cert SHA-1  : ${CERT_SHA1:-<unknown>}"

# ---------------------------------------------------------------- 3. identity
info "== 3. Package identity =="
BADGING="$("$AAPT2" dump badging "$APK" 2>/dev/null)"
PKG_NAME="$(printf '%s\n' "$BADGING" | awk -F"'" '/^package: name=/{print $2; exit}')"
VERSION_CODE="$(printf '%s\n' "$BADGING" | sed -n "s/^package:.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)"
VERSION_NAME="$(printf '%s\n' "$BADGING" | sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" | head -1)"
info "  package      : ${PKG_NAME:-<unknown>}"
info "  versionCode  : ${VERSION_CODE:-<unknown>}"
info "  versionName  : ${VERSION_NAME:-<unknown>}"
[ -n "$PKG_NAME" ] || die "could not read the package name"

# ---------------------------------------------------------------- 4. privacy
info "== 4. Privacy permissions =="
if printf '%s\n' "$BADGING" | grep -q "uses-permission: name='android.permission.INTERNET'"; then
  die "INTERNET permission is PRESENT. This app must never ship with it."
fi
info "  INTERNET permission: absent (required)"
info "  declared permissions:"
printf '%s\n' "$BADGING" | sed -n "s/^uses-permission: name='\(.*\)'/\1/p" | sed 's/^/    - /'

# ---------------------------------------------------------------- 5. installed
info "== 5. Installed package on device =="
if command -v adb >/dev/null 2>&1 && adb get-state >/dev/null 2>&1; then
  if adb shell pm path "$PKG_NAME" 2>/dev/null | grep -q '^package:'; then
    DEVICE_APK="/tmp/pi-device-$$.apk"
    DEVPATH="$(adb shell pm path "$PKG_NAME" | head -1 | sed 's/^package://' | tr -d '\r')"
    adb pull "$DEVPATH" "$DEVICE_APK" >/dev/null 2>&1 || true
    if [ -s "$DEVICE_APK" ]; then
      DEVICE_SHA="$("$APKSIGNER" verify --print-certs "$DEVICE_APK" 2>/dev/null \
        | awk '/certificate SHA-256 digest/{print $NF; exit}')"
      info "  installed   : yes ($PKG_NAME)"
      info "  device cert : ${DEVICE_SHA:-<unknown>}"
      if [ -n "$DEVICE_SHA" ] && [ "$DEVICE_SHA" = "$CERT_SHA256" ]; then
        info "  compatibility: SAME SIGNATURE -> 'adb install -r' is safe (data preserved)"
      else
        info "  compatibility: DIFFERENT SIGNATURE -> DO NOT install -r."
        info "                  Android would refuse it. Uninstalling would DESTROY"
        info "                  the user's archived messages. Offer the encrypted"
        info "                  backup/restore flow instead."
      fi
    else
      info "  installed   : yes, but the APK could not be pulled for comparison"
    fi
    rm -f "$DEVICE_APK"
  else
    info "  installed   : no ($PKG_NAME is not on the device) -> clean install"
  fi
else
  info "  installed   : not checked (no device/emulator reachable)"
fi

rm -f /tmp/pi-verify.$$
info ""
info "ALL CHECKS PASSED"
