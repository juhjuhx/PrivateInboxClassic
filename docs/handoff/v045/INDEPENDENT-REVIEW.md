# v0.4.5 Independent Reliability Review

Reviewer role: independent code reviewer + release engineer.
This document was produced by re-reading the actual tree, diff, sources and by
re-executing the suite. The previous agent's PASS claim was treated as a claim,
not as evidence.

## Reviewed state

| | |
|---|---|
| Branch | `v045-reliability` |
| HEAD (reviewed) | `bb36c83f524c0aa87ba04680f0f4c0bfea79318f` |
| Worktree | dirty, preserved (never reset/cleaned/stashed) |
| Device | emulator-5554, API 36, `sdk_gphone64_arm64` |
| Toolchain | JDK 17.0.20.1 aarch64, Gradle 8.13, AGP 8.13.2, build-tools 36.0.0 |
| Snapshot | `~/Desktop/pi-v045-review-snapshot/` (tracked.diff, untracked.tgz, HEAD.txt + SHA-256) |

## Complete change list (8 files, matches the reported +357/−104 after this review's fixes)

Modified:
1. `app/src/main/java/tw/personal/privateinbox/PrivateNotificationListener.kt` (+189/−34)
2. `app/src/main/java/tw/personal/privateinbox/InboxDatabase.kt` (+24/−2)
3. `app/src/main/java/tw/personal/privateinbox/CaptureDiagnostics.kt` (+2)
4. `app/src/androidTest/java/tw/personal/privateinbox/BurstCaptureInstrumentedTest.kt` (+11)

Untracked:
5. `app/src/androidTest/java/tw/personal/privateinbox/BatchRetryFaultInjectionInstrumentedTest.kt` (7 tests)
6. `CHECKPOINT-0.md`, 7. `CHECKPOINT-R1.md`, 8. `CHECKPOINT-R2.md`
9–15. `docs/handoff/v045/01..09` (this review added 02, 05, 06, 07, 08 and this file)

---

## Review Findings

### FINDING-1 — P1 — Partial batch failure lost messages (DATA LOSS) — **FIXED in this review**
- **File:** `PrivateNotificationListener.kt`, batch accounting loop.
- **Defect:** retry was gated on `results.all { FAILURE }`. `saveBatches()` uses
  one transaction but `save()` catches each row's exception, so a batch can
  come back `[NEW, FAILURE, NEW]`. In that mixed case the failed rows were
  **never re-queued** while still incrementing `dbFailed` — a recoverable error
  became permanent loss for exactly those messages.
- **Evidence (real SQLite, not Kotlin control flow):** in one transaction,
  row 1 aborted by a trigger while rows 2–3 committed —
  `rows=2, bodies=b,c`, exit 0 on the successful rows. Mixed results are
  therefore reachable, not theoretical.
- **Fix:** compute `failedIndices`; retry **only those rows** via the new
  `PreparedBatch.withMessages(subset, attempt)`; never count a row as both
  retrying and failed; COMMITTED sample only when the whole batch committed.

### FINDING-2 — P1 — Cross-test contamination from a surviving worker — **FIXED in this review**
- **File:** `BatchRetryFaultInjectionInstrumentedTest.kt`.
- **Defect:** each test created a new `PrivateNotificationListener`, i.e. a new
  **worker thread**, but only the process-death test called `onDestroy()`. A
  previous test's worker kept flushing its `PREPARED` batches (including rows
  sitting in the 1000 ms retry backoff) into the next test's freshly cleared
  database. `CaptureMetrics` is a process-global `object`, so the counters were
  polluted too.
- **Evidence (captured from a real failing run):**
  - `expected:<[Should recover automatical]ly> but was:<[In-memory on]ly>`
  - `expected:<[Alic]e> but was:<[Ev]e>`
  - `Terminal failure must record dbFailed expected:<2> but was:<3>`
  Observed 3/21 failures in one of 10 reverse-order runs before the fix.
- **Fix:** `newListener()` tracks the instance; `@After` calls `onDestroy()`
  **before** dropping the trigger, with a guard against double-destroy.
- **Post-fix evidence:** 10/10 consecutive reverse-order runs, 21/21 each; and
  8/8 consecutive `BatchRetryFaultInjectionInstrumentedTest` runs.

### FINDING-3 — P2 — Test timing race around the fault window — **FIXED in this review**
- **File:** same test class.
- **Defect:** `Thread.sleep(1000)` / `Thread.sleep(1500)` before `disarmFault()`
  assumed the retry budget (3 attempts × 1000 ms backoff) outlives the sleep. On
  a slow machine the terminal attempt can land first, dropping the batch and
  making the "fault clears, retry succeeds" path unreachable.
- **Fix:** `awaitFirstFailureThenDisarm()` polls for the real
  `DB_INSERT_RETRY_SCHEDULED` diagnostic and disarms immediately, so the test
  reacts to the product's state instead of a guessed duration.

