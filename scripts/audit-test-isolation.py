#!/usr/bin/env python3
"""Fail the build if an instrumentation test can destroy the user's data.

The instrumentation tests open the TARGET application's database. Historically
they called `db.clear()` directly, which meant that running the suite on a
phone with the real app installed would DELETE THE USER'S SAVED MESSAGES.

There are two protections:

  1. The destructive tests run against the `devtest` variant, which has its
     own application id and therefore its own data directory. It physically
     cannot reach the production database.

  2. `DestructiveOperationGuard` refuses a destructive operation when the
     target package is the production one, and `TestDataIsolation` is the only
     sanctioned entry point.

This script enforces the RULE at the source level: no instrumentation test may
call a destructive database method directly. It has to go through
`TestDataIsolation`, which carries the guard.

It is a build gate, not a reminder: a test that bypasses the guard fails the
build instead of quietly being dangerous.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TEST_ROOT = ROOT / "app" / "src" / "androidTest"

# The destructive operations that must not appear directly in a test.
FORBIDDEN = [
    (re.compile(r"\bdb\.clear\(\)"), "db.clear()"),
    (re.compile(r"\bdatabase\.clear\(\)"), "database.clear()"),
    (re.compile(r"\bdeleteDatabase\s*\("), "deleteDatabase()"),
    (re.compile(r"\bclearDatabase\s*\("), "clearDatabase()"),
    (re.compile(r"\bpurgeUndecryptableRows\s*\("), "purgeUndecryptableRows()"),
]

# The sanctioned entry point. ANY call through TestDataIsolation is allowed: that
# object is the isolation layer, and it carries the guard.
SANCTIONED = "TestDataIsolation."

# The files that are allowed to call the destructive methods directly,
# because they ARE the isolation layer.
ALLOWED_FILES = {
    "DestructiveOperationGuardTest.kt",
}


def main() -> int:
    findings: list[str] = []
    scanned = 0

    for path in sorted(TEST_ROOT.rglob("*.kt")):
        scanned += 1
        if path.name in ALLOWED_FILES:
            continue
        text = path.read_text(encoding="utf-8")
        for pattern, label in FORBIDDEN:
            for match in pattern.finditer(text):
                line_no = text.count("\n", 0, match.start()) + 1
                line = text.splitlines()[line_no - 1].strip()
                # The sanctioned call is allowed.
                if SANCTIONED in line:
                    continue
                findings.append(
                    f"  {path.relative_to(ROOT)}:{line_no}  {label}\n"
                    f"      {line}\n"
                    f"      -> use a TestDataIsolation call instead; it refuses "
                    f"to destroy data outside an isolated package."
                )

    if findings:
        print("RESULT: FAIL — an instrumentation test can destroy the user's data.")
        print()
        for finding in findings:
            print(finding)
        print(f"{len(findings)} finding(s) in {scanned} test file(s).")
        return 1

    print(f"RESULT: PASS — {scanned} instrumentation test file(s) scanned; "
          f"no unguarded destructive database operation.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
