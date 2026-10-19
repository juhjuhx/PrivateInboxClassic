#!/usr/bin/env bash
# Runs the JVM unit tests for the Android-free policy and image-validation logic.
# Uses the project's own Gradle wrapper so the same tests run locally and in CI.
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew --no-daemon :app:testDebugUnitTest

echo
echo "--- test report ---"
shopt -s nullglob
for f in app/build/test-results/testDebugUnitTest/*.xml; do
  python3 - "$f" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
print(f"{root.get('name')}: tests={root.get('tests')} "
      f"failures={root.get('failures')} errors={root.get('errors')} "
      f"skipped={root.get('skipped')}")
PY
done
