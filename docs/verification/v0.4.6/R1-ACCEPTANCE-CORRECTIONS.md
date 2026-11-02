# v0.4.6 R1 — Acceptance Test Corrections

Three tests were reported as passing but their evidence did not support the claim.
This document records what was wrong, what replaced it, and the fresh result.

## R1-A — Real v5 → v6 migration

### What was wrong

`ConversationIdentityInstrumentedTest.acceptance4_migrationPreservesRowCountAndCiphertext`
created the database with the **current** `InboxDatabase` helper. Because the
helper is already at version 6, that path runs `onCreate` — `onUpgrade` never
executes. Re-opening the file afterwards proves only that a v6 database survives
a close/open. It could not have detected a broken v5→v6 migration.

### What replaced it

`ConversationMigrationV5InstrumentedTest` (5 tests) builds a **genuine v5
fixture**:

- the exact v0.4.5 schema (no `conversations` / `conversation_aliases` /
  `message_conversation` tables),
- `PRAGMA user_version = 5` via `db.version = 5`,
- 4 synthetic rows across 2 packages, including the same shortcut under two
  titles (the rename case) and a title-only row.

It then asserts the fixture really is v5 **before** opening it with v6 code, and
opens it so `onUpgrade(5, 6)` actually runs.

| Test | Proves |
|---|---|
| `realV5ToV6_upgradePreservesEveryHistoricalField` | version becomes 6; the 3 new tables exist; every row's `id`, `conversation_key`, `fingerprint` byte-identical |
| `realV5ToV6_aliasesAreBackfilledFromTheRealKeys` | SHORTCUT alias back-filled; **both renamed legacy keys** map to one canonical id; LINE has 2 conversations, Telegram 1 (no cross-package merge) |
| `realV5ToV6_titleOnlyRowIsAmbiguous` | the title-only key is aliased but reported `isAmbiguous == true` |
| `realV5ToV6_reopeningDoesNotDuplicateTheBackfill` | two further opens do not grow the conversation or alias count |
| `realV5ToV6_interruptedMigrationRecoversWithoutDuplicates` | tables pre-created + `version` forced back to 5 → `onUpgrade` re-runs without throwing and without duplicates |

### What is deliberately NOT claimed

The sealed blobs in the fixture are **synthetic bytes**, not real AES-GCM
output. The Keystore key that would produce real ciphertext lives on a device and
cannot be moved into a portable fixture. The migration test therefore proves
**byte-for-byte preservation**, not "the fixture ciphertext decrypts".
Decryption of real rows is covered separately by
`acceptance4b_oldCiphertextIsStillDecryptable`, which writes through the
production path on the device itself.

**Result: OK (5 tests).**

---

## R1-B — Rename dedup

### What was wrong

`acceptance1c_aRenameDoesNotDuplicateTheMessage` stored one message and asserted
the row count was 1. It never re-delivered anything after a rename, so it
verified nothing about the scenario its name describes.

### What replaced it

The test now performs the full sequence:

1. conversation WITH a stable shortcut id,
2. store a message,
3. rename the conversation,
4. re-deliver the same message (same shortcut, timestamp, body, discriminator),
5. assert the **canonical conversation is still one**,
6. assert the actual row count, and
7. add a genuinely new message → must be `NEW`, then re-deliver it → must be
   `DUPLICATE`.

### The honest finding

The fingerprint input is the **legacy key**, which embeds the title. A rename
therefore changes the fingerprint input, so the re-delivered message is stored as
a **second row** (`NEW`, not `DUPLICATE`). That is a **pre-existing v0.4.5
limitation**, not something v0.4.6 introduced, and this test states it explicitly
rather than hiding it:

```
assertEquals("the rename must not create a second canonical conversation", 1, lineConversations)
assertEquals("the re-delivered message is still stored ... the LIMITATION is
              documented, not hidden", 2, countAfterRedelivery)
```

The v0.4.6 fix is that the **conversation** no longer splits; the row-level
duplicate across a rename remains a known limitation. Changing it would require
rewriting the stored legacy key, which is forbidden (it would break dedup and DHC
identity for every existing row).

