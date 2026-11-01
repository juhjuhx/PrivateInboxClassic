# Private Inbox Classic — Current Engineering State

**Single source of truth for "where the project is right now."**
Updated: 2026-11-01 · Target: v0.4.6 Experimental Debug Alpha · Verdict: REVIEW_PENDING (independent GitHub reviewer APPROVE required)

## 1. Where things are

| What | Path/Value |
|---|---|
| The only active working tree | `/tmp/v046-final` |
| Branch / HEAD | `v046-integration-repair-final` @ `1d4029fbabf1f0fda0f6f49cb1696f4bb489098d` |
| PR | https://github.com/juhjuhx/PrivateInboxClassic/pull/4 (open, DRAFT) |
| Tracking | `origin/integration/dsh-v040` |
| Task ledger | `docs/handoff/current/TASK_STATE.json` |
| Agent state | `docs/handoff/current/AGENT_STATE.md` |
| Test matrix | `docs/verification/v0.4.6/FINAL-TEST-MATRIX.md` |
| Environment | JDK 17 (Homebrew aarch64), Gradle 8.13, SDK at `/opt/homebrew/share/android-commandlinetools` |
| Snapshots | Not created in this cycle (release is docs-only; no new product build) |

**No product code has changed since `d376f40`. All commits after `d376f40` are documentation-only.**

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
| Listener → resolver → link → reconcile | DONE |
| UI canonical grouping | DONE |
| Search / filters | DONE |
| Read model | DONE (`conversationPage`, `conversationCountAll`, `readConversationMessages` — NUL-safe hex matching, no full-table decrypt) |
| Portable archive (Phase 2) | DESIGN_ONLY — not implemented |
| Persistent Intake Journal | DESIGN_ONLY |
| Rust/UniFFI | DESIGN_ONLY |

## 5. Verification summary

- Instrumentation: **291 / 291 PASS** (0 skipped, 0 failed) — CI run 41275245825 attempt 2
- JVM: 157 / 157 PASS
- Lint: PASS
- Security audits: PASS (secrets + history, test isolation 51 files)
- DHC connector: PASS
- Release audit / version regression: PASS / PASS
- Signing: debug key `c876051e…`; **PRODUCTION_SIGNING_BLOCKED**. Upgrade over public v0.4.4-alpha verified via `adb install -r` on API 33 emulator.

## 6. IR-12 upgrade verification (corrected)

- Public `v0.4.4-alpha` (`6c6def17…`, cert `c876051e…`) → v0.4.6 (`b68fd821…`, cert `c876051e…`)
- Method: `adb install -r`, no uninstall, no data clear
- versionCode 10 → 11; signature `[a0e2e7cb]` unchanged before and after
- Encrypted message-table readability: **VERIFIED on API 33 emulator** — genuine
  encrypted data was captured by v0.4.4-alpha, the candidate was installed over
  it in place, and the upgraded app decrypted and displayed the message,
  including after a force-stop and relaunch.
- Evidence: `docs/verification/v0.4.6/IR12-APK-UPGRADE-EVIDENCE.md`
- Limitation: Emulator-only validation; no OEM/real-device verification.

## 7. Known limitations / blockers

1. Rename changes the fingerprint input → re-delivered message is a new ROW.
2. Messages captured during a merge stay with the target after unmerge.
3. Process death between capture and commit loses in-flight rows.
4. Emulator-only evidence; no OEM/real-device verification.
5. Release signing does not exist; debug-only artifacts (Debug Alpha).
6. No in-place upgrade path to a future production-signed build (data loss on switch).
7. DHC export is one-way; no in-app restore.

## 8. Remaining external blocker

- **FORMAL_REVIEW=PENDING** — independent GitHub reviewer APPROVE required
- Only account `juhjuhx` is PR author; GitHub blocks self-review
- PR remains DRAFT until reviewer signs off

## 9. Next atomic action

Request independent reviewer APPROVE on PR #4; upon approval, merge PR #4 and proceed to conditional v0.4.6-alpha publication.
