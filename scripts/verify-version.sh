#!/usr/bin/env bash
# Version regression test: prevents Gradle version drift from audit scripts.
#
# Verifies that app/build.gradle.kts versionCode/versionName match the
# EXPECTED_VERSION_* constants in scripts/audit-release.sh.
#
# Usage: bash scripts/verify-version.sh
set -uo pipefail
cd "$(dirname "$0")/.."

BUILD_FILE="app/build.gradle.kts"
AUDIT_FILE="scripts/audit-release.sh"

fail=0
ok()   { printf '  OK   %s\n' "$*"; }
bad()  { printf '  FAIL %s\n' "$*"; fail=1; }

echo "=== Version regression check ==="

# Extract expected versions from audit script
AUDIT_VC=$(grep -E '^EXPECTED_VERSION_CODE=' "$AUDIT_FILE" | cut -d= -f2 | tr -d '"')
AUDIT_VN=$(grep -E '^EXPECTED_VERSION_NAME=' "$AUDIT_FILE" | cut -d= -f2 | tr -d '"')

# Extract actual versions from build.gradle.kts
BUILD_VC=$(grep -E '^\s*versionCode\s*=' "$BUILD_FILE" | head -1 | sed 's/.*versionCode *= *//' | tr -d '"')
BUILD_VN=$(grep -E '^\s*versionName\s*=' "$BUILD_FILE" | head -1 | sed 's/.*versionName *= *//' | tr -d '"')

ok "audit-release.sh expects versionCode=$AUDIT_VC versionName=$AUDIT_VN"
ok "build.gradle.kts has versionCode=$BUILD_VC versionName=$BUILD_VN"

if [ "$AUDIT_VC" = "$BUILD_VC" ]; then
  ok "versionCode matches ($AUDIT_VC)"
else
  bad "versionCode MISMATCH: audit=$AUDIT_VC build=$BUILD_VC"
fi

if [ "$AUDIT_VN" = "$BUILD_VN" ]; then
  ok "versionName matches ($AUDIT_VN)"
else
  bad "versionName MISMATCH: audit=$AUDIT_VN build=$BUILD_VN"
fi

echo ""
if [ "$fail" -eq 0 ]; then
  echo "RESULT: PASS — version constants are in sync."
  exit 0
else
  echo "RESULT: FAIL — version drift detected."
  exit 1
fi
