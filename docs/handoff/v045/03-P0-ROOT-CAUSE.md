# v0.4.5 P0 Root Cause (Failure Convergence) — CONFIRMED & FIXED

## Evidence Base
- JUnit XML (before): `app/build/outputs/androidTest-results/connected/debug/flavors/devtest/TEST-emulator-5554 - 16-_app-devtest.xml`
  — 139 tests / 54 failures / 0 errors / 0 skipped (API 36 arm64 emulator)
- SQLite semantics probes: platform-tools sqlite3 against real file DBs (documented below)
- On-device probe tests (since deleted after root cause): RetryDiagProbeTest
- Fresh runs after fix: see 04-TEST-EVIDENCE.md

## ROOT CAUSE 1 (dominant — caused ~50 of 54 failures): Cross-class trigger contamination
`BatchRetryFaultInjectionInstrumentedTest` had **no @After**. Its always-fail
tests installed `CREATE TRIGGER fault_trigger BEFORE INSERT ON messages ...
RAISE(ABORT)`. `TestDataIsolation.clearDatabase()` → `InboxDatabase.clear()`
only does `db.delete("messages", null, null)` — DELETE is not an INSERT, so
the trigger survived every clear. JUnit runs classes alphabetically
(BatchRetry → BurstCapture → ConcurrentWriter → DbContract → …), so every
later class that INSERTs hit RAISE(ABORT) on every row: 0 rows everywhere,
SaveResult.FAILURE everywhere ("expected NEW but was FAILURE", "no write may
fail but was:<200/300>", "seed must have stored 100 rows (got 0)").
Classes that do not insert into `messages` all passed, which matched the
observed pass/fail matrix exactly.

**FIX (test)**: `@After tearDown()` drops the trigger + control table on every
test end (runs on the failure path too); setUp keeps a defensive drop.

## ROOT CAUSE 2 (test design flaw): self-decrementing "fail-once" trigger can never count down
The original trigger decremented `fault_control.remaining` inside its own body
and then raised ABORT. Verified with real sqlite3: RAISE(ABORT) rolls back the
statement INCLUDING the trigger's own UPDATE, so `remaining` never decremented
— "fail once" was actually "fail forever". A pure-trigger failure countdown is
impossible because every side effect of the aborted statement is rolled back.

**FIX (test)**: guard-only trigger (no side effects) + `disarmFault()` executed
by the test as its own statement (`UPDATE fault_control SET remaining=0`),
which the abort cannot roll back. Verified in sqlite3 and on-device:
insert fails (exit 19), disarm, insert succeeds.

## ROOT CAUSE 3 (product): immediate recursive re-flush burned the retry budget in milliseconds
`flushPrepared()` requeued failed batches at the FRONT of PREPARED and called
itself with NO delay. Probe evidence (logcat diagnostics):
`codes=NOTIFICATION_RECEIVED, PARSE_SUCCESS, DB_INSERT_RETRY_SCHEDULED,
DB_INSERT_RETRY_SCHEDULED, DB_INSERT_TERMINAL` — all 3 attempts consumed
within ~2 s of the fault appearing; when the test disarmed afterwards, the
batch was already TERMINAL and dropped. In production a transient fault
(SQLite busy, brief disk stall) outlives the whole retry budget the same way.

**FIX (product, PrivateNotificationListener.kt)**: bounded backoff
`RETRY_BACKOFF_MS = 1000` sleeps on the SAME worker thread before each
scheduled re-flush (both the toRetry path and the Throwable path). No new
thread, no scheduler, recursion still bounded by MAX_FLUSH_ATTEMPTS, retries
still complete with no new notifications.

**BUILD GOTCHA discovered during verification**: the backoff initially appeared
ineffective because `assembleDevtestDebugAndroidTest` rebuilt ONLY the test
APK; the installed app APK was stale (00:13 vs 14:02). Rebuilding and
reinstalling BOTH APKs made the fix observable. Verification runs must always
reinstall both APKs after any main-source change.

## ROOT CAUSE 4 (test design flaw): dedup test created NEW messages, then expected DUPLICATE
`multiMessageBatch_accountingIsExactPerMessage` re-captured a freshly built
notification whose MessagingStyle messages carry new
`System.currentTimeMillis()` timestamps. The dedup discriminator embeds the
message timestamp, so the "duplicate" rows were genuinely NEW messages — the
product was right, the test was wrong. Separately, re-capturing the SAME sbn
through the SAME listener instance is filtered at the SOURCE (per-notification
memory) and never reaches the database, so no DUPLICATE can be counted either.

**FIX (test)**: re-capture the SAME sbn through a FRESH listener instance
(process-restart semantics): no source memory, rows reach the database,
fingerprint dedup reports DUPLICATE.

## Transaction semantics established (probed, real SQLite)
- RAISE(ABORT) from a trigger fires even under INSERT OR IGNORE (Android
  CONFLICT_IGNORE does not suppress it).
- ABORT + external disarm across statements verified: fail → disarm → insert
  succeeds, no leftover state.
- `save()` catches the per-row exception and returns FAILURE; the outer
  `saveBatches()` transaction is not aborted by the exception itself.
- `clear()` (DELETE) is unaffected by INSERT triggers — why contamination
  survived every setUp.

## Final State
- BatchRetryFaultInjectionInstrumentedTest: 7/7 PASS (was 3/7)
- BatchRetry + BurstCapture sequence: 17/17 PASS (contamination check A→B)
- NotificationPipelineInstrumentedTest: 10/10 PASS (was 4/10)
- WalBenchmark + DbContract + ConcurrentWriter: 10/10 PASS (was 4/10)
- Full suite: see 04-TEST-EVIDENCE.md
