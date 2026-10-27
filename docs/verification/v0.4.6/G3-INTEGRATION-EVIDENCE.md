# v0.4.6 G3 — Production Integration Evidence

## G2.5 — Identity risk closure (PASS)

### G2.5-A onOpen compatibility
- `onOpen` schema repair failure is now RECORDED
  (`SCHEMA_REPAIR_FAILED`, type name only) instead of silently swallowed.
- DDL remains idempotent; a healthy database does no work.
- Read-only opens do not write: `onOpen` only runs through the helper's
  writable path; raw read-only connections skip it entirely.

### G2.5-B provenance persistence
The five-value taxonomy is persisted in a NEW `conversation_aliases.provenance`
column (idempotent `ALTER TABLE ADD COLUMN`), so the historical `strength`
values ("STRONG"/"WEAK") stay untouched for older readers.

| Test | Proves |
|---|---|
| `provenanceSurvivesRestart` | LEGACY_INFERRED / PLATFORM_CONFIRMED / AMBIGUOUS all round-trip a restart |
| `preProvenanceRowsFallBackToAmbiguous` | a historical `STRONG` row with NULL provenance reads back AMBIGUOUS — never silently reinterpreted as PLATFORM_CONFIRMED |
| `provenanceColumnIsAddedIdempotently` | three opens, one ALTER, column present |

**Result: `AliasProvenanceInstrumentedTest` 8/8 PASS**

### G2.5-C integrity automation
`ConversationIntegrityInstrumentedTest` 8/8 PASS: `PRAGMA integrity_check` = ok,
`foreign_key_check` = 0 rows, zero orphan message links, zero dangling alias
owners, journal/log consistency after a merge, `clear()` wipes all 7 canonical
tables, repeated opens keep integrity clean, concurrent writer + linker threads
leave integrity clean with no exceptions.

## G3-A — production capture pipeline (PASS)

`CapturePipelineIntegrationInstrumentedTest` 9/9 PASS. Every test drives the
REAL `PrivateNotificationListener.capture()` entry point and asserts on rows
produced by the production write path.

| Test | Proves |
|---|---|
| `newMessageIsStoredAndLinkedThroughTheRealPipeline` | notification → listener → worker → encrypted save → canonical link (`cv_…`) |
| `redeliveryOfTheSameMessageDedupsAndDoesNotDuplicateTheLink` | re-delivery: no new row, no duplicate link |
| `renameKeepsOneCanonicalConversation` | renamed title still resolves to the SAME canonical id; both rows linked to it |
| `sameTitleDifferentShortcutsAreTwoConversations` | title collision stays two conversations |
| `sameTitleInDifferentPackagesNeverMerges` | cross-package isolation |
| `noShortcutStillStoresAndLinksAmbiguously` | title-only → stored, linked, `isAmbiguous == true` |
| `listenerRestartDoesNotBreakLinking` | service restart keeps one conversation |
| `pendingLinksAreReconciledAfterRestart` | links deleted → next listener reconciles them automatically |
| `burstOfNotificationsAllLandInTheirOwnConversations` | 10 bursts → 10 rows, 10 links, 10 conversations |

## Defects found and fixed during G3-A

1. **Reconcile never ran in tests**: the worker guarded it behind
   `applicationContext`, which is null for a test-driven listener. Moved into
   `flushPrepared()` using the BATCH's context — correct for both a system-bound
   listener and a test-driven one.
2. **Reconcile fought the linking pass**: an unconditional reconcile re-created
   links the test had deliberately removed and raced the batch link. Now guarded
   by "there really are unlinked rows", runs once per process.
3. **`put()` never wrote provenance** (G2.5-B): the column was only written by
   `insertAlias`, so every resolver-registered alias had NULL provenance and read
   back AMBIGUOUS. Fixed; caught by `provenanceSurvivesRestart`.

## Full regression (fresh, after the last production change)

```
adb shell am instrument -w \
  tw.personal.privateinbox.devtest.test/androidx.test.runner.AndroidJUnitRunner
EXIT=0    OK (209 tests)
```

31 classes / 209 tests / 0 failures / 0 skipped.
Growth: 189 → 209 (+8 provenance restart/persistence, +9 pipeline integration,
+8 integrity, −5 refactor of prior suites).

JVM: 157 tests / 0 failures / 0 errors.

## NOT done (honest)
- G3-B UI integration: `MainActivity` still groups by the legacy key.
- G3-C full UI end-to-end (Activity relaunch assertions).
- G3-D performance re-measurement.
- DHC Kotlin↔Python interop.
