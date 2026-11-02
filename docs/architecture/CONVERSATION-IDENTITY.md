# Architecture — Conversation Identity (v0.4.6)

Normative documents: `docs/handoff/v046/03-CONVERSATION-IDENTITY-CONTRACT.md`
(R1, user-approved) and `docs/handoff/v046/04-MIGRATION-CONTRACT.md`. This page
is the engineering summary of what is actually implemented.

## Data model (schema v6)

```
messages                     UNCHANGED v5 rows; conversation_key immutable
conversations                conversation_id (cv_uuid), package, title, merged_into
conversation_aliases         (pkg, type, value) → conversation_id
                             type: SHORTCUT | LEGACY_KEY | MANUAL
                             strength: STRONG | WEAK (compatibility view)
                             provenance: full taxonomy, NULL = AMBIGUOUS
message_conversation         message_id → conversation_id
conversation_merge_log       batch, source, target, undone
conversation_alias_undo      batch → original alias owner
message_conversation_undo    batch → original message link owner
```

## Resolution flow

```
notification (pkg, shortcutId?, title)
  → legacyKey = LegacyKeyCodec.format(pkg, shortcutId, title)   [IMMUTABLE format]
  → resolver.resolve:
      shortcut present?
        yes: alias(SHORTCUT) hit  → same conversation (+register current legacy key)
             alias miss           → adopt by legacy alias or allocate; register both
                                   provenance = PLATFORM_CONFIRMED
        no:  alias(LEGACY_KEY) hit → same conversation, AMBIGUOUS/TITLE_ONLY
             miss                  → allocate, AMBIGUOUS/TITLE_ONLY
  → fingerprint STILL computed from legacyKey   [dedup unchanged]
```

## Production integration points

| Layer | Hook |
|---|---|
| Listener | `flushPrepared()` → `linkBatchToCanonicalConversation()` per committed batch (worker thread, batch context); `reconcilePendingLinks()` once per process, guarded by an unlinked-row count |
| UI | `MainActivity.loadEntries()` → `ConversationStore.linkAllMessages()` (reconcile) → `refreshCanonicalKeys()` → `threadKey()` = canonical id or legacy key fallback |
| Merge/Unmerge | `ConversationStore.merge()/unmerge()` with batch journals; validation refuses self/cross-package/cycle/unknown |
| Schema repair | `onOpen` one-shot per process, read-only probe first; failures recorded as `SCHEMA_REPAIR_FAILED` |

## Invariants (all test-pinned)
1. `messages` rows, fingerprints, sealed blobs: byte-identical through migration, merge, unmerge.
2. Dedup and DHC V1 identity use the legacy key only; `cv_` never enters them.
3. Title-only evidence is never presented as certain (AMBIGUOUS persists).
4. Weaker evidence never overwrites a stronger alias owner (`CONFLICT`).
5. Cross-package conversations never merge.

## Test map
| Suite | Coverage |
|---|---|
| ConversationMigrationV5InstrumentedTest (7) | real v5 fixture, rollback injection, same-device fingerprint |
| ExistingV6CompatibilityInstrumentedTest (6) | pre-G1 v6 repair, reopen stability |
| ConversationMergeInstrumentedTest (13) | merge/unmerge, rejections, restart, conflict |
| AliasProvenanceInstrumentedTest (8) | taxonomy, persistence, weaker-never-overwrites |
| ConversationReadModelInstrumentedTest (11) | SQL grouping, page boundaries, NUL keys |
| CapturePipelineIntegrationInstrumentedTest (9) | listener → link → reconcile |
| ConversationUiLifecycleInstrumentedTest (6) | real Activity, recreate, merge UI |
| ConversationIntegrityInstrumentedTest (8) | PRAGMA + app-level audits |
| CanonicalPerformanceInstrumentedTest (4) | measured costs |
| Qa01ReadModelAndUiTest (7) | search, same-title, view hierarchy, relaunch |
