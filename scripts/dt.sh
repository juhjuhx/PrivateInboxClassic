#!/usr/bin/env bash
# Build and test the devtest variant, with the environment resolved locally.
set -uo pipefail
cd "$(dirname "$0")/.."

source "$(dirname "$0")/_env.sh"

TASK="${1:-:app:assembleDevtestDebug}"
shift 2>/dev/null || true

./gradlew --no-daemon "$TASK" "$@" > /tmp/dsh-build.log 2>&1
CODE=$?
tail -25 /tmp/dsh-build.log
exit $CODE
