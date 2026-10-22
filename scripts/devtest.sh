#!/usr/bin/env bash
# Build and test the devtest variant (the ISOLATED application id).
set -uo pipefail
cd "$(dirname "$0")/.."

source "$(dirname "$0")/_env.sh"

TASK="${1:-:app:assembleDevtestDebug}"
shift || true

./gradlew --no-daemon "$TASK" "$@" 2>&1 | tail -30