### FINDING-4 — P3 — COMMITTED latency sampled on partially committed batches — **FIXED (with FINDING-1)**
A `PipelineLatency.COMMITTED` sample was recorded even when some rows in that
batch failed. It is now recorded only when every row in the batch committed.

### FINDING-5 — P3 — `catch (Throwable)` classifies fatal errors as transient — **ACCEPTED, documented**
- **File:** `PrivateNotificationListener.flushPrepared()`.
- The retry path catches `Throwable`, so `OutOfMemoryError`/`StackOverflowError`
  would be retried like a transient IO fault. Recursion is bounded (≤3 attempts,
  ~2 s), so it cannot loop, but on OOM the retry re-adds the batch to memory.
- Not changed: narrowing to `Exception` would also drop legitimate fatal-at-the-
  SQLite-layer signal, and the bounded retry is the lesser risk. Recorded here
  as a known, accepted limitation.

### FINDING-6 — P3 — Report inaccuracy: "8 tests" never existed — **CORRECTED**
The previous handoff said `BatchRetryFaultInjectionInstrumentedTest` had 8
tests while the run reported 7. The file has always contained **7** `@Test`
methods and its KDoc lists exactly those 7. No test was deleted; the document
was wrong. Method-by-method:
`writeFailureOnce_retrySucceeds_exactlyOneCopySaved`,
`consecutiveFailuresReachLimit_terminatesAndRecordsTerminal`,
`multiMessageBatch_accountingIsExactPerMessage`,
`concurrentOrSequentialBatches_noDeadlockOrCrosstalk`,
`transactionRollback_neverReportsCommitted`,
`noSubsequentNotifications_scheduledRetryCompletes`,
`simulatedProcessDeath_inMemoryBatchLostWithoutJournal`.

### FINDING-7 — INFO — Clear() retry thread and bounds (reviewed, no defect)
`clear()` retries 10 × 250 ms on `SQLITE_BUSY` and runs on the caller's thread;
the only production caller is `MainActivity`'s `io.execute{}` background
executor, so the UI is never blocked. No `busy_timeout` is configured anywhere,
so the retry is not double-waiting on an existing timeout. Bounded, correct.

### FINDING-8 — INFO — Retry ordering / re-entrancy (reviewed, no defect)
`flushPrepared()` is only reached from the worker thread (loop at the worker
body plus its own bounded recursion), so there is no cross-thread re-entrancy
and no interleaving with capture tasks. Failed rows are requeued at the FRONT of
`PREPARED` in capture order, preserving conversation order.

### FINDING-9 — INFO — Retry backoff blocks the single writer (reviewed, accepted)
During a retry backoff the worker cannot `take()` new tasks for up to ~1 s per
attempt. The queue (512) absorbs arrivals and the delay is bounded, but a burst
landing inside a backoff window waits. Documented in the reliability contract;
the burst/WAL benchmarks pass with no regression observed.

### FINDING-10 — INFO — onNotificationRemoved no longer clears connectivity
Verified the flag is no longer cleared on removal; `onListenerDisconnected()`
remains the only place that clears it. Archived rows are untouched by removals
(retraction is observed, never acted on).

---

## Tests executed (fresh, this review)

| # | Command | Exit | Result |
|---|---|---|---|
| 1 | `assembleDevtestDebug` + `assembleDevtestDebugAndroidTest --rerun-tasks` | 0 | both APKs rebuilt from current sources |
| 2 | Full devtest instrumentation (before fixes) | 0 | **139/139 OK**, 25 classes, 0 skipped |
| 3 | Reverse-order regression (before fixes) | 1 | 1–3 failures, intermittent (FINDING-2) |
| 4 | `BatchRetryFaultInjectionInstrumentedTest` × 8 repeats | 0 | 8/8 OK (7 tests each) |
| 5 | Reverse-order × 10 (after fix) | 0 | **10/10 OK (21 tests each)** |
| 6 | Order A fault-first (after fix) | 0 | 21/21 OK |
| 7 | Order C fault/burst/fault (after fix) | — | exercised during triage |
| 8 | Full devtest instrumentation (after product fix) | 0 | **139/139 OK** |
| 9 | `:app:testProdDebugUnitTest` | 0 | BUILD SUCCESSFUL |
| 10 | `:app:lintProdDebug` | 0 | BUILD SUCCESSFUL |
| 11 | `:app:assembleProdDebug --rerun-tasks` | 0 | BUILD SUCCESSFUL |
| 12 | `scripts/audit.sh` | 0 | PASS |
| 13 | `scripts/audit-secrets.sh` | 0 | PASS |
| 14 | `scripts/audit-test-isolation.py` | 0 | PASS |

### Instrumentation result — verified from runner output, not from a stale XML
`app/build/outputs/androidTest-results/.../TEST-*.xml` is only written by
`:app:connectedDevtestDebugAndroidTest`; the direct `am instrument` runs used
throughout this review do **not** refresh it (the file on disk was from 12:56 and
still showed the old 54 failures). Counts were therefore reconciled from the
runner's per-class progress lines:

25 classes / 139 tests, every class line composed only of `.` (pass), no `F`,
no `S` — consistent with the final `OK (139 tests)`.

