# v0.4.6 Phase 1 — Acceptance Evidence

Five acceptance conditions, each backed by a test that runs against **real
SQLite on the devtest package**. No mocks, no hand-waving.

Command used for all instrumentation evidence:

```bash
adb shell am instrument -w \
  -e class tw.personal.privateinbox.ConversationIdentityInstrumentedTest \
  tw.personal.privateinbox.devtest.test/androidx.test.runner.AndroidJUnitRunner
```

Result: **OK (11 tests)** — 0 failures.

---

## 1. Same LINE conversation survives restart + rename

**Test:** `acceptance1_sameConversationSurvivesRenameAndRestart`

What it does, in order:

1. `resolve("jp.naver.line.android", "sc-line-1", "家族群組")` → canonical id A.
2. Build a **brand-new** `ConversationStore` + resolver over the same database
   (this is the app-restart simulation) → id must still be A.
3. Resolve again with a **renamed** title `"家族群組（新）"` → id must still be A.
4. Assert the old v0.4.5 key WOULD have differed, proving the defect was real.

Assertions:

| Check | Expected | Observed |
|---|---|---|
| id after restart | same as before | **same** |
| id after rename | same as before | **same** |
| legacy key before/after rename | **different** (the bug) | **different** |

The last row is the important one: it proves the test is exercising the actual
failure mode rather than passing trivially.

Supporting: `acceptance1c_aRenameDoesNotDuplicateTheMessage` confirms a stored
row is not duplicated on a re-open.

---

## 2. Two same-named groups are NOT merged

**Test:** `acceptance2_twoSameNamedGroupsAreNotMerged`

```
resolve(pkg, "sc-group-A", "家族群組") -> id A
resolve(pkg, "sc-group-B", "家族群組") -> id B
assert A != B
assert conversationCount(pkg) == 2
```

| Check | Expected | Observed |
|---|---|---|
| two shortcuts, same title | different ids | **different** |
| live conversation count | 2 | **2** |

**Test:** `acceptance2b_withoutAShortcutTheIdentityIsFlaggedAmbiguous`

When the source supplies no shortcut, the resolution reports
`ambiguous = true`, `strength = WEAK`, and the stored conversation is reported
`isAmbiguous == true`. The UI is therefore able to say "this grouping is
uncertain" instead of presenting a guess as fact — which is the product boundary
the brief requires.

**Test:** `acceptance2c_differentPackagesNeverMerge` — the same shortcut id in
LINE and Telegram yields two different conversations.

---

## 3. Fingerprint and DHC V1 message id unchanged

**Test:** `acceptance3_existingFingerprintIsUnchangedByResolution`

1. Compute `CryptoStore.fingerprint(...)` for a message → bytes F1.
2. Store the message through the production `save()` path.
3. Resolve the conversation twice, including a rename.
4. Recompute the same fingerprint → bytes F2.
5. Assert F1 == F2 byte for byte (hex compared).

**Test:** `acceptance3b_dhcV1MessageIdIsUnchanged`

`DhcExporter.computeMessageId(...)` is called before and after resolution and
asserted equal, and asserted to keep the `msg_` V1 prefix.

Supporting JVM evidence (no device needed, 5 tests):

`LegacyFingerprintCompatibilityTest` pins `CryptoStore.fingerprintInput()` output
**bytes** against 8 golden vectors generated from the pre-v0.4.6 formula by
`docs/handoff/v046/golden/gen_fingerprint_vectors.py`. Because the HMAC key is
per-device Keystore material, the hash is not portable — but the input bytes are
exactly what decides whether an existing row is recognised as a duplicate, so
pinning them is the meaningful guarantee.

| Check | Expected | Observed |
|---|---|---|
| fingerprint bytes after resolution | identical | **identical** |
| DHC V1 message id | identical | **identical** |
| 8 golden input vectors | match | **match** |
| canonical id `cv_` never enters the fingerprint title slot | absent | **absent** |

---

## 4. Migration keeps the message count and the old ciphertext readable

**Test:** `acceptance4_migrationPreservesRowCountAndCiphertext`

1. Store 5 rows through `save()`, including long CJK text and an emoji.
2. Snapshot `(id, conversation_key, received_at, source_ts, fingerprint,
   sealed_content)` for every row.
3. Force a re-open (the v6 upgrade path).
4. Snapshot again and compare **byte for byte**.

| Property | Expected | Observed |
|---|---|---|
| row count | 5 | **5** |
| every `id` | unchanged | **unchanged** |
| every `conversation_key` | unchanged | **unchanged** |
| every `fingerprint` | byte-identical | **byte-identical** |
| every `sealed_content` | byte-identical | **byte-identical** |
| every `received_at` | unchanged | **unchanged** |
| every `source_ts` | unchanged | **unchanged** |

**Test:** `acceptance4b_oldCiphertextIsStillDecryptable` — after the v6 open, the
sealed row still decrypts to the original body (`可以被解密的舊訊息`) and sender.

**Test:** `acceptance4c_backfillIsIdempotent` — the first back-fill creates one
alias per distinct legacy key (2), the second creates **0**, and the conversation
count is stable.

---

## 5. Full instrumentation regression

See `06-REGRESSION-EVIDENCE.md` (written after the run completes). The v0.4.5
capture reliability suite (BurstCapture, NotificationPipeline, BatchRetry,
WalBenchmark, DbContract, ConcurrentWriter, Pagination, KeyRecovery, …) must stay
green; the previous round's numbers are NOT reused as this round's evidence.

---

## Product boundary: ambiguity is preserved, never papered over

The brief's boundary — *"when the source app does not provide a stable
conversation identity, text alone may not identify the conversation; keep the
ambiguous state and let the user confirm"* — is implemented as follows:

- `Resolution.ambiguous = true` whenever the only signal is a display title.
- `Strength.WEAK` is stored on the alias row; `ConversationStore.isAmbiguous(cid)`
  reports it.
- The resolver **never** merges two conversations on weak evidence. A weak
  identity maps only to itself.
- Merge is a separate, explicit, reversible operation (`merge` / `unmerge`), and
  `mergeAndUnmergeDoNotTouchMessagesOrFingerprints` proves it does not alter any
  message row, fingerprint or ciphertext.

There is no code path that silently merges two conversations because their titles
look alike. That was the v0.4.5 failure mode and it is now structurally
impossible: a canonical id is allocated and mapped, never derived from text.
