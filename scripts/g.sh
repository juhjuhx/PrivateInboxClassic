#!/usr/bin/env bash
# Build a Gradle task with the workspace environment resolved locally.
#
# The environment (Gradle home, Android SDK, AVD home) may live next to the
# workspace rather than next to the worktree, so _env.sh resolves it here
# instead of expecting it from the caller's shell.
set -uo pipefail
cd "$(dirname "$0")/.."

source "$(dirname "$0")/_env.sh"

TASK="${1:-:app:assembleDevtestDebug}"
shift 2>/dev/null || true

./gradlew --no-daemon "$TASK" "$@" > /tmp/dsh-gradle.log 2>&1
CODE=$?
tail -25 /tmp/dsh-gradle.log
exit $CODE
