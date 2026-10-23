#!/usr/bin/env bash
# The full verification, against the ISOLATED devtest variant.
#
# The destructive instrumentation tests run against
# `tw.personal.privateinbox.devtest`, which has its own application id and
# therefore its own data directory. They cannot reach the production app's
# database.
#
# The prod variant is also verified, because the SHIPPED app is the prod one and
# its behaviour must not regress.
set -uo pipefail
cd "$(dirname "$0")/.."

source "$(dirname "$0")/_env.sh"
export PATH="$PATH:$ANDROID_SDK_ROOT/build-tools/36.0.0"

FAILED=0
step() {
  local name="$1"; shift
  echo ""
  echo "=== $name ==="
  if "$@"; then
    echo "--- $name: PASS"
  else
    echo "--- $name: FAIL"
    FAILED=$((FAILED + 1))
  fi
}

step "test isolation audit" python3 scripts/audit-test-isolation.py
step "unit tests" ./gradlew --no-daemon :app:testProdDebugUnitTest
step "lint" ./gradlew --no-daemon :app:lintProdDebug
step "assemble (prod + devtest + release)" \
  ./gradlew --no-daemon :app:assembleProdDebug :app:assembleDevtestDebug :app:assembleProdRelease
step "instrumentation (ISOLATED devtest variant)" \
  ./gradlew --no-daemon :app:connectedDevtestDebugAndroidTest
step "secret audit" bash scripts/audit-secrets.sh
step "release artifact audit" bash scripts/audit-release.sh
step "DHC connector tests" python3 integrations/dhc/tests/test_connector.py

echo ""
if [ "$FAILED" -eq 0 ]; then
  echo "RESULT: PASS — every check succeeded."
else
  echo "RESULT: FAIL — $FAILED check(s) failed."
fi
exit $FAILED
