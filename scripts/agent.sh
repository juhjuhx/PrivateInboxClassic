#!/usr/bin/env bash
# Resume helper for Private Inbox Classic.
#
# Every subcommand is IDEMPOTENT: running it twice must not create a duplicate
# commit, a duplicate export, a duplicate repository, or delete anything.
#
#   status        show the current state
#   next          print the next concrete action
#   verify        run the verification suite
#   resume-check  verify that the tree is in a state a new agent can continue from
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

STATE_FILE="$ROOT/.agent/state.json"

print_status() {
  echo "=== Private Inbox Classic — status ==="
  echo "root:        $ROOT"
  echo "branch:      $(git branch --show-current 2>/dev/null || echo '<none>')"
  echo "head:        $(git rev-parse --short HEAD 2>/dev/null || echo '<none>')"
  echo "status:      $(git status --porcelain 2>/dev/null | wc -l | tr -d ' ') uncommitted change(s)"
  echo "disk free:   $(df -h . | tail -1 | awk '{print $4}')"
  if [ -f "$STATE_FILE" ]; then
    echo ""
    echo "=== .agent/state.json ==="
    cat "$STATE_FILE"
  fi
}

print_next() {
  if [ -f "$STATE_FILE" ] && command -v python3 > /dev/null 2>&1; then
    python3 - "$STATE_FILE" <<'PY'
import json, sys
try:
    with open(sys.argv[1], encoding="utf-8") as handle:
        state = json.load(handle)
except Exception as exc:
    print(f"could not read the state file: {exc}")
    raise SystemExit(1)
print(f"phase:        {state.get('current_phase')}")
print(f"task:         {state.get('current_task')}")
print(f"blockers:     {state.get('blockers')}")
print("")
print("NEXT ACTION:")
for step in state.get("next_actions", []):
    print(f"  - {step}")
PY
  else
    echo "no state file at $STATE_FILE; see docs/AGENT_STATE.md"
  fi
}

run_verify() {
  export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
  export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/../gradle_home}"
  export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ROOT/../android-sdk}"
  export ANDROID_HOME="$ANDROID_SDK_ROOT"
  export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$ROOT/../.android-home}"
  export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/build-tools/36.0.0:$PATH"

  echo "=== unit tests ==="
  ./gradlew --no-daemon :app:testProdDebugUnitTest || return 1
  echo "=== test isolation audit ==="
  python3 scripts/audit-test-isolation.py || return 1
  echo "=== lint ==="
  ./gradlew --no-daemon :app:lintProdDebug || return 1
  echo "=== build ==="
  ./gradlew --no-daemon :app:assembleProdDebug || return 1
  echo "=== static audits ==="
  bash scripts/audit.sh || return 1
  bash scripts/audit-secrets.sh || return 1
  if [ -f app/build/outputs/apk/release/app-release-unsigned.apk ]; then
    bash scripts/audit-release.sh || return 1
  else
    echo "  (release APK not built; run :app:assembleProdRelease first)"
  fi
  echo "=== python connector tests ==="
  python3 integrations/dhc/tests/test_connector.py || return 1
  echo ""
  echo "All checks completed."
}

resume_check() {
  echo "=== resume check ==="
  ok=0
  fail=0
  check() {
    if eval "$2" > /dev/null 2>&1; then
      echo "  OK   $1"
    else
      echo "  FAIL $1"
      fail=$((fail + 1))
    fi
  }

  check "the git tree is a repository" "git rev-parse --git-dir"
  check "there is a gradle wrapper" "test -x ./gradlew"
  check "the wrapper jar is present" "test -f gradle/wrapper/gradle-wrapper.jar"
  check "the wrapper checksum is pinned" \
    "grep -q distributionSha256Sum gradle/wrapper/gradle-wrapper.properties"
  check "the source is present" "test -d app/src/main/java/tw/personal/privateinbox"
  # The SDK may sit next to the workspace or next to the worktree; look in both.
  SDK_CANDIDATE=""
  for candidate in "${ANDROID_SDK_ROOT:-}" "$ROOT/../android-sdk" "$ROOT/../../android-sdk" \
                   "/opt/homebrew/share/android-commandlinetools"; do
    if [ -n "$candidate" ] && [ -d "$candidate/platforms" ]; then
      SDK_CANDIDATE="$candidate"
      break
    fi
  done
  if [ -n "$SDK_CANDIDATE" ]; then
    echo "  INFO android sdk: $SDK_CANDIDATE"
  fi
  check "the android sdk is available" "[ -n '$SDK_CANDIDATE' ]"
  check "the android sdk has platform 36" \
    "[ -n '$SDK_CANDIDATE' ] && test -e '$SDK_CANDIDATE/platforms/android-36'"
  check "the state file is present" "test -f docs/AGENT_STATE.md"
  check "the machine state file is present" "test -f .agent/state.json"
  check "no keystore is tracked by git" \
    "! git ls-files | grep -E '\.(keystore|jks)$'"
  check "the debug keystore is not tracked" \
    "! git ls-files | grep -q 'keystore/debug.keystore'"
  check "the agent state is not tracked" "! git ls-files | grep -q '^.agent/'"

  echo ""
  if [ "$fail" -eq 0 ]; then
    echo "RESULT: PASS - a new agent can continue from this tree."
    return 0
  fi
  echo "RESULT: FAIL - $fail check(s) did not pass. See docs/AGENT_STATE.md."
  return 1
}

case "${1:-status}" in
  status) print_status ;;
  next) print_next ;;
  verify) run_verify ;;
  resume-check) resume_check ;;
  *)
    echo "usage: $0 {status|next|verify|resume-check}"
    exit 2
    ;;
esac
