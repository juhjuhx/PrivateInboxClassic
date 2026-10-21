#!/usr/bin/env bash
# Run the burst capture regression tests, N times.
#
# The burst tests drive the capture pipeline with synthetic group notifications
# that are updated in place, the way a group chat behaves. They measure what
# reaches the DATABASE, with the four denominators the brief requires:
#
#   post_attempts                        - notify() calls made by the test
#   listener_callbacks                   - times the listener was called
#   logical_messages_exposed_to_listener - DISTINCT messages the listener saw
#   db_saved                             - rows in the database
#
# Usage: bash scripts/run-burst-tests.sh [--repeat N]
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"

find_dir() {
  local name="$1"
  local env_value="${!1-}"
  for candidate in "$env_value" "$ROOT/../$name" "$ROOT/../../$name" \
                   "$HOME/$name"; do
    if [ -n "$candidate" ] && [ -d "$candidate" ]; then
      echo "$candidate"
      return 0
    fi
  done
  echo ""
}

export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$(find_dir gradle_home)}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$(find_dir android-sdk)}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$(find_dir .android-home)}"
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$PATH"

REPEAT=1
while [ "${1:-}" != "" ]; do
  case "$1" in
    --repeat) REPEAT="$2"; shift 2 ;;
    *) shift ;;
  esac
done

echo "=== device ==="
"$ADB" devices | tail -n +2
echo "android:  $("$ADB" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
echo "sdk:      $("$ADB" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
echo "model:    $("$ADB" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
echo "abi:      $("$ADB" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')"

FAILED=0
for run in $(seq 1 "$REPEAT"); do
  echo ""
  echo "=== burst run $run/$REPEAT ==="
  "$ADB" logcat -c
  ./gradlew --no-daemon :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=tw.personal.privateinbox.BurstCaptureInstrumentedTest
  RESULT=$?
  echo "--- burst metrics (run $run) ---"
  "$ADB" logcat -d 2>&1 | grep "BURST-" | sed -E 's/.*System.out: //' | grep -v "INSTALL-FAILED"
  if [ $RESULT -ne 0 ]; then
    FAILED=$((FAILED + 1))
  fi
done

echo ""
if [ "$FAILED" -eq 0 ]; then
  echo "RESULT: PASS - all $REPEAT run(s) passed."
else
  echo "RESULT: FAIL - $FAILED of $REPEAT run(s) failed."
fi
exit $FAILED
