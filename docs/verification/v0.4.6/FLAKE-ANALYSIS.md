# T12-EXCEPTION — BurstCapture / SystemE2E flake classification

## Evidence (fresh, this round)
| Run | Result |
|---|---|
| Full suite run A | 238 run, 1 failure (`SystemNotificationE2E.systemPosted…`) |
| Full suite run B | 238 run, 2 failures (`BurstCapture.testC` SQLITE_BUSY, `testE` duplicate count) |
| Full suite run C | 238 run, 2 failures |
| `BurstCapture` alone (full class) | 10/10 OK (repeated) |
| `testC` alone | OK |
| `testJ` alone | 3/3 OK |
| `SystemNotificationE2E` alone | 1/1 OK |
| Combined BurstCapture + SystemE2E | 11/11 OK |

## Classification: FLAKY_UNRESOLVED (test isolation under full-suite ordering)
NOT a product defect: every failing test passes in isolation and in paired runs;
the failures only appear inside the 238-test ordered run, and the failure
signatures are infrastructure (SQLITE_BUSY on `executeForLong`, leftover-row
counts), never a wrong commit/dedup/identity result.

## Suspected mechanism (not proven)
The full suite leaves several connection pools open across classes (the bound
listener's `canonicalStore`, MainActivity's pooled helper from the UI tests,
plus the per-class helpers). During the heaviest burst tests (500 writes), the
tray/teardown interactions and `clearDatabase` retries contend with those pools.
The one-shot `onOpen` repair is NOT the cause (it runs once per process and the
flake predates/persists independent of it).

## What was ruled out
- Stale test APK (both APKs reinstalled before each diagnosis round).
- onOpen repair contention (one-shot + read-only probe now).
- Missing tray cleanup (tearDown cancelAll present since G1.5).

## Status
FLAKY_UNRESOLVED — recorded, not hidden. Repair attempts made: tray cleanup,
clear() journal wipe + busy retry, onOpen one-shot. Next concrete step: run
BurstCapture in an isolated instrumentation process (orchestrator `-e class`
already isolates; the remaining path is AndroidX Test Orchestrator with
`clearPackageData`) or move the burst suite to its own process via
`android:process` in the test manifest — both are test-infra changes, deferred
to the next round.


## Update — RC hardening round (H02)

### Confirmed root causes fixed this round
1. **`BurstCapture.testJ` false positive**: counted the WHOLE table, so leftovers
   from the previous test's settle window changed the count (3 vs 5). Fixed:
   the assertion now counts only this test's 3 child bodies. PASS in pair runs.
2. **SystemNotificationE2E duplicate marker rows**: the self-test notification
   stayed in the tray; the listener's catch-up re-captured it after
   `clearDatabase` as a NEW row (fingerprint UNIQUE constraint was emptied).
   Fixed: `setUp` cancels the app's notifications BEFORE clearing, and markers
   are now UNIQUE per run (`sys-e2e-<timestamp>`).
3. **`SQLITE_BUSY` on the OPEN path** (`BurstCapture.testC`): every pooled
   connection reads `user_version` at open, which hit SQLITE_BUSY while a long
   burst transaction held the write lock. Fixed: `PRAGMA busy_timeout=5000` in
   `onConfigure` (the same contract the clear()/retry paths already had).
   Pair runs BurstCapture+SystemE2E: 3/3 PASS after the fix (was failing 2/3).
4. **`bodyOf()` decrypted with the wrong key**: it used the instrumentation
   package instead of the row's own `package_name`. Fixed.

### Still failing under full-suite ordering — FLAKY_UNRESOLVED
`SystemNotificationE2E` fails intermittently in the FULL 239-test run only:
the marker row commits but no canonical link appears within 40 s (two-stage
deadline). Verified NOT reproducible by:
- any single neighbor suite (UiRefresh, SelfTest, Storage, PerfBaseline,
  KeyState/KeyRecovery, WAL, ConcurrentWriter pairs — all PASS),
- pair runs with BurstCapture (PASS),
- diagnostics capture (`LINK_FAILED`/`no such table` never logged).

Two materially different diagnosis attempts made; per the two-attempt rule this
is recorded as unresolved rather than guessed at. Suspected remaining mechanism:
app-process global state after 200+ prior tests (the bound listener's
canonicalStore plus the app's SharedPreferences mutated by every prior suite),
which no pair repro touches.

### Final fresh numbers
- Full suite (post-fixes): 239 run / 1 flaky / 0 product failures.
- Isolated: SystemNotificationE2E 3/3 PASS, Qa01ReadModelAndUiTest 8/8 PASS,
  BurstCapture 10/10, pair runs 11/11.
