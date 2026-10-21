#!/usr/bin/env bash
# Build and test the devtest variant (the ISOLATED application id).
set -uo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/gradle_home}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$HOME/.android-home}"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$PATH"

TASK="${1:-:app:assembleDevtestDebug}"
shift || true

./gradlew --no-daemon "$TASK" "$@" 2>&1 | tail -30
