# v0.4.6 — Conversation Identity Contract (R1, APPROVED)

Status: R1 approved by the user ("Conversation Alias Mapping + Legacy Identity
Compatibility"). This document is the normative contract Phase 1 implements.

## 1. Non-negotiable invariants

These may never be violated by any change in this phase:

| I | Invariant |
|---|---|
| I1 | Existing `messages.conversation_key` values are **never rewritten**. |
| I2 | Existing `messages.fingerprint` bytes are **never recomputed**. |
| I3 | `CryptoStore.fingerprintInput()` keeps its exact historical formula. |
| I4 | `sealed_content` / `sealed_image` are never decrypted-then-re-encrypted in bulk. |
| I5 | `received_at`, `source_ts`, `message_discriminator` semantics unchanged. |
| I6 | DHC V1 `computeMessageId()` unchanged (Kotlin **and** Python). |
| I7 | Two conversations in different packages are never merged. |
| I8 | Two same-named conversations without strong identity are never auto-merged. |
| I9 | No merge is irreversible; every alias can be revoked. |

## 2. Identity layers

Four distinct concepts, never conflated:

```
canonical conversation_id   ← new, stable, local, opaque (e.g. "cv_" + uuid)
source strong identity      ← shortcutId (same package only), trusted
legacy conversation_key     ← the historical key; IMMUTABLE, still the dedup input
display title               ← mutable text; display + weak evidence ONLY
```

`conversation_id` is **not** derived from any of the others by hashing — it is
allocated once and then mapped. Deriving it would re-introduce the
"rename changes identity" failure.

## 3. Schema (additive, version 6)

```sql
-- The canonical conversation.
CREATE TABLE conversations (
  conversation_id TEXT PRIMARY KEY,      -- "cv_" + random, never derived
  package_name    TEXT NOT NULL,
  is_group        INTEGER NOT NULL DEFAULT 0,
  display_title   TEXT NOT NULL DEFAULT '',   -- mutable, cosmetic
  created_at      INTEGER NOT NULL,
  merged_into     TEXT                    -- non-null => this id was merged away
);
CREATE INDEX conversations_pkg_idx ON conversations(package_name);

-- How a source signal maps to a canonical conversation.
CREATE TABLE conversation_aliases (
  package_name    TEXT NOT NULL,
  alias_type      TEXT NOT NULL,   -- SHORTCUT | LEGACY_KEY | MANUAL
  alias_value     TEXT NOT NULL,
  conversation_id TEXT NOT NULL,
  strength        TEXT NOT NULL,   -- STRONG | WEAK | MANUAL
  created_at      INTEGER NOT NULL,
  PRIMARY KEY (package_name, alias_type, alias_value)
);
CREATE INDEX conversation_aliases_cid_idx ON conversation_aliases(conversation_id);

-- Row → canonical mapping, kept OUTSIDE messages so messages is untouched.
CREATE TABLE message_conversation (
  message_id      INTEGER PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,
  conversation_id TEXT NOT NULL
);
CREATE INDEX message_conversation_cid_idx ON message_conversation(conversation_id);
```

`messages` itself is **not altered** by this phase (no new column, no rewrite).

## 4. Resolution algorithm

```
resolve(pkg, shortcutId, title) -> Resolution(conversationId, ambiguous)

1. if shortcutId is not blank:
     a. alias (pkg, SHORTCUT, shortcutId) exists  -> its conversation_id   [STRONG]
     b. else: legacy key K = "pkg\0shortcutId\0title" (historical format)
              if a message row with conversation_key == K exists
                 -> adopt that key's canonical conversation (see §5 backfill)
              else -> create a new canonical conversation
              register alias (pkg, SHORTCUT, shortcutId) -> id, STRONG
2. else (no strong identity):
     a. alias (pkg, LEGACY_KEY, K) exists -> its id
     b. else create a NEW canonical conversation, register WEAK alias
        -> resolution.ambiguous = true     ← never merge by title alone
3. never cross package_name boundaries.
```

`title` is written to `conversations.display_title` on every resolution; it is
never part of a lookup key for a new canonical id.

## 5. Legacy backfill (the compatibility core)

For every distinct `messages.conversation_key` (per package) not yet mapped:

```
K = stored conversation_key
if K parses as "pkg\0<part>\0<title>" AND <part> is a plausible shortcutId
   and the package is known to supply shortcut ids:
        -> alias (pkg, SHORTCUT, <part>) -> new conversation_id, STRONG
        -> alias (pkg, LEGACY_KEY, K)   -> same id
else:
        -> alias (pkg, LEGACY_KEY, K)   -> new conversation_id, WEAK
        -> conversation marked AMBIGUOUS (resolution.ambiguous = true)
```

**Critical:** backfill must not merge two different legacy keys into one
conversation. Each distinct `K` gets its own canonical id unless a SHORTCUT
alias proves two keys are the same conversation (that is the *only* merge that
may happen automatically, and it is exactly the D1 fix).

Where a legacy key cannot be unambiguously parsed, the row stays its own
conversation and is flagged AMBIGUOUS — never guessed.

## 6. Dedup compatibility (the hard requirement)

New notifications still compute their fingerprint from the **legacy key**, not
from `conversation_id`:

```
conversationPart = legacyKeyFor(pkg, shortcutId, title)   // historical format
fingerprint      = CryptoStore.fingerprint(pkg, ts, conversationPart, sender\0body, disc)
```

So a message stored before v0.4.6 and the same message re-delivered after it
produce the **identical** fingerprint bytes → recognised as DUPLICATE, not
stored twice. This is the "Legacy Fingerprint Compatibility Resolver": the
canonical id is a *grouping* concept layered on top; the *identity* concept
(fingerprint) is untouched.

Verification: a JVM test pins `fingerprintInput()` output bytes against
committed golden fixtures produced from the pre-change formula.

## 7. Merge / unmerge

- **Merge** (manual): set `conversations.merged_into = target` for the loser,
  and repoint `message_conversation.conversation_id` rows to the target in one
  transaction. `messages` rows untouched.
- **Unmerge**: clear `merged_into` and restore the original mapping, which is
  recoverable because merge records the loser id and the original per-message
  mapping is derived from `message_conversation` at merge time (a
  `message_conversation_undo` snapshot table is written before the repoint).
- Neither operation touches `fingerprint`, `conversation_key`, or any sealed blob.
- Automatic merge is **never** performed by title or text similarity.

## 8. Ambiguity reporting

`Resolution.ambiguous = true` whenever the identity rests on WEAK evidence.
The UI must be able to surface "this conversation's identity is uncertain" and
offer manual merge/unmerge. The system never presents a weak grouping as
certain.

## 9. What this contract explicitly does NOT do

- Does not rewrite any existing row.
- Does not change DHC V1 ids.
- Does not merge same-named groups automatically.
- Does not derive `conversation_id` from a hash of mutable data.
- Does not claim process-death durability for in-flight batches (unchanged
  limitation).
