# Private Inbox Classic — Current Engineering State

**Single source of truth for "where the project is right now."**
Updated: 2026-10-24 · Target: v0.4.6 · Verdict: READY_FOR_PUBLICATION (pending independent review)

## 1. Where things are

| What | Path |
|---|---|
| The only working tree | `PrivateInboxClassic-v0.2/codex-integration` (workspace root) |
| Branch / HEAD | `v045-reliability` @ `bb36c83f524c0aa87ba04680f0f4c0bfea79318f` (uncommitted work on top) |
| Tracking | `origin/fix/x200pro-notification-burst` (ahead 7) — NOT the public default `integration/dsh-v040` |
| Task ledger | `docs/handoff/current/TASK_STATE.json` |
| Agent state | `docs/handoff/current/AGENT_STATE.md` |
| Test matrix | `docs/verification/v0.4.6/FINAL-TEST-MATRIX.md` |
| Environment | JDK 17 (Homebrew aarch64), Gradle 8.13, SDK at `~/Desktop/我的已讀不回助理/android-sdk` |
| Snapshots | `workspace/snapshots/v0.4.6/pi-v046-r0-snapshot/`, `workspace/snapshots/v0.4.6/pi-v046-r1postfix-snapshot/`, `workspace/snapshots/v0.4.6/pi-v046-t00-snapshot/` |

**Every build/test command must run after `source scripts/_env.sh`.**

## 2. SQLite schema: version 6 (+ idempotent onOpen repair)

- `messages` — UNCHANGED since v5 (fingerprint, sealed_content, sealed_image…).
- v6 additions: `conversations`, `conversation_aliases` (+`provenance` column),
  `message_conversation`, `message_conversation_undo`,
  `conversation_alias_undo`, `conversation_merge_log`.
- `onUpgrade(5→6)`: additive DDL + alias back-fill, transactional, rollback-proven.
- `onOpen`: ONE-SHOT per-process repair (guarded by a read-only `sqlite_master`
  probe) for pre-G1 v6 databases; never re-runs the back-fill on healthy DBs.

## 3. Identity contract (implemented & tested)

- Legacy key `pkg\0(shortcut?:title)\0title` is IMMUTABLE and remains the
  fingerprint + DHC V1 input.
- Canonical id `cv_<uuid>` is allocated + mapped (never derived from mutable text).
- Provenance: PLATFORM_CONFIRMED / LEGACY_INFERRED / TITLE_ONLY /
  MANUAL_CONFIRMED / AMBIGUOUS, persisted in `conversation_aliases.provenance`;
  NULL → AMBIGUOUS (never reinterpret historical STRONG).
- Merge/unmerge: batch-scoped undo journal restores alias ownership AND message
  links; cross-package, self, cycle and unknown-id merges are refused.
- The rename limitation is documented: a renamed title changes the fingerprint
  input, so a re-delivered message is a new ROW (the CONVERSATION stays one).

## 4. What is wired into the product

| Layer | Status |
|---|---|
| Listener → resolver → link → reconcile | DONE (`PrivateNotificationListener`: linkBatchToCanonicalConversation, reconcilePendingLinks via flushPrepared with the batch context) |
| UI canonical grouping | DONE (`MainActivity`: refreshCanonicalKeys + threadKey canonical-first + reconcile in loadEntries) |
| Search / filters | DONE (QA-01C: keyword, source, combined, date, unicode, empty, rename) |
| Read model | DONE (`conversationPage`, `conversationCountAll`, `readConversationMessages` — NUL-safe hex matching, no full-table decrypt) |
| Portable archive (Phase 2) | DESIGN_ONLY — not implemented |
| Persistent Intake Journal | DESIGN_ONLY |
| Rust/UniFFI | DESIGN_ONLY |

## 5. Verification summary (fresh)

- Instrumentation: 239 run across 25 suites; 0 product failures; 1 documented
  flake (`SystemNotificationE2E` link under full-suite ordering — FLAKY_UNRESOLVED,
  passes 3/3 isolated). Fresh run at /tmp/t05-final.log
- JVM: 157/157.
- DHC interop: Python 27 pass, Kotlin 14 pass, REAL round trip PASS, wrong
  passphrase rejected.
- Performance: CURRENT_BASELINE_ESTABLISHED (see PERFORMANCE-EVIDENCE.md).
- Signing: debug key A `c876051e…`; **PRODUCTION_SIGNING_BLOCKED**. Upgrade over public v0.4.4-alpha verified via `adb install -r`.

## 6. Known limitations / blockers
1. Rename changes the fingerprint input → re-delivered message is a new ROW.
2. Messages captured during a merge stay with the target after unmerge.
3. Process death between capture and commit loses in-flight rows.
4. Emulator-only evidence; no OEM/real-device verification.
5. Release signing does not exist; debug-only artifacts.

## 7. Next atomic action
R07-E independent review of PR #4 (https://github.com/juhjuhx/PrivateInboxClassic/pull/4), then conditional publication.
