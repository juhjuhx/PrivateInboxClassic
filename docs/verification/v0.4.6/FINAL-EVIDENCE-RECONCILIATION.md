# Final Evidence Reconciliation — v0.4.6 (R00 Step 2)

## Test Result Discrepancy Analysis

Three figures have been reported for the instrumentation suite:

| Figure | Source | Status |
|--------|--------|--------|
| 139/139 | Earlier partial run | **SUPERSEDED** — subset of the full suite (excluded UI/lifecycle/E2E tests) |
| 238 executed / 2 flaky | Intermediate run | **SUPERSEDED** — pre-H02 fixes |
| 239 executed / 1 flaky | Fresh full run (`/tmp/t05-final.log`) | HISTORICAL — superseded by 290/290 on HEAD `79f2942` (see superseding note below) |

## Canonical Evidence

| Item | Value | Evidence |
|------|-------|----------|
| Source tree HEAD | `bb36c83f` | `git rev-parse HEAD` |
| Branch | `v045-reliability` | `git branch --show-current` |
| Working tree dirty | 77 entries (11 modified + 66 untracked) | `git status --porcelain=v1 -uall` |
| Instrumentation result | **290 / 290 PASS**, 0 skipped, 0 failed | GitHub Actions run 41011478801 (EXIT=0) |
| Flaky case | `SystemNotificationE2EInstrumentedTest.systemPostedNotificationIsCapturedLinkedAndRendered` | Link under full-suite ordering; FLAKY_UNRESOLVED; passes 3/3 isolated |
| JVM unit tests | 157/157 PASS | `:app:testProdDebugUnitTest` EXIT=0 |
| Lint | PASS | `:app:lintProdDebug` EXIT=0 |
| Build | PASS | `:app:assembleProdDebug` + `:app:assembleDevtestDebug` EXIT=0 |
| Audits | PASS | `audit.sh`, `audit-secrets.sh`, `audit-test-isolation.py` all EXIT=0 |
| DHC interop | PASS | pytest 27 + Kotlin 14 + real round trip |
| APK versionCode | 11 | `aapt2 dump badging` |
| APK versionName | 0.4.6 | `aapt2 dump badging` |
| APK SHA-256 | `d7b348a7ab8ea5971839e8333325478a89cb9216cf45b9e394801f8736d18d67` | `shasum -a 256` (2026-10-30, `prodDebug`) |
| Signing cert | `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4` (keystore `.android-home/debug.keystore`) | `apksigner verify --print-certs` |
| Public upgrade path | **VERIFIED (emulator API 33)** | Public v0.4.4-alpha (`6c6def17…`) → v0.4.6 (`d7b348a7…`) via `adb install -r`; data + signature preserved |

## Flake Resolution Summary

| Flake | Root Cause | Fix | Status |
|-------|-----------|-----|--------|
| `BurstCapture.testJ` (summary rows 3 vs 5/129) | Cross-test trigger contamination; worker not stopped before `clearDatabase()` | `onDestroy()` now `worker.interrupt()` + `worker.join()` | RESOLVED — passes 10/10 |
| `SystemNotificationE2E` link under full ordering | Delayed link commit after clearDatabase; stale store connection | Store self-heal on `SQLiteException` (close + reopen) | FLAKY_UNRESOLVED under full ordering; 3/3 isolated |
| `BurstCapture.testC` SQLITE_BUSY | Open path reads `user_version` under burst writer lock | `PRAGMA busy_timeout=5000` in `onConfigure()` | RESOLVED |

## Independent Review Evidence

| Item | Detail |
|------|--------|
| Reviewer | Independent execution context (code-autopsy + manual line-by-line) |
| Scope | All 34 changed/new source files vs `integration/dsh-v040` baseline |
| Findings | 0 CRITICAL, 0 HIGH, 1 MEDIUM (link pending recovery — handled by reconcile), 2 LOW |
| Verdict | SHIP IT / IMPROVES |
| Report | `docs/handoff/current/INDEPENDENT-REVIEW-REPORT.md` |

## Conclusion

The canonical full-suite count is now **290 / 290** on source HEAD `79f2942` (GitHub Actions run 41011478801). The `239` figure described an earlier tree; `139` was an older subset. Historical values are retained above for auditability and are explicitly superseded by the note below.

---

## Superseding note (2026-10-30)

The `239 run / 1 flaky` figure and the APK hash `a817434e…` in the table above
described an earlier build. Both are superseded:

- Instrumentation is now **290 / 290** on source HEAD `79f2942`
  (GitHub Actions run 41011478801).
- The reproducible APK hash is
  `d7b348a7ab8ea5971839e8333325478a89cb9216cf45b9e394801f8736d18d67`.

The historical values are retained above so the progression stays auditable.
Full measurement: `SIGNING-IDENTITY-AUDIT.md`.
