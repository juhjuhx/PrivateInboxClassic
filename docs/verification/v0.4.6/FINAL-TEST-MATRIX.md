# v0.4.6 Final Test Matrix — T12

All results fresh, after the last production change (onOpen one-shot repair).
Emulator-5554, API 36, sdk_gphone64_arm64. HEAD bb36c83 (uncommitted worktree).

| Requirement | Implementation | Test | Evidence | Verdict |
|---|---|---|---|---|
| Real v5→v6 migration | InboxDatabase.onUpgrade | ConversationMigrationV5InstrumentedTest (7) | full-column byte compare, rollback injection, same-device fingerprint | PASS |
| Existing v6 upgrade | onOpen one-shot repair | ExistingV6CompatibilityInstrumentedTest (6) | journal tables added, data intact, stability across 3 reopens | PASS |
| Canonical read model | InboxDatabase.conversationPage / readConversationMessages | ConversationReadModelInstrumentedTest (11) | 1/100, 100/1, rename, page boundaries, NUL-safe hex matching | PASS |
| Merge/Unmerge | ConversationStore batch undo journal | ConversationMergeInstrumentedTest (13) | alias ownership restore, cycle/cross-package/self rejection | PASS |
| Alias provenance | 5-value Strength + provenance column | AliasProvenanceInstrumentedTest (8) | inferred vs confirmed, weaker-never-overwrites, restart persistence | PASS |
| Capture pipeline | Listener + resolver + link + reconcile | CapturePipelineIntegrationInstrumentedTest (9) | real capture() path, dedup, rename, restart, reconcile, burst-10 | PASS |
| SQLite integrity | PRAGMA checks + app-level audits | ConversationIntegrityInstrumentedTest (8) | integrity ok, no orphans, journal consistent, concurrent clean | PASS |
| UI grouping | MainActivity canonical grouping | ConversationUiLifecycleInstrumentedTest (6) | recreate, rename→1 box, merge/unmerge, legacy rows visible | PASS |
| Burst capture | v0.4.5 reliability suite | BurstCaptureInstrumentedTest (10) | full class 10/10; testJ flaky in full-run order (passes alone 3/3, class 10/10) | PASS w/ known flake |
| Pipeline reliability | v0.4.5 retry/dedup suites | BatchRetryFaultInjection (7), NotificationPipeline (10), WalBenchmark (3), DbContract (4), ConcurrentWriter (3) | all pass in full run | PASS |
| Migration v1..v5 chain | additive onUpgrade | MigrationInstrumentedTest (6) | pass | PASS |
| JVM unit suite | policy/fingerprint/image/etc | 157 tests | 0 failures | PASS |
| Lint | lintProdDebug | gradle | BUILD SUCCESSFUL | PASS |
| Builds | assembleProdDebug + DevtestDebug | gradle | BUILD SUCCESSFUL | PASS |
| Security audits | audit.sh / audit-secrets.sh / audit-test-isolation.py | scripts | 3x PASS (39 files) | PASS |
| DHC Kotlin↔Python interop | — | — | NOT EXECUTED | UNVERIFIED |
| Real-device / OEM | — | — | NOT TESTED | NOT TESTED |
| Performance re-measurement | — | — | NOT RUN (burst suite functional only) | NOT TESTED |

## Totals
- Instrumentation: 226 executed, 1 known flake (BurstCapture.testJ — tray-scheduling
  race under full-suite ordering; passes alone 3/3 and as a full class 10/10), 0 product failures.
- JVM: 157 / 0.

## Known limitations (stated, not hidden)
1. Rename changes the legacy key → fingerprint input → a re-delivered message is
   stored as a new row (canonical conversation stays ONE). Fixing the row-level
   duplicate requires rewriting stored keys — forbidden.
2. Messages captured during a merge stay with the target after unmerge (asserted
   contract).
3. Process death between capture and commit loses in-flight rows (Persistent
   Intake Journal is DESIGN_ONLY).
4. Emulator-only evidence; no OEM/real-device verification.
5. DHC cross-language interop not executed this round.


---

# RC HARDENING ROUND (H01–H05) — regenerated fresh

All rows re-verified after the LAST production change (busy_timeout in
onConfigure; row-tag in MainActivity; bodyOf package fix; testJ scoping;
per-run markers).

| Requirement | Implementation | Test | Fresh Evidence | Verdict |
|---|---|---|---|---|
| View hierarchy correctness | MainActivity row tags | Qa01ReadModelAndUiTest 8/8 | exact counts + exact order vs SQL summary | PASS |
| Date-range filtering | MainActivity.search predicate | same suite | ≥bound 2 / ≥0 5 / past 0 | PASS |
| Real UI search input | showSearch + EditText | same suite | rendered hits in hierarchy | PASS |
| 100-conversation pagination | conversationPage | same suite + ReadModel 11/11 | page1 keys == SQL summary first 50 | PASS |
| Same-title different-shortcut | resolver + links | qa01d | two independent rows rendered | PASS |
| v5→v6 migration | onUpgrade | MigrationV5 (7) | byte compare + rollback injection | PASS |
| Existing v6 upgrade | onOpen one-shot repair | ExistingV6 (6) | stability across 3 reopens | PASS |
| Merge/Unmerge | batch undo journal | Merge suite (13) | alias ownership restore | PASS |
| Capture pipeline | listener link + reconcile | Pipeline (9) | real capture path | PASS |
| True system self-post | SelfTestNotifier + provider | SystemE2E | ISOLATED 3/3 PASS; full-run 1 flake (link) | FLAKY |
| Burst capture | v0.4.5 suite | BurstCapture 10/10 | pass | PASS |
| Concurrent writer | suite | 3/3 | pass | PASS |
| SQLite integrity | PRAGMA + app audits | Integrity 8/8 | pass | PASS |
| DHC interop | real round trip | host_interop.sh | Python report ok=true | PASS |
| Performance | canonical layer measurements | CanonicalPerformance 4/4 | baseline established | PASS (baseline) |
| JVM unit | — | 157/157 | pass | PASS |
| Lint/Build/Audits | — | gradle + scripts | PASS | PASS |
| Real LINE/OEM devices | — | — | no hardware | NOT TESTED |
| Production signing | — | — | no release keystore | BLOCKED |

## Totals (fresh)
- Full instrumentation: **239 run / 1 flaky (SystemE2E link under full ordering) / 0 product failures**
- Isolated: SystemE2E 3/3 · Qa01 8/8 · BurstCapture 10/10 · pair 3/3
- JVM: 157/157

## Verdict
`RC_PREPARED_SIGNING_BLOCKED` — engineering complete; production signing does
not exist; one flake remains recorded (FLAKY_UNRESOLVED) with the four other
flake causes fixed and verified.
