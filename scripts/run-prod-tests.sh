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

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/gradle_home}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$HOME/.android-home}"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$ANDROID_SDK_ROOT/build-tools/36.0.0:$PATH"

CLASS_FILTER="${1:-}"
if [ -n "$CLASS_FILTER" ]; then
  ./gradlew --no-daemon :app:connectedProdDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$CLASS_FILTER" 2>&1 | tail -12
else
  ./gradlew --no-daemon :app:connectedProdDebugAndroidTest 2>&1 | tail -12
fi
