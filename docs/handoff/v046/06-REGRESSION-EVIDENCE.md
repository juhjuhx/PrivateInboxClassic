# v0.4.6 — Regression Evidence (Acceptance Condition 5)

The requirement: the v0.4.6 identity change must not break the v0.4.5 capture
reliability. This is the fresh run — the previous round's 139/139 is NOT reused.

## Command

```bash
adb shell am instrument -w \
  tw.personal.privateinbox.devtest.test/androidx.test.runner.AndroidJUnitRunner
```

## Result

```
EXIT=0
OK (150 tests)
```

**26 classes / 150 tests / 0 failures / 0 skipped.**

Count breakdown: 139 (v0.4.5 baseline) + 11 (new `ConversationIdentityInstrumentedTest`).

## Per-class (every class is all-dots; no `F`, no `S`)

| Class | Tests |
|---|---|
| BatchRetryFaultInjectionInstrumentedTest | 7 |
| BurstCaptureInstrumentedTest | 10 |
| ConcurrentWriterInstrumentedTest | 3 |
| **ConversationIdentityInstrumentedTest** | **11 (new)** |
| DbContractInstrumentedTest | 4 |
| DebugProviderAccessControlInstrumentedTest | 5 |
| DestructiveOperationGuardTest | 8 |
| DhcExportInstrumentedTest | 7 |
| KeyRecoveryInstrumentedTest | 10 |
| KeyStateInstrumentedTest | 7 |
| LatencyBenchmarkInstrumentedTest | 3 |
| ListenerLifecycleInstrumentedTest | 3 |
| ListenerRobustnessInstrumentedTest | 4 |
| MigrationInstrumentedTest | 6 |
| NotificationPipelineInstrumentedTest | 10 |
| PaginationInstrumentedTest | 12 |
| PaginationPerformanceInstrumentedTest | 3 |
| PerformanceBaselineInstrumentedTest | 7 |
| ProcessDeathExperimentInstrumentedTest | 3 |
| SelfTestSystemNotificationInstrumentedTest | 4 |
| SourceSelectionRoundTripInstrumentedTest | 3 |
| StorageBenchmarkInstrumentedTest | 4 |
| UiRefreshInstrumentedTest | 2 |
| UiSnapshotInstrumentedTest | 4 |
| WalBenchmarkInstrumentedTest | 3 |
| DhcPythonFixtureInstrumentedTest | 7 |

## The specific v0.4.5 reliability classes, called out

| Class | v0.4.5 role | v0.4.6 result |
|---|---|---|
| BurstCaptureInstrumentedTest | burst capture / no message loss | 10/10 pass |
| NotificationPipelineInstrumentedTest | end-to-end capture + decrypt | 10/10 pass |
| BatchRetryFaultInjectionInstrumentedTest | retry state machine + fault injection | 7/7 pass |
| WalBenchmarkInstrumentedTest | WAL / write-loss regression | 3/3 pass |
| DbContractInstrumentedTest | save() contract, dedup | 4/4 pass |
| ConcurrentWriterInstrumentedTest | concurrent writer behaviour | 3/3 pass |
| MigrationInstrumentedTest | v1..v5 → current migration | 6/6 pass |

Nothing in the capture path regressed.

## What this run proves and what it does not

Proves: with the v6 schema and the conversation layer in place, every previously
passing behaviour still passes on a real API 36 emulator.

Does NOT prove: performance characteristics beyond what those benchmarks measure,
nor behaviour on a real device or a non-emulator ROM. Those remain unverified, as
before.