**Result: OK (1 test).**

---

## R1-C — Fingerprint compatibility through real outcomes

### What was wrong

The previous evidence called `CryptoStore.fingerprint()` twice with identical
arguments and asserted equality. That proves a pure function is pure; it says
nothing about whether an already-stored row is recognised on re-delivery after the
change.

### What replaced it

`FingerprintCompatibilityInstrumentedTest` (8 tests) drives the **production
`save()` path** and asserts the real `NEW` / `DUPLICATE` results:

| Test | Case | Assertion |
|---|---|---|
| `sameShortcutSameTitleIsDuplicateOnRedelivery` | identical re-delivery | `DUPLICATE`, row count unchanged |
| `sameShortcutDifferentTitleChangesTheFingerprint_documentedLimitation` | rename | row grows (documented), canonical conversation stays **1** |
| `differentShortcutSameTitleIsNotDuplicate` | title collision | both `NEW`, two canonical conversations |
| `missingSourceTimestampUsesTheDiscriminatorAndStillDedups` | `ts = 0` | same discriminator → `DUPLICATE`; different → `NEW` |
| `twoIdenticalBodiesInTheSameMillisecondAreBothStored` | same ms, same text | both stored; each re-delivery dedups against its own row |
| `multipleLegacyAliasesForOneShortcutStillDedupAgainstTheirOwnRows` | two titles, one shortcut | 2 rows, 1 canonical conversation, **2** legacy aliases |
| `theStoredFingerprintBytesAreExactlyTheHistoricalFormula` | formula pin | the stored blob equals `CryptoStore.fingerprint(...)` hex |
| `theCanonicalIdNeverAppearsInTheStoredConversationKey` | key integrity | stored key is the legacy format; never `cv_` |

**Result: OK (8 tests).**

### A product defect this uncovered

`multipleLegacyAliasesForOneShortcutStillDedupAgainstTheirOwnRows` initially
failed with `legacy.size == 1` instead of 2. Root cause: when the SHORTCUT alias
already existed, the resolver returned immediately and **never recorded the new
legacy key**, so a renamed conversation's new key was lost — which would have
broken a later back-fill or a DHC import that only knows the old key.

Fixed in `ConversationIdentityResolver.resolve()`: a SHORTCUT hit now also
registers the current legacy key (idempotent, `INSERT OR IGNORE`).

The JVM fake was corrected to mirror `INSERT OR IGNORE` semantics (only a new row
counts as an insert), which is what the production table's composite primary key
does.

---

## Fresh full-suite result (after all R1 changes)

```
adb shell am instrument -w \
  tw.personal.privateinbox.devtest.test/androidx.test.runner.AndroidJUnitRunner

EXIT=0    OK (163 tests)
```

28 classes / 163 tests / 0 failures / 0 skipped.

Growth: 150 → 163 (+5 v5 migration, +8 fingerprint compatibility).

| Class | Tests |
|---|---|
| **ConversationMigrationV5InstrumentedTest** | **5 (new)** |
| **FingerprintCompatibilityInstrumentedTest** | **8 (new)** |
| ConversationIdentityInstrumentedTest | 11 (R1-B rewritten) |
| BatchRetryFaultInjectionInstrumentedTest | 7 |
| BurstCaptureInstrumentedTest | 10 |
| NotificationPipelineInstrumentedTest | 10 |
| WalBenchmarkInstrumentedTest | 3 |
| DbContractInstrumentedTest | 4 |
| ConcurrentWriterInstrumentedTest | 3 |
| MigrationInstrumentedTest | 6 |
| PaginationInstrumentedTest | 12 |
| (all others) | 84 |

JVM: **157 tests / 0 failures / 0 errors / 0 skipped**.

## Remaining gaps (not claimed as done)
- Listener and UI are **not yet wired** to the resolver (R3). Canonical grouping
  exists in storage + resolver only; the app still groups by the legacy key.
- Kotlin↔Python DHC interop not executed this round.
- Real-device / non-emulator ROM behaviour unverified.
