# v0.4.6 Performance Evidence (T07)

## Conditions
- Emulator-5554, API 36, sdk_gphone64_arm64 (SwiftShader, no host GPU)
- HEAD bb36c83 + uncommitted v0.4.6 work
- Sample counts shown per metric; percentile precision limited by sample size
- **No previous same-conditions baseline exists → CURRENT_BASELINE_ESTABLISHED.**
  No relative-improvement claims are made.

## Canonical layer (NEW in v0.4.6) — measured via CanonicalPerformanceInstrumentedTest

| Operation | p50 | p95 | p99 | n |
|---|---|---|---|---|
| resolve — NEW conversation | 4.4 ms | 8.8 ms | 10.2 ms | 100 |
| resolve — existing (warm lookup) | **53 µs** | 90 µs | 122 µs | 100 |
| link single message | 8.1 ms | 19.1 ms | 26.7 ms | 100 |
| conversationPage — 100 convos | **294 µs** | 338 µs | 460 µs | 30 |
| conversationPage — 1000 convos | **878 µs** | 1.04 ms | 1.04 ms | 10 |

## Interpretation
- The hot path (a notification for an EXISTING conversation) adds a **~53 µs**
  alias lookup — negligible against the ~ms-scale SQLite commit.
- The cold path (a brand-new conversation) costs ~4 ms once; it is not repeated.
- `link` at ~8 ms p50 is the most expensive new step; it runs on the worker
  AFTER commit, so it does not delay the notification callback and a failure is
  recoverable (reconcile).
- `conversationPage` is sub-millisecond even at 1000 conversations — the SQL
  summary approach did not introduce an N+1 or a full-table decrypt.
- The burst suite (500 messages) passes functionally with the layer enabled.

## Existing suites (re-run this round)
- LatencyBenchmark + StorageBenchmark + PerformanceBaseline: **14/14 pass**
  (Time: 230.761s total). These suites pin the pre-existing latency/memory
  baselines; no regression observed.

## NOT measured
- Cold/warm app start delta (needs an APK-side timing harness; not present).
- Real-device numbers (emulator only).
- p99 beyond 1000 conversations.
