# v0.4.5 Final Handoff

## Status
READY_FOR_INDEPENDENT_REVIEW — P0 reliability fixes verified with fresh evidence.
No push / merge / release performed (per brief).

PROJECT: Private Inbox Classic
BRANCH: v045-reliability
GIT_HEAD: bb36c83f524c0aa87ba04680f0f4c0bfea79318f (all fixes uncommitted on top)

DIRTY_STATUS:
  M  PrivateNotificationListener.kt   (retry state machine + backoff + removal-flag fix)
  M  InboxDatabase.kt                 (clear() SQLITE_BUSY bounded retry)
  M  CaptureDiagnostics.kt            (DB_INSERT_RETRY_SCHEDULED / DB_INSERT_TERMINAL)
  M  BurstCaptureInstrumentedTest.kt  (tray cancelAll in tearDown, import)
  ?? BatchRetryFaultInjectionInstrumentedTest.kt (new, 7 tests)
  ?? CHECKPOINT-0/R1/R2.md, docs/handoff/v045/ (01,03,04 + this file)

## Root Causes Fixed (details in 03-P0-ROOT-CAUSE.md)
| # | Layer | Cause | Fix |
|---|-------|-------|-----|
| 1 | test | Fault trigger survived tearDown → poisoned every later INSERT class (~50 failures) | @After drop + defensive drop in setUp |
| 2 | test | Self-decrementing fail-once trigger impossible (RAISE(ABORT) rolls back own UPDATE) | guard-only trigger + external disarmFault() |
| 3 | PROD | Immediate recursive re-flush burned 3 attempts in ms; transient faults unrecoverable | RETRY_BACKOFF_MS=1000 on worker thread, both retry paths |
| 4 | test | Dedup test created NEW messages then expected DUPLICATE | re-capture same sbn via fresh listener (process-restart semantics) |
| 5 | PROD | onNotificationRemoved() cleared listener-connected flag | flag no longer touched on removal |
| 6 | PROD | clear() died on SQLITE_BUSY racing the worker | bounded 10×250ms retry in clear() |
| 7 | test | Tray backlog recaptured on next listener bind repopulated cleared DB | cancelAll() in BurstCapture tearDown |

## Verification (full commands + exit codes in 04-TEST-EVIDENCE.md)
- Full devtest instrumentation: **139/139 PASS** (`OK (139 tests)`, was 54 failures)
- Unit (prodDebug): PASS • Lint: PASS • Both APK builds: PASS
- audit.sh / audit-secrets.sh / audit-test-isolation.py: all PASS
- APK SHA-256 recorded in 04 doc; **SIGNING_NOT_VERIFIED** (apksigner not run)

## Retry / Reliability Contract (current)
- MAX_FLUSH_ATTEMPTS=3 total attempts per batch; success is the only release state
- Backoff 1000 ms on the worker thread between attempts; no new thread/scheduler; recursion bounded
- TERMINAL batches: dbFailed += size, DB_INSERT_TERMINAL diagnostic; never counted committed
- Process death between capture and commit still loses in-memory batches — Persistent Intake Journal is DESIGN_ONLY (see 07 plan)
- Accounting: submitted == committed + duplicated + terminal_failed once no batch is pending/retrying

## Not Done This Round (honest list)
- docs/handoff/v045/02, 05, 06, 07, 08, 09: not written this round (01/03/04 + R-checkpoints are current)
- apksigner release-gate verification
- Independent reviewer sign-off (this document is the handoff TO that review)

## Next Agent Commands
```
cd /Users/huang/Desktop/我的已讀不回助理/PrivateInboxClassic-v0.2/codex-integration
source scripts/_env.sh
git status --porcelain=v1 -uall        # confirm dirty set above
./gradlew --no-daemon :app:assembleDevtestDebug :app:assembleDevtestDebugAndroidTest
adb install -r app/build/outputs/apk/devtest/debug/app-devtest-debug.apk
adb install -r app/build/outputs/apk/androidTest/devtest/debug/app-devtest-debug-androidTest.apk
adb shell am instrument -w tw.personal.privateinbox.devtest.test/androidx.test.runner.AndroidJUnitRunner
```
Rule: after ANY main-source change, reinstall BOTH APKs before trusting instrumentation results.

## Commit Gate (for the next agent)
Changes are safe to commit as one focused commit after independent review, e.g.:
`fix: bounded retry backoff, trigger hygiene, removal-flag and clear() race (P0-B/C)`
