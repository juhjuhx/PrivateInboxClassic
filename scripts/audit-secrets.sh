#!/usr/bin/env bash
# Audit the repository for secrets and private material.
#
# Checks BOTH the working tree AND the full Git history, because a secret that
# was committed and later removed is still in the history.
#
# Exit code 0 = clean, 1 = findings.
set -uo pipefail
cd "$(dirname "$0")/.."

fail=0
note() { printf '%s\n' "$*"; }
bad()  { printf 'FAIL: %s\n' "$*"; fail=1; }

note "=== 1. Tracked files that must never exist ==="
# Each pattern is an extended regex matched against the tracked file list.
for pattern in '\.keystore$' '\.jks$' '\.p12$' '\.pem$' '\.key$' 'signing\.properties$' \
               'local\.properties$' '^\.env' '\.db$' '\.sqlite3?$' \
               'google-services\.json$' 'play-service-account.*\.json$'; do
  hits=$(git ls-files | grep -E "$pattern" || true)
  # The example file is explicitly allowed; it holds no key material.
  hits=$(printf '%s' "$hits" | grep -v 'signing\.properties\.example' || true)
  if [ -n "$hits" ]; then
    bad "tracked file(s) matching '$pattern':"
    printf '%s\n' "$hits" | sed 's/^/    /'
  fi
done
[ "$fail" -eq 0 ] && note "  none"

note ""
note "=== 2. Full Git history: file names that look like secrets ==="
hist=$(git log --all --pretty=format: --name-only --diff-filter=A | sort -u | grep -v '^$')
for pattern in '\.keystore$' '\.jks$' '\.p12$' '\.pem$' 'signing\.properties$' 'local\.properties$' '^\.env$'; do
  found=$(printf '%s' "$hist" | grep -E "$pattern" || true)
  if [ -n "$found" ]; then
    bad "history contains file(s) matching '$pattern':"
    printf '%s\n' "$found" | sed 's/^/    /'
  fi
done
[ "$fail" -eq 0 ] && note "  none"

note ""
note "=== 3. History content scan for secret-looking assignments ==="
# Look for common secret shapes in every reachable commit, not just HEAD.
# Well-known Android debug keystore defaults (androiddebugkey / android) are
# documented by Google in AOSP and are not real secrets.
scan=$(git log --all -p --no-color 2>/dev/null | grep -nE \
  '^\+.*(storePassword|keyPassword|api[_-]?key|secret|token|password)[[:space:]]*=[[:space:]]*["'"'"'][^"'"'"']{6,}' \
  | grep -vE 'storePassword="android"|keyPassword="android"|keyAlias="androiddebugkey"|storePassword="\${DEBUG_STORE_PASSWORD}"|keyPassword="\${DEBUG_KEY_PASSWORD}"' \
  | grep -vE 'signing\.properties\.example|README|SIGNING\.md|\.md:' || true)
if [ -n "$scan" ]; then
  bad "history contains secret-looking assignments:"
  printf '%s\n' "$scan" | head -20 | sed 's/^/    /'
else
  note "  none"
fi

note ""
note "=== 4. Working tree: untracked secret-shaped files ==="
untracked=$(git status --porcelain --untracked-files=all | awk '$1=="??"{print $2}' \
            | grep -E '\.(keystore|jks|p12|pem|key|db|sqlite|sqlite3)$' || true)
if [ -n "$untracked" ]; then
  bad "untracked secret-shaped files present (these are ignored by git but present on disk):"
  printf '%s\n' "$untracked" | sed 's/^/    /'
else
  note "  none"
fi

note ""
note "=== 5. .gitignore covers the signing material ==="
for entry in 'keystore/' '*.keystore' '*.jks' 'local.properties'; do
  if grep -qF -- "$entry" .gitignore 2>/dev/null; then
    note "  OK  .gitignore contains '$entry'"
  else
    bad ".gitignore is missing '$entry'"
  fi
done

note ""
if [ "$fail" -eq 0 ]; then
  note "RESULT: PASS — no secrets or key material found in the tree or the history."
  exit 0
else
  note "RESULT: FAIL — see the findings above."
  exit 1
fi
