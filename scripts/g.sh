#!/usr/bin/env bash
# Build a Gradle task with the workspace environment resolved locally.
#
# The environment (Gradle home, Android SDK, AVD home) lives next to the
# WORKSPACE, not next to the worktree, so it is resolved here rather than
# expected from the caller's shell.
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

./gradlew --no-daemon "$TASK" "$@" > /tmp/dsh-gradle.log 2>&1
CODE=$?
tail -25 /tmp/dsh-gradle.log
exit $CODE
