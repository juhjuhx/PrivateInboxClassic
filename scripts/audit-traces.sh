#!/usr/bin/env bash
# Publication trace audit.
#
# Before a repository is made public, its files AND its history must be free of
# the machine it was built on: absolute home paths, local host names, and
# author addresses that are not deliberately public.
#
# This is separate from audit-secrets.sh. That one looks for credentials; this
# one looks for the author's local environment. It reads binary files too,
# because a Python .pyc embeds __file__ as an absolute path and `git grep`
# skips binaries by default.
set -uo pipefail
cd "$(dirname "$0")/.."

FAIL=0
note() { printf '%s\n' "$*"; }

# A pattern may match a deliberate, documented exception. Keep this list short
# and justify every entry.
is_allowed() {
  case "$1" in
    docs/OPEN-SOURCE-PROVENANCE.md) return 0 ;;  # upstream URLs may contain /home/ paths
    scripts/audit-traces.sh) return 0 ;;          # this script names the patterns
    *) return 1 ;;
  esac
}

PATTERNS='/Users/[A-Za-z0-9._-]+|/home/[a-z][A-Za-z0-9._-]+|C:\\Users\\|MacBook|MacBook-Air'

note "== 1. tracked files (binary included) =="
while IFS= read -r path; do
  [ -z "$path" ] && continue
  if grep -aqE "$PATTERNS" "$path" 2>/dev/null; then
    if ! is_allowed "$path"; then
      note "TRACE  file: $path"
      FAIL=1
    fi
  fi
done < <(git ls-files)

note "== 2. every blob reachable from every ref (binary included) =="
while read -r sha path; do
  [ -z "$sha" ] && continue
  type=$(git cat-file -t "$sha" 2>/dev/null) || continue
  [ "$type" = blob ] || continue
  if git cat-file blob "$sha" 2>/dev/null | grep -aqE "$PATTERNS"; then
    if ! is_allowed "${path:-<unnamed blob>}"; then
      note "TRACE  blob ${sha:0:12} (${path:-<unnamed blob>})"
      FAIL=1
    fi
  fi
done < <(git rev-list --objects --all)

note "== 3. author and committer identities =="
bad_ident=$(git log --all --format='%an <%ae>|%cn <%ce>' \
  | tr '|' '\n' | sort -u \
  | grep -vE '<[^>]+@users\.noreply\.github\.com>$' \
  | grep -E 'MacBook|@local>|@stash>|@[A-Za-z0-9.-]*\.local>' || true)
if [ -n "$bad_ident" ]; then
  note "$bad_ident" | sed 's/^/TRACE  identity: /'
  FAIL=1
fi

note ""
if [ "$FAIL" -eq 0 ]; then
  note "RESULT: PASS — no local-environment traces in the files, the history or the identities."
else
  note "RESULT: FAIL — the lines marked TRACE must be removed or explicitly allowlisted."
fi
exit "$FAIL"
