# CHECKPOINT-R2 — Failure Convergence Complete

PHASE: STAGE 1–6 complete (Root Cause → Fix → Fresh Verification)
STATUS: GREEN

REPO_HEAD: bb36c83f524c0aa87ba04680f0f4c0bfea79318f
BRANCH: v045-reliability

DIRTY_FILES:
  M app/src/main/java/tw/personal/privateinbox/PrivateNotificationListener.kt  (retry backoff + removal-flag fix)
  M app/src/main/java/tw/personal/privateinbox/InboxDatabase.kt                 (clear() SQLITE_BUSY retry)
  M app/src/main/java/tw/personal/privateinbox/CaptureDiagnostics.kt            (2 retry diagnostic codes, from prior round)
  M app/src/androidTest/java/tw/personal/privateinbox/BurstCaptureInstrumentedTest.kt (tray cleanup + import)
  ?? app/src/androidTest/java/tw/personal/privateinbox/BatchRetryFaultInjectionInstrumentedTest.kt (8→7 tests, guard-only trigger, @After hygiene)
  ?? CHECKPOINT-0.md, CHECKPOINT-R1.md, CHECKPOINT-R2.md
  ?? docs/handoff/v045/ (9 docs)

LAST_SUCCESSFUL_COMMAND: adb shell am instrument -w tw.personal.privateinbox.devtest.test/... → OK (139 tests)
LAST_EXIT_CODE: 0

EVIDENCE_PATH: docs/handoff/v045/03-P0-ROOT-CAUSE.md, docs/handoff/v045/04-TEST-EVIDENCE.md, /tmp/devtest-full-run.log

ROOT_CAUSES (all confirmed with probes, see 03 doc):
  1. Fault trigger cross-class contamination (no @After in BatchRetry tests) → ~50 failures
  2. Self-decrementing fail-once trigger impossible (RAISE(ABORT) rolls back its own UPDATE)
  3. PRODUCT: immediate recursive re-flush burned MAX_FLUSH_ATTEMPTS in ms → RETRY_BACKOFF_MS=1000 added
  4. TEST: dedup test built NEW messages then expected DUPLICATE → fresh-listener re-capture semantics
  5. PRODUCT: onNotificationRemoved() wrongly cleared the listener-connected flag → fixed
  6. PRODUCT: clear() died on SQLITE_BUSY when racing the worker → bounded retry added
  7. TEST: burst notifications left in tray were recaptured on next listener bind → cancelAll() in tearDown

VERIFICATION: 139/139 instrumentation PASS; unit/lint/build/audit/secrets/isolation all exit 0.

BLOCKERS: none for P0 reliability; apksigner release-gate check NOT RUN (SIGNING_NOT_VERIFIED).

NEXT_ATOMIC_ACTION: independent code review of the 4 changed source files (STAGE 8), then release-gate signing check.
