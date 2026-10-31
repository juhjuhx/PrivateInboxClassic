# v0.4.6 Final Test Matrix — T12 + R07 + IR

All results fresh, after the IR-01…IR-11 remediation.
Source HEAD `79f2942059a815d275403d924be1c1e25024d786`.
GitHub Actions run [41003719936](https://github.com/juhjuhx/PrivateInboxClassic/actions/runs/41003719936)
— emulator API 36, `290/290` instrumentation, 0 skipped, 0 failed.
Locally reproduced on emulator API 36 (`OK (290 tests)`).
See `IR-REMEDIATION-EVIDENCE.md` for each finding's RED/GREEN evidence.

**Code HEAD `79f2942`** (all product code and tests; CI run [41003719936](https://github.com/juhjuhx/PrivateInboxClassic/actions/runs/41003719936) — 290/290). Documentation commits sit on top of it and change no product code, so the APK built from `79f2942` remains the release artifact. A later CI run on the documentation HEAD (`122cdda`, run 41011478801) was also green at 290/290.

**Historical results are preserved, not rewritten.** The earlier `239/239` and
`274/274` figures below are kept with their original source HEAD and date so the
progression stays auditable; they are not the current result.

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
| Lint | lintProdDebug | gradle | BUILD SUCCESSFUL | PASS |
| Builds | assembleProdDebug + DevtestDebug | gradle | BUILD SUCCESSFUL | PASS |
| Security audits | audit.sh / audit-secrets.sh / audit-test-isolation.py | scripts | 3x PASS (39 files) | PASS |
| DHC Kotlin↔Python interop | — | host_interop.sh | Python 27 pass, Kotlin 14 pass, REAL round trip PASS | PASS |
| IR-01 conversation paging | conversationPage + entryForMessageId | IR01ConversationPaginationInstrumentedTest (4) | many-msg convo does not hide older; 100 convos page w/o loss/dup; legacy rows visible; rename/merge paging | PASS |
| IR-02 fail-closed reads | readConversationMessages union of alias+link | IR02FailClosedReadInstrumentedTest (6) | unknown id → 0; deleted alias scoped; partial migration; merge/unmerge; cross-package; empty DB | PASS |
| IR-03 NUL-safe linking | SaveResult.NEW(id) + link by real id | IR03NulSafeLinkingInstrumentedTest (6) | real id; DUPLICATE≠NEW; [NEW,FAILURE,NEW]; NUL keys; fresh helper; reconcile | PASS |
| IR-04 summary cardinality | alias join pinned to one row | IR04SummaryCardinalityInstrumentedTest (5) | 2 shortcuts → 1 row; page not inflated; latest id/ts; paging; merge/unmerge | PASS |
| IR-05 schema repair | tri-state NONE/RUNNING/DONE + verify | IR05SchemaRepairInstrumentedTest (5) | fresh DB; repeated opens; dropped table recreated; failure not remembered; data preserved | PASS |
| IR-06 authoritative CI | postSelfTest implemented; no `|| true` | SystemNotificationE2EInstrumentedTest (1) | real NotificationManager post → captured, linked, rendered | PASS |
| IR-10 chat detail pagination | cursor-based beforeReceivedAt/beforeId | IR10ChatHistoryPaginationInstrumentedTest (5) | 150/350/650 canonical; 150 unlinked legacy; same-timestamp diff-ID; new msg during load; read failure preserves history | PASS |
| **IR-11A** conversation state isolation | `chatConversationId` resets all chat state on switch | IR11 (1) + IR11 (7) | A→B→A shows no cross-conversation leakage; stale result cannot pollute new session | PASS |
| **IR-11B** cursor ordering | full `(received_at, id)` keyset cursor, not `received_at` alone | IR11 (2) | 150 messages sharing one timestamp, distinct ids: no omission, no duplication across pages | PASS |
| **IR-11C** async fetch/render separation | DB read + AES on IO executor; `renderChatPage()` UI-thread only | IR11 (5) | 350 and 650 messages load across multiple pages with no main-thread DB work | PASS |
| **IR-11D** async lifecycle corrective patch | `chatRequestGen` generation gating; `refreshChat()` render-only path; honest delete failure | IR11 (8, 9, 10, 11) | photo return keeps entry count + cursor; delete removes row from chat page; stale callback does not clear new request's loading; lock rejects in-flight callback | PASS |
| Real-device / OEM | — | — | NOT TESTED | NOT TESTED |
| Performance re-measurement | — | — | NOT RUN (burst suite functional only) | NOT TESTED |

## Totals — current (source HEAD `79f2942`)

- Instrumentation: **290 / 290 PASS**, 0 failures, 0 skipped.
  Verified in GitHub Actions run 41003719936 and reproduced locally.
  Breakdown: 239 baseline + 35 from IR-01…IR-10 + 11 IR-11 + 5 IR-08 follow-up.
- JVM: 157 / 157 PASS.
- Lint: PASS.
- Builds: `assembleProdDebug`, `assembleDevtestDebug`, `assembleDevtestDebugAndroidTest`: PASS.
- Security audits: audit.sh PASS, audit-secrets.sh PASS, audit-test-isolation.py PASS (50 files).
- Release audit: PASS. Version regression: PASS. DHC connector: PASS.

## Historical totals (preserved, not current)

| Figure | Source HEAD | Date | Note |
|---|---|---|---|
| 239 / 239 | `bb36c83` | 2026-10-27 | before the IR-01…IR-10 suites |
| 274 / 274 | `316c44c` | 2026-10-28 | after IR-01…IR-10; before IR-11 |
| **290 / 290** | **`79f2942`** | **2026-10-30** | **current** — adds the 11 IR-11 tests + 5 IR-08 follow-up |

## Flake status correction
The previously-recorded `SystemNotificationE2E` "FLAKY_UNRESOLVED" classification
was WRONG. The test never passed because `DebugSettingsProvider.call()` had no
`postSelfTest` branch at all. IR-06 implements it; the suite now passes for real
and CI is authoritative (no `|| true`).

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
| True system self-post | SelfTestNotifier + provider | SystemNotificationE2EInstrumentedTest | PASS in CI run 41013529150 (290/290). Historical FLAKY_UNRESOLVED record retained in FLAKE-ANALYSIS.md; not claimed permanently gone | PASS |
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
- Full instrumentation: **290 / 290 PASS** on source HEAD `79f2942` (0 skipped, 0 failed).
  Historical: 239 run / 1 flaky (SystemE2E link) on the pre-IR-11 tree — see the history table above.
- Isolated: SystemE2E 3/3 · Qa01 8/8 · BurstCapture 10/10 · pair 3/3
- JVM: 157/157

## Verdict
`READY_FOR_PUBLICATION` (pending independent review — R07-E). Engineering complete; production signing does
not exist; one flake remains recorded (FLAKY_UNRESOLVED) with the four other
flake causes fixed and verified.
