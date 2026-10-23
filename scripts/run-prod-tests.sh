#!/usr/bin/env bash
# Run the instrumentation suite against the PROD variant.
#
# The prod variant is the SHIPPED application, so its behaviour must not
# regress. The destructive tests in it run against the production package,
# which is why they are guarded by DestructiveOperationGuard: on a device with
# the real app and real messages, they refuse to run rather than deleting
# anything.
set -uo pipefail
cd "$(dirname "$0")/.."

source "$(dirname "$0")/_env.sh"
export PATH="$PATH:$ANDROID_SDK_ROOT/build-tools/36.0.0"

CLASS_FILTER="${1:-}"
if [ -n "$CLASS_FILTER" ]; then
  ./gradlew --no-daemon :app:connectedProdDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$CLASS_FILTER" 2>&1 | tail -12
else
  ./gradlew --no-daemon :app:connectedProdDebugAndroidTest 2>&1 | tail -12
fi
