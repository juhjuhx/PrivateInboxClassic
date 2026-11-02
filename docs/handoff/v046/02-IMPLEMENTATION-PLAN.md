# v0.4.6 Phase 1 — Implementation Plan

Precise file list, interfaces, migration, RED→GREEN tests, acceptance, order.
R1 approved; no further approval needed for these steps.

## Files to create / change

### New
| File | Purpose |
|---|---|
| `app/src/main/java/tw/personal/privateinbox/ConversationStore.kt` | schema v6 tables, alias CRUD, canonical id allocation, backfill, merge/unmerge |
| `app/src/main/java/tw/personal/privateinbox/ConversationIdentityResolver.kt` | pure resolution algorithm (§4 of the contract) |
| `app/src/main/java/tw/personal/privateinbox/LegacyKeyCodec.kt` | parse/format the historical `pkg\0part\0title` key; pure, JVM-testable |
| `app/src/test/java/tw/personal/privateinbox/ConversationIdentityResolverTest.kt` | JVM RED/GREEN for the resolver (no Android deps) |
| `app/src/test/java/tw/personal/privateinbox/LegacyFingerprintCompatibilityTest.kt` | byte-for-byte golden fixtures for `fingerprintInput` |
| `app/src/test/java/tw/personal/privateinbox/LegacyKeyCodecTest.kt` | key parse/format edge cases |
| `app/src/androidTest/.../ConversationMigrationInstrumentedTest.kt` | real SQLite v5→v6, row/ciphertext preservation |
| `docs/handoff/v046/golden/` | committed golden vectors (JSON) shared by Kotlin + Python |

### Changed (minimal, surgical)
| File | Change |
|---|---|
| `InboxDatabase.kt` | version 5→6; `onCreate` adds the 3 tables; `onUpgrade` adds them + runs backfill; **`messages` untouched**; `save()` unchanged |
| `PrivateNotificationListener.kt` | resolve canonical id via the resolver; pass it alongside the legacy key into `PendingMessage` (new optional field); fingerprint computation **unchanged** |
| `MainActivity.kt` | group the inbox by canonical id instead of raw `conversation_key`; surface AMBIGUOUS |
| `dhc/DhcExporter.kt` | **no change to computeMessageId**; optionally emit canonical id as a NEW versioned field |

### Explicitly NOT changed
`CryptoStore.fingerprintInput`, `messages` schema, any sealed blob,
DHC V1 `computeMessageId` (Kotlin or Python).

## Interfaces

```kotlin
// pure, no Android imports
data class Resolution(
    val conversationId: String,
    val ambiguous: Boolean,
    val matchedBy: AliasType   // SHORTCUT | LEGACY_KEY | MANUAL | NEW
)

interface AliasLookup {
    fun find(pkg: String, type: AliasType, value: String): String?   // -> conversationId
    fun put(pkg: String, type: AliasType, value: String, cid: String, strength: Strength)
    fun newConversationId(): String
}

class ConversationIdentityResolver(private val aliases: AliasLookup) {
    fun resolve(pkg: String, shortcutId: String?, title: String): Resolution
}
```

`AliasLookup` is the seam that makes the resolver testable on the JVM with an
in-memory fake — the negative tests never need a device.

## Migration (v5 → v6)

```
onUpgrade(db, 5, 6):
  1. CREATE TABLE conversations / conversation_aliases / message_conversation
  2. CREATE INDEX … (each guarded by existence check)
  3. BEGIN transaction
       backfill()   // §5 of the contract
     COMMIT
```

- Idempotent: every `CREATE` uses `IF NOT EXISTS`; backfill is keyed on
  "distinct conversation_key not yet aliased", so a re-run inserts nothing new.
- Crash-safe: the backfill runs in ONE transaction, so an interruption leaves
  the schema present with zero aliases, and the next open re-runs it.
- Never touches `messages`.

## RED → GREEN order (tests first, per TDD)

1. **RED** `LegacyKeyCodecTest` — parse/format edge cases (Unicode, empty title,
   NUL in title, over-long title). Fails: codec absent.
2. **RED** `ConversationIdentityResolverTest` — the 10 resolver cases below.
3. **RED** `LegacyFingerprintCompatibilityTest` — golden bytes. This one passes
   immediately **by design** (it pins existing behaviour); it exists to catch a
   future regression, and it is the acceptance proof for I2/I3.
4. Implement `LegacyKeyCodec` → GREEN (1).
5. Implement resolver + in-memory fake `AliasLookup` → GREEN (2).
6. **RED** `ConversationMigrationInstrumentedTest` — v5 fixture DB → open →
   assert rows/ciphertext/rowcount preserved + alias rows created.
7. Implement `ConversationStore` + schema v6 → GREEN (6).
8. Wire the listener + UI grouping; rerun full instrumentation.

## Required tests (from the brief) → where they live

| # | Test | Location |
|---|---|---|
| 1 | same shortcut, title renamed → same conversation | ResolverTest |
| 2 | same pkg/title, different shortcut → different | ResolverTest |
| 3 | different pkg, same title → different | ResolverTest |
| 4 | no shortcut + ambiguous → no guess-merge | ResolverTest |
| 5 | reopen DB → canonical id stable | MigrationInstrumentedTest |
| 6 | legacy fingerprint byte-for-byte unchanged | FingerprintCompatibilityTest |
| 7 | new notification dedups against an old row | MigrationInstrumentedTest |
| 8 | multiple legacy aliases → one canonical | ResolverTest + Migration |
| 9 | merge/unmerge preserves old rows | MigrationInstrumentedTest |
| 10 | migration preserves ciphertext + count | MigrationInstrumentedTest |
| 11 | interrupted migration → no duplicate aliases | MigrationInstrumentedTest |
| 12 | DHC V1 golden message id unchanged | DHC golden fixture test |
| 13 | Kotlin/Python export ids equal | existing python_interop_check |
| 14 | duplicate import → no duplicate rows | existing importer tests |
| 15 | long text / Unicode / odd titles | LegacyKeyCodecTest + ResolverTest |

## Acceptance

- All 15 above green.
- `fingerprintInput` golden bytes identical to the pre-change formula.
- `messages` table byte-identical schema (no new column).
- Full devtest instrumentation green (fresh run, not the previous 139).
- Lint / build / audits green.

## Risk register

| Risk | Mitigation |
|---|---|
| Backfill merges two conversations wrongly | only a SHORTCUT alias may merge; everything else stays separate + AMBIGUOUS |
| Backfill slow on a large DB | one transaction, single pass over DISTINCT conversation_key; measured in the instrumentation test |
| UI grouping change breaks pagination | grouping is a read-side projection; pagination queries unchanged |
| Resolver called on the main thread | resolution is O(1) alias lookups; still invoked from the worker path |
