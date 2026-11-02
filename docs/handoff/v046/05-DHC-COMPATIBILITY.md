# v0.4.6 — DHC Compatibility

DHC V1 identity must not change. This document pins the formula, lists the
golden fixtures, and states what a canonical conversation id may and may not do.

## 1. V1 message id (frozen)

Kotlin — `dhc/DhcExporter.kt:74-97`:

```
material  = conversationId \0 sourceApplication \0 (sourceTimestamp?.toString() ?: "") \0 body
messageId = "msg_" + urlsafe_b64_nopad( sha256(material)[0..14] )
```

Python — `integrations/dhc/protocol/contract.py:150-174`:

```
material = "\0".join([conversation_id, source_application,
                      str(source_timestamp) if source_timestamp is not None else "", body])
message_id = "msg_" + base64.urlsafe_b64encode(hashlib.sha256(material.encode()).digest()[:15]).decode().rstrip("=")
```

Both take the first **15 bytes** and strip padding. `Base64.NO_WRAP` in Kotlin is
load-bearing: the default Android encoder appends a newline, which historically
made the two sides differ by one character.

## 2. Invariants

| I | Invariant |
|---|---|
| V1-1 | `computeMessageId` formula and inputs unchanged in Kotlin. |
| V1-2 | Same in Python. |
| V1-3 | `conversationId` passed to V1 is still the **legacy conversation_key**, never the new canonical id. |
| V1-4 | The export bundle's `message_id` for an already-exported message is byte-identical before and after v0.4.6. |
| V1-5 | A canonical id, if exported at all, goes into a NEW versioned field; it never replaces `message_id` or `conversation_id`. |

## 3. Why the canonical id must not enter V1

`conversationId` is an input to the hash. If v0.4.6 started passing the canonical
id there, every `message_id` would change, so:

- a re-import of an old bundle would not match any row → duplicates;
- the Python importer's idempotency (`message_id PRIMARY KEY`) would be defeated;
- a multi-device merge would see the same message as two different messages.

Therefore: V1 keeps the legacy key. The canonical id is a **grouping** concept,
layered on top, and any export of it is additive and versioned.

## 4. Golden fixtures

Committed under `docs/handoff/v046/golden/` as JSON, generated from the
**pre-change** implementations, then asserted by both sides:

```json
{
  "vectors": [
    {
      "name": "ascii-basic",
      "conversation_id": "org.telegram.messenger\u0000shortcut-1\u0000Alice",
      "source_application": "org.telegram.messenger",
      "source_timestamp": 1700000000000,
      "body": "hello",
      "expected_message_id": "msg_…"
    },
    { "name": "null-timestamp", … },
    { "name": "unicode-cjk",    "body": "你好，世界" },
    { "name": "long-text",      "body": "<10k chars>" },
    { "name": "embedded-nul",   "body": "a\u0000b" },
    { "name": "empty-body",     "body": "" },
    { "name": "emoji",          "body": "👍🏽" }
  ]
}
```

Consumers:
- Kotlin: `DhcV1GoldenFixtureTest` reads the JSON and asserts
  `DhcExporter.computeMessageId(...)` equals `expected_message_id`.
- Python: `integrations/dhc/tests/python_interop_check.py` extended to read the
  same file and assert `compute_message_id(...)` equality.

One file, two languages, so drift on either side fails the build.

## 5. Fixture generation rule

The `expected_message_id` values are produced by running the **current,
unmodified** implementations once and committing the output. They are a
regression pin, not a new specification. If a future change needs a different
id, it requires a new versioned contract (e.g. `msg2_…`), never an edit to V1.

## 6. Import dedup across the change

- Old bundle imported by v0.4.6: ids match, importer's `message_id PRIMARY KEY`
  dedups as before.
- New bundle (v0.4.6) imported by old code: ids are the same V1 ids, so the old
  importer still dedups. The extra canonical field is ignored by old readers
  (additive).
- Re-import twice: idempotent in both directions.

## 7. What is NOT claimed
- No cross-language test was executed in this phase (it needs the Python
  environment); the fixtures and the test hooks are specified, execution is part
  of the Phase 1 verification gate.
- The DHC bundle's **encryption** (password-derived AES-GCM) is unchanged and out
  of scope for this phase.
