# v0.4.6 — Agent State (continuation checkpoint)

## Current Phase
Phase 1 (Conversation Identity, R1 APPROVED) — **partially implemented**.
Pure-logic layer done and green; SQLite layer + wiring NOT started.

## Actual HEAD
`bb36c83f524c0aa87ba04680f0f4c0bfea79318f` — no commits created this round.

## Dirty Files (all preserved, nothing reset)
Modified (v0.4.5 candidate, uncommitted):
```
 M app/src/androidTest/java/tw/personal/privateinbox/BurstCaptureInstrumentedTest.kt
 M app/src/main/java/tw/personal/privateinbox/CaptureDiagnostics.kt
 M app/src/main/java/tw/personal/privateinbox/InboxDatabase.kt
 M app/src/main/java/tw/personal/privateinbox/PrivateNotificationListener.kt
```
Untracked (v0.4.5 + v0.4.6 work):
```
CHECKPOINT-0.md CHECKPOINT-R1.md CHECKPOINT-R2.md
app/src/androidTest/.../BatchRetryFaultInjectionInstrumentedTest.kt
docs/handoff/v045/**  (10 docs)
docs/handoff/v046/**  (00,01,02,03,04,05,SKILLS-MATRIX + golden/)
scripts/release/verify-artifact.sh scripts/release/install-artifact.sh
```

## Completed this round
1. Phase 0.1 tool preflight — PASS (bash/gradle/adb/emulator verified).
2. Phase 0.4 repository understanding — `00-BASELINE.md`, `01-ARCHITECTURE-AS-IS.md`.
3. **Three defects confirmed from source, not assumed:**
   - D1 `ConversationIdentity.key` appends the mutable title unconditionally
     (`ConversationIdentity.kt:40`) → rename splits one chat into two boxes.
   - D2 `fingerprint` depends on `conversation_key` (`InboxDatabase.kt:229`) →
     any key rewrite breaks dedup for every existing row.
   - D3 `ImageCapture.readBytes()` checks the deadline *before* `read()`
     (`ImageCapture.kt:68-69`) → a blocking provider cannot be interrupted.
4. R1 architecture fixed in `03-CONVERSATION-IDENTITY-CONTRACT.md`
   (alias mapping, legacy key immutable, canonical id never derived).
5. `02-IMPLEMENTATION-PLAN.md`, `04-MIGRATION-CONTRACT.md`,
   `05-DHC-COMPATIBILITY.md`, `SKILLS-MATRIX.md` written.
6. **Implemented + green (TDD, RED first):**
   - `LegacyKeyCodec.kt` — parse/format the historical key, ambiguity-aware.
   - `ConversationIdentityResolver.kt` — canonical id resolution over an
     `AliasLookup` seam (pure, JVM-testable).
7. **Phase 4 partial (CLI + signing):**
   - `scripts/release/verify-artifact.sh` — SHA-256, apksigner, package id,
     version, INTERNET absence, device signature compatibility. Tested: correct
     hash (pass), wrong hash (exit 1), missing arg, missing file.
   - `scripts/release/install-artifact.sh` — refuses on signature mismatch with a
     backup/migration message, `install -r` only on match. **Tested with a
     deliberately re-signed APK: refused (exit 1), installed package untouched.**
   - Signing fact established by measurement: the installed APK and
     `keystore/debug.keystore` are the same cert `46488e99…`. `docs/SIGNING.md`
     claims the project uses `c876051e…` — **that document is wrong** and needs
     correcting in Phase 4.

## Tests passed (fresh, this round)
| Suite | Result |
|---|---|
| `:app:testProdDebugUnitTest` (all JVM) | **157 tests, 0 failures, 0 errors, 0 skipped** |
| `LegacyKeyCodecTest` | 14 pass |
| `ConversationIdentityResolverTest` | 16 pass |
| `LegacyFingerprintCompatibilityTest` | 5 pass |
| `verify-artifact.sh` (4 paths) | correct-hash PASS / wrong-hash exit 1 / no-arg PASS / missing-file exit 1 |
| `install-artifact.sh` (2 paths) | mismatch refused exit 1 / match installed, data preserved |

## Tests NOT run (honest)
- **Instrumentation: NOT run this round.** The v5→v6 migration test
  (`ConversationMigrationInstrumentedTest`) does not exist yet, and the SQLite
  layer is not implemented, so running the suite would prove nothing new.
  The previous round's 139/139 is NOT claimed as this round's evidence.
- Kotlin↔Python DHC interop: not executed (needs the Python env); fixtures
  specified only.
- Lint / build of the release variants after these source additions: not run.

## Blocking reason (for the next slice)
None environmental. The next slice is blocked only on doing the work:
implement `ConversationStore` (schema v6 + backfill + merge/unmerge), then the
instrumentation migration test.

## Next atomic action
1. Write `ConversationMigrationInstrumentedTest` (RED): v5 fixture → open →
   assert row count / fingerprint bytes / sealed_content bytes / conversation_key
   all unchanged, plus alias rows created.
2. Implement `ConversationStore` + `InboxDatabase` v5→v6 upgrade.
3. Re-run: focused unit → instrumentation → full devtest suite → lint → build →
   audits.
4. Only then wire the listener + UI grouping.

## Note on the other project's instructions
The user message contained a second block of instructions about
`reuse_eligibility()`, pytest, `test_two_paths_one_device_not_independent`,
P07.1/P08, NAS mirror and Draft PR #1. **That codebase is not present in this
workspace** (verified: no `reuse_eligibility`, no `conftest.py`, no pytest
config). Those instructions cannot be executed here and were not attempted; this
round is scoped to Private Inbox Classic only.
