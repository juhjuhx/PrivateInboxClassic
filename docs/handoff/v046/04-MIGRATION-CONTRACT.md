# v0.4.6 — Migration Contract (v5 → v6)

The migration may not lose or mutate any user data. This document states exactly
what is allowed, what is forbidden, and how it is proven.

## 1. What changes

| Object | v5 | v6 |
|---|---|---|
| `messages` | exists | **unchanged** (same columns, same rows, same bytes) |
| `conversations` | absent | new |
| `conversation_aliases` | absent | new |
| `message_conversation` | absent | new |
| `SQLiteOpenHelper` version | 5 | 6 |

No column is added to `messages`. No row in `messages` is updated or deleted.

## 2. Forbidden operations (hard)

- `ALTER TABLE messages` (any form)
- `UPDATE messages SET …`
- `DELETE FROM messages`
- recomputing `fingerprint`
- decrypting + re-encrypting any `sealed_content` / `sealed_image`
- dropping or recreating any index on `messages`
- `DROP TABLE` of anything user-visible

Any of these in the migration is a defect, not a trade-off.

## 3. Allowed operations

- `CREATE TABLE IF NOT EXISTS` for the three new tables
- `CREATE INDEX IF NOT EXISTS` for their indices
- `INSERT` into the new tables (alias backfill)
- a single transaction wrapping the backfill

## 4. Idempotency

The migration must be safe to run twice (crash between the DDL and the commit,
or a re-open). Guarantees:

1. Every DDL statement is `IF NOT EXISTS`.
2. Backfill selects only `conversation_key` values that have **no**
   `LEGACY_KEY` alias yet, so a second pass inserts nothing.
3. Alias primary key is `(package_name, alias_type, alias_value)`, so even a
   racing insert cannot duplicate a mapping.

## 5. Crash / interruption

| Interruption point | Result after restart |
|---|---|
| during DDL | tables partially created; next open re-runs `IF NOT EXISTS` |
| during backfill | transaction rolled back; **zero** aliases; next open re-runs backfill |
| after commit | nothing to do; backfill finds no unmapped keys |

There is never a state where a `messages` row is lost, and never a state where
the same legacy key maps to two conversations.

## 6. Compatibility — what is and is NOT claimed

These five notions are different, and conflating them has produced wrong claims
in the past. Each is stated with its actual verification status.

### 6.1 Schema forward compatibility — VERIFIED (test)
A database written by the current version can be read by the current version,
and older `messages` rows survive every additive migration byte for byte.
Evidence: `ConversationMigrationV5InstrumentedTest` (real v5 fixture,
`onUpgrade(5,6)`, full-column byte comparison).

### 6.2 APK upgrade (vN → vN+1) — VERIFIED (test)
Opening an older database with newer code upgrades it. Evidence: the same suite,
plus `ExistingV6CompatibilityInstrumentedTest` (a pre-G1 v6 database gains the
merge-journal tables through `onOpen`, keeps its rows, aliases and canonical
links, and merge/unmerge works afterwards).

### 6.3 APK downgrade — **UNSUPPORTED, NOT TESTED**
Android's `SQLiteOpenHelper.onDowngrade()` **throws by default**, so installing
an older APK over a newer one fails at first open. The additive design means the
DATA would still be readable by the newer code, but this project does NOT claim,
and has NOT tested, that an old APK can open a new database. Any downgrade flow
must go through the (Phase 2, DESIGN_ONLY) encrypted backup/restore.

**Never present "the schema is additive" as "downgrade is safe". They are
different claims; only the first is true and tested.**

### 6.4 Signature compatibility — measured, debug-only
`adb install -r` succeeds only when the installed certificate matches. Current
builds are signed with the project debug key
(`46488e99373f3c1af0c630642f2d9ec459b8db671391f3ad817efb3aae99da94`, measured
with `apksigner verify --print-certs`). A signature change requires an
uninstall, which destroys user data — the release scripts refuse it
(`scripts/release/install-artifact.sh`).

### 6.5 Version-code compatibility
Android requires a strictly higher `versionCode` to upgrade in place. Current
builds are `versionCode 10`; a v0.4.6 release must bump it. Not yet done.

### 6.6 Rollback of a migration step
A failed `onUpgrade` rolls back completely (transactional), proven by
`realV5ToV6_throwingInsideTheMigrationRollsEverythingBack`: after an injected
failure `user_version` is still 5, no conversation tables exist, and every row is
byte-identical. Rolling back the APK itself remains 6.3: UNSUPPORTED.

## 7.## 7. Re-run behaviour

`onUpgrade` runs once per version step. If the app is reinstalled over itself
with the same version, `onUpgrade` does not run and the tables persist — which
is correct, because the aliases are still valid.

If a future v7 adds more, the v6 step remains in the chain (version-disjoint
steps, the same discipline as the P0-A fix).

## 8. Proof (tests)

`ConversationMigrationInstrumentedTest` (real SQLite on the devtest package):

1. Build a **v5 fixture** database with N rows across several
   `conversation_key` values, including sealed blobs and fingerprints.
2. Record: row count, every `fingerprint` byte array, every `sealed_content`
   byte array, every `conversation_key`.
3. Open with v6 code (triggers `onUpgrade`).
4. Assert:
   - row count identical;
   - every `fingerprint` byte-for-byte identical;
   - every `sealed_content` byte-for-byte identical;
   - every `conversation_key` identical;
   - `conversations` / `conversation_aliases` populated;
   - a second open does not duplicate aliases.
5. Reopen again and assert canonical ids are stable.

Plus `LegacyFingerprintCompatibilityTest` (JVM): pins
`CryptoStore.fingerprintInput()` output bytes against committed golden vectors,
so a future edit to the formula fails immediately.

## 9. UNVERIFIED / open
- Actual backfill duration on a very large real database is not measured; the
  instrumentation test uses a synthetic fixture. This is recorded as a known
  gap, not claimed as benchmarked.
- SQLite `ON DELETE CASCADE` on `message_conversation` requires
  `PRAGMA foreign_keys=ON`; the implementation must enable it per connection or
  handle cascade explicitly. To be resolved in implementation, not assumed.
