#!/usr/bin/env bash
# Run the instrumentation suite against the ISOLATED devtest variant.
#
# The destructive tests (the ones that clear the database) run against
# `tw.personal.privateinbox.devtest`, which has its own application id and
# therefore its own data directory. They physically cannot reach the
# production app's database.
#
# The production variant is `tw.personal.privateinbox` — the same package the
# user installs. Destructive operations against it are REFUSED by
# DestructiveOperationGuard.
set -uo pipefail
cd "$(dirname "$0")/.."

source "$(dirname "$0")/_env.sh"
export PATH="$PATH:$ANDROID_SDK_ROOT/build-tools/36.0.0"

CLASS_FILTER="${1:-}"
shift || true

if [ -n "$CLASS_FILTER" ]; then
  ./gradlew --no-daemon :app:connectedDevtestDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$CLASS_FILTER" "$@" 2>&1 | tail -20
else
  ./gradlew --no-daemon :app:connectedDevtestDebugAndroidTest "$@" 2>&1 | tail -20
fi
