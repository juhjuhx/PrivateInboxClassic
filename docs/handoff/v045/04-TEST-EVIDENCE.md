# v0.4.5 Test Evidence — Fresh Verification Matrix

## Environment
- Emulator: emulator-5554, API 36 (sdk_gphone64_arm64), arm64
- JDK: 17.0.20.1 (Homebrew), Gradle 8.13, AGP 8.13.2, Kotlin 2.2.20
- HEAD at time of run: bb36c83f524c0aa87ba04680f0f4c0bfea79318f (branch v045-reliability)
- Both APKs reinstalled before every product-source change (see ROOT-CAUSE doc, build gotcha)

## Results (all commands executed 2026-10-24, exit codes recorded)

| # | Command | Exit | Result |
|---|---------|------|--------|
| 1 | `adb shell am instrument -e class BatchRetryFaultInjectionInstrumentedTest` | 0 | OK (7 tests) — was 4 failures |
| 2 | `adb shell am instrument -e class BatchRetry...,BurstCapture...` (contamination A→B) | 0 | OK (17 tests) |
| 3 | `adb shell am instrument -e class NotificationPipelineInstrumentedTest` | 0 | OK (10 tests) — was 6 failures |
| 4 | `adb shell am instrument -e class WalBenchmark...,DbContract...,ConcurrentWriter...` | 0 | OK (10 tests) — was 6 failures |
| 5 | `adb shell am instrument -e class Pagination...,KeyRecovery...,KeyState...,UiRefresh...` | 0 | OK (31 tests) |
| 6 | `adb shell am instrument -e class BurstCaptureInstrumentedTest` | 0 | OK (10 tests) — was 8-10 failures |
| 7 | **Full suite: `adb shell am instrument -w tw.personal.privateinbox.devtest.test/androidx.test.runner.AndroidJUnitRunner`** | **0** | **OK (139 tests), 0 failures, 0 skipped** |
| 8 | `./gradlew --no-daemon :app:testProdDebugUnitTest` | 0 | BUILD SUCCESSFUL |
| 9 | `./gradlew --no-daemon :app:lintProdDebug` | 0 | BUILD SUCCESSFUL |
| 10 | `./gradlew --no-daemon :app:assembleProdDebug :app:assembleDevtestDebug` | 0 | BUILD SUCCESSFUL |
| 11 | `bash scripts/audit.sh` | 0 | PASS (manifest policy + all unit suites green) |
| 12 | `bash scripts/audit-secrets.sh` | 0 | PASS — no secrets in tree or history |
| 13 | `python3 scripts/audit-test-isolation.py` | 0 | PASS — 29 instrumentation files, no unguarded destructive op |

## Baseline Comparison
- BEFORE this round: 139 executed / 54 failed / 85 passed (JUnit XML, 2026-10-24 12:56)
- AFTER this round:  **139 executed / 0 failed / 139 passed** (adb instrument -w output: `OK (139 tests)`)
- Log: /tmp/devtest-full-run.log

## APK Artifacts (SHA-256)
| Artifact | SHA-256 |
|---|---|
| app-devtest-debug.apk | 225948beeaaf89353b6759e379a64835460c2c27fa333fa3eb047751fe8786b7 |
| app-prod-debug.apk | eeffe1b22c1b2e9d80418b9c0693d72308a26e9232fcc2468c0169eba263a4c9 |

Package IDs verified by install path: `tw.personal.privateinbox.devtest` (devtest), `tw.personal.privateinbox` (prod).
VersionName 0.4.4-devtest (devtest variant) — note: versionName not bumped this round.

## Signing
- Debug keystore only (`keystore/debug.keystore`, git-ignored). apksigner verification NOT RUN this round
  (build-tools path not wired into this shell session) — marked **SIGNING_NOT_VERIFIED** for the release gate.

## Known Limits (NOT fixed, by design)
- Process death between capture and commit still loses in-memory batches (no Persistent Intake Journal) — documented, DESIGN_ONLY.
- `P0_INSTRUMENTATION=PASS` claimed ONLY for the devtest instrumentation suite above.
