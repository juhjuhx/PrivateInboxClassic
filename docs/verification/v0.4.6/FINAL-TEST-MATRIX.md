# v0.4.6 Final Test Matrix — T12 + R07 + IR

All results fresh, after the IR-01…IR-11 remediation.
Source HEAD `d376f407976f511837f963227115e5610f42083f` (all product code and tests; CI run [41231048769](https://github.com/juhjuhx/PrivateInboxClassic/actions/runs/41231048769) — 291/291).
Documentation commits sit on top of it and change no product code, so the APK built from `d376f40` remains the release artifact.

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
| Burst capture | v0.4.5 reliability suite | BurstCaptureInstrumentedTest (10) | full class 10/10; passes alone and as class | PASS |
| Pipeline reliability | v0.4.5 retry/dedup suites | BatchRetryFaultInjection (7), NotificationPipeline (10), WalBenchmark (3), DbContract (4), ConcurrentWriter (3) | all pass in full run | PASS |
| Migration v1..v5 chain | additive onUpgrade | MigrationInstrumentedTest (6) | pass | PASS |
| JVM unit suite | policy/fingerprint/image/etc | 157 tests | 0 failures | PASS |
| IR-01 conversation paging | conversationPage + entryForMessageId | IR01ConversationPaginationInstrumentedTest (4) | many-msg convo does not hide older; 100 convos page w/o loss/dup; legacy rows visible; rename/merge paging | PASS |
| IR-02 fail-closed reads | readConversationMessages union of alias+link | IR02FailClosedReadInstrumentedTest (6) | unknown id → 0; deleted alias scoped; partial migration; merge/unmerge; cross-package; empty DB | PASS |
| IR-03 NUL-safe linking | SaveResult.NEW(id) + link by real id | IR03NulSafeLinkingInstrumentedTest (6) | real id; DUPLICATE≠NEW; [NEW,FAILURE,NEW]; NUL keys; fresh helper; reconcile | PASS |
| IR-04 summary cardinality | alias join pinned to one row | IR04SummaryCardinalityInstrumentedTest (5) | 2 shortcuts → 1 row; page not inflated; latest id/ts; paging; merge/unmerge | PASS |
| IR-05 schema repair | tri-state NONE/RUNNING/DONE + verify | IR05SchemaRepairInstrumentedTest (5) | fresh DB; repeated opens; dropped table recreated; failure not remembered; data preserved | PASS |
| IR-06 authoritative CI | postSelfTest implemented; no `|| true` | SystemNotificationE2EInstrumentedTest (1) | real NotificationManager post → captured, linked, rendered | PASS |
| IR-10 chat detail pagination | cursor-based beforeReceivedAt/beforeId | IR10ChatHistoryPaginationInstrumentedTest (5) | 150/350/650 canonical; 150 unlinked legacy; same-timestamp diff-ID; new msg during load; read failure preserves history | PASS |
| IR-11 chat detail pagination safety | conversation state isolation / cursor ordering / async fetch & render / request-gen gating | IR11NavigationAndPaginationSafetyInstrumentedTest (11) | conversationIsolationAtoBtoA, cursorOrderingSameTimestamp, photoPreviewReturnPreservesHistory, deleteMessageShowsCorrectCount, pagination350and650, switchConversationDuringAsyncLoad, dbQueryFailureDoesNotLeakOtherConversation, photoPreviewReturnPreservesHistoryAndCursor, deleteRemovesMessageFromChatPage, staleCallbackDoesNotClearNewRequestLoading, lockRejectsInFlightChatCallback | PASS |
| Real-device / OEM | — | — | NOT TESTED | NOT TESTED |
| Performance re-measurement | — | — | NOT RUN (burst suite functional only) | NOT TESTED |

## Totals — current (source HEAD `d376f407976f511837f963227115e5610f42083f`)

- Instrumentation: **291 / 291 PASS**, 0 skipped, 0 failed.
  Breakdown: 239 baseline + 35 from IR-01…IR-10 + 11 IR-11 + 5 IR-08 follow-up.
- JVM unit tests: **157 / 157 PASS**.
- Lint: PASS.
- Builds: assembleProdDebug + assembleDevtestDebug + assembleDevtestDebugAndroidTest: PASS.
- Security audits: audit.sh PASS, audit-secrets.sh PASS, audit-test-isolation.py PASS (51 files).
- Release audit: PASS. Version regression: PASS. DHC connector: PASS.

## Historical totals (preserved, not current)

| Figure | Source HEAD | Date | Note |
|---|---|---|---|
| 239 / 239 | `bb36c83` | 2026-10-27 | before the IR-01…IR-10 suites |
| 274 / 274 | `316c44c` | 2026-10-28 | after IR-01…IR-10; before IR-11 |
| **291 / 291** | **`d376f407976f511837f963227115e5610f42083f`** | **2026-11-01** | **current** — adds the IR-11 test + 5 IR-08 follow-up |

## Known limitations (stated, not hidden)

1. Rename changes the legacy key → fingerprint input → a re-delivered message is stored as a new row (canonical conversation stays ONE). Fixing the row-level duplicate requires rewriting stored keys — forbidden.
2. Messages captured during a merge stay with the target after unmerge (asserted contract).
3. Process death between capture and commit loses in-flight rows (Persistent Intake Journal is DESIGN_ONLY).
4. Emulator-only evidence; no OEM/real-device verification.
5. DHC cross-language interop not executed this round.