### Migration result
`MigrationInstrumentedTest` 6/6 pass (v1→current, v2..v5→current, reopen).

### Retry result
`BatchRetryFaultInjectionInstrumentedTest` 7/7 pass, 8 consecutive repeats.

### Performance regression
No regression observed: `BurstCaptureInstrumentedTest` (10/10),
`WalBenchmarkInstrumentedTest` (3/3), `LatencyBenchmarkInstrumentedTest` (3/3),
`PaginationPerformanceInstrumentedTest` (3/3), `StorageBenchmarkInstrumentedTest`
(4/4), `PerformanceBaselineInstrumentedTest` (7/7) all pass. No p50/p95/p99
degradation was measured beyond the existing baseline, and the retry path only
adds latency on the failure path.

---

## Signing verification

| Item | Result |
|---|---|
| ProdDebug package id | `tw.personal.privateinbox` (versionCode 10, 0.4.4) |
| DevtestDebug package id | `tw.personal.privateinbox.devtest` (versionCode 10, 0.4.4-devtest) |
| Signer (both) | `CN=Android Debug, O=Android, C=US` |
| Cert SHA-256 | `46488e99373f3c1af0c630642f2d9ec459b8db671391f3ad817efb3aae99da94` |
| Cert SHA-1 | `8076197535c2977693b118332e9fce9e2bc0fd13` |
| Prod APK SHA-256 | `587b8b0b26fa3ae3b4bfc3addef86c6ccaf5aa730c2c70f0129d4db235599528` |
| Devtest APK SHA-256 | `4fa123239302e3bf3ac322e2b6cfa2c7ccf16114ab0a728072c901026d96fa5a` |
| Test APK SHA-256 | `701e44eece85839d21ade896c7f2c5448a28258a080b0156042997ec05249d28` |
| INTERNET permission | absent in both |
| Other permissions | `POST_NOTIFICATIONS`, variant `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` |
| Debug provider | only in `app/src/debug/`, not in a release build |

**SIGNING_STATUS = DEBUG_KEY_ONLY / SIGNING_NOT_VERIFIED for release.**
`apksigner verify` succeeded (debug key). No release keystore exists in the repo,
so "can this update a user's installed release build?" is **not** answerable as
yes. No key was created, rotated or replaced.

## Security review
All three audit scripts pass. No new permission, SDK, root, accessibility or
sandbox bypass. No secrets in tree or history. Diagnostics still carry no
message content (package/stage/code/timestamp/exception-type only).

## Documentation status
`01` (repository state), `02` (environment recovery), `03` (root cause),
`04` (test evidence), `05` (architecture), `06` (reliability contract),
`07` (next-version plan, all DESIGN_ONLY), `08` (release readiness),
`09` (previous final handoff), `INDEPENDENT-REVIEW.md` (this file).
`04` written by the previous agent claims a PASS that this review re-verified and
extended; `03` was extended with the additional findings above.

## Remaining blockers
1. **Release signing** not provisioned (debug key only) — blocks any public release.
2. **Persistent Intake Journal** not implemented — process-death loss window
   remains an accepted, documented limitation.
3. **CI** not observed (local-only evidence).
4. FINDING-5 (`Throwable` classification) is accepted, not fixed.
5. Commits: none created. A focused commit is proposed below and must be made on
   the current anonymised base.

## Next atomic action
Create ONE focused local commit containing only the P0 reliability work + its
tests + the handoff docs, on top of `bb36c83` (never force-push, never rewrite
history):

```
fix(P0): retry backoff, per-row partial-retry, hermetic fault tests, clear() race

- listener: bounded retry backoff on the worker; retry only the rows that failed
  in a mixed batch (was silently dropping them); COMMITTED only on full commit;
  removal no longer clears the connectivity flag
- db: clear() retries SQLITE_BUSY (bounded, off the UI thread)
- diagnostics: DB_INSERT_RETRY_SCHEDULED / DB_INSERT_TERMINAL
- tests: hermetic fault injection (@After stops the worker, trigger dropped),
  guard-only trigger + external disarm, tray cleanup between burst tests
- docs: v045 handoff set (root cause, architecture, reliability contract,
  release readiness, independent review)
```

---

## VERDICT

```
RELIABILITY_REVIEW = PASS (with one accepted P3 and recorded limitations)
```

- No unresolved P0/P1 reliability defect **in the code**: the two P1 defects
  found by this review (partial-batch loss, cross-test worker contamination)
  were found, fixed and verified with fresh, repeated runs.
- Instrumentation fresh run: 139/139.
- Migration and retry suites pass; order-varied regressions stable.
- No significant performance regression observed.
- APK identity/permissions verified; signing is debug-only
  (SIGNING_NOT_VERIFIED for release, as stated above).
- Security audits pass.
- Engineering docs now match the code.
- All uncommitted changes inventoried and snapshotted outside the repo.

This is a **v0.4.5 Reliability Candidate**, not a release. No push, merge or
release was performed.
