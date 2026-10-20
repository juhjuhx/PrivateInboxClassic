#!/usr/bin/env bash
# Host-side DHC interop check.
#
# The Android instrumentation test writes a bundle into the app's external
# files directory. This script pulls it to the host and runs the Python
# reference importer against it, proving that a normal desktop tool can
# decrypt and read what the Android exporter produced.
#
# Usage: bash integrations/dhc/tests/host_interop.sh [bundle-path-on-host]
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
PKG="tw.personal.privateinbox"
PASSPHRASE="correct horse battery staple"
OUT_DIR="$ROOT/app/build/outputs"
REPORT="$OUT_DIR/dhc-interop-report.json"
BUNDLE="$OUT_DIR/dhc-export-test.picbox"

mkdir -p "$OUT_DIR"

if [ "${1:-}" != "" ]; then
  BUNDLE="$1"
fi

if [ ! -f "$BUNDLE" ]; then
  # Pull it from the device. The app's external files dir is world-readable
  # through adb, which is how the bundle gets from the sandbox to the host.
  # The bundle is written to shared storage (the app's external files dir is
  # removed when the instrumentation run uninstalls the app).
  DEVICE_PATH="/sdcard/Download/dhc-export-test.picbox"
  FALLBACK_PATH="/sdcard/Android/data/$PKG/files/dhc-export-test.picbox"
  ADB="${ADB:-adb}"
  if ! "$ADB" pull "$DEVICE_PATH" "$BUNDLE" > /dev/null 2>&1; then
    "$ADB" pull "$FALLBACK_PATH" "$BUNDLE" > /dev/null 2>&1 || true
  fi
  if [ ! -f "$BUNDLE" ]; then
    echo "RESULT: SKIP - no bundle available on the device."
    echo "        Run the DhcExportInstrumentedTest first, then this script."
    exit 3
  fi
fi

echo "=== DHC host interop check ==="
echo "bundle:  $BUNDLE ($(stat -f%z "$BUNDLE" 2>/dev/null || stat -c%s "$BUNDLE") bytes)"

python3 "$ROOT/integrations/dhc/tests/python_interop_check.py" \
  "$BUNDLE" "$PASSPHRASE" "$REPORT"
CORRECT_EXIT=$?

echo ""
echo "--- report ---"
cat "$REPORT" 2>/dev/null
echo ""

if [ "$CORRECT_EXIT" -ne 0 ]; then
  echo "RESULT: FAIL - the python importer could not read the bundle."
  exit 1
fi

# The wrong passphrase must be rejected.
WRONG_REPORT="$OUT_DIR/dhc-interop-wrongpass.json"
python3 "$ROOT/integrations/dhc/tests/python_interop_check.py" \
  "$BUNDLE" "definitely the wrong passphrase" "$WRONG_REPORT" > /dev/null 2>&1
if [ $? -eq 0 ]; then
  echo "RESULT: FAIL - the wrong passphrase was accepted."
  exit 1
fi
echo "OK: the wrong passphrase is rejected."

echo ""
echo "RESULT: PASS - Android export -> Python import works end to end."
