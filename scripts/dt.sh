#!/usr/bin/env bash
# Build and test the devtest variant, with the environment resolved locally.
set -uo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export GRADLE_USER_HOME="$HOME/gradle_home"
export ANDROID_SDK_ROOT="$HOME/android-sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_USER_HOME="$HOME/.android-home"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$PATH"

TASK="${1:-:app:assembleDevtestDebug}"
shift 2>/dev/null || true

./gradlew --no-daemon "$TASK" "$@" > /tmp/dsh-build.log 2>&1
CODE=$?
tail -25 /tmp/dsh-build.log
exit $CODE
