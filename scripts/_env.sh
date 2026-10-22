#!/usr/bin/env bash
# Shared toolchain discovery for the scripts in this directory.
#
# A checkout may keep Gradle, the Android SDK and the AVD home beside the
# repository instead of under $HOME, so look there first and fall back to the
# usual locations. Every variable can still be overridden from the caller's
# environment.
#
# Usage: source "$(dirname "$0")/_env.sh"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

_find_dir() {
  local name="$1"
  local candidate
  for candidate in "$ROOT/../$name" "$ROOT/../../$name" "$HOME/$name"; do
    if [ -n "$candidate" ] && [ -d "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  printf '%s\n' "$ROOT/../$name"
}

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$(_find_dir gradle_home)}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$(_find_dir android-sdk)}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$(_find_dir .android-home)}"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$PATH"
