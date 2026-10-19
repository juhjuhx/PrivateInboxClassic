#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
TMPDIR_TEST="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_TEST"' EXIT
kotlinc app/src/main/java/tw/personal/privateinbox/CapturePolicy.kt \
  core-tests/CapturePolicyTest.kt -include-runtime -d "$TMPDIR_TEST/tests.jar"
java -jar "$TMPDIR_TEST/tests.jar"
