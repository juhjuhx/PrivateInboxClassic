# v0.4.6 — Architecture As-Is (verified from source)

Read from the actual tree at `bb36c83` + the uncommitted v0.4.5 reliability set.
Every statement cites a file; anything unverified is marked **UNVERIFIED**.

## 1. Capture → Storage data flow

```
Android system
   │  onNotificationPosted(sbn)
   ▼
PrivateNotificationListener (main thread)
   ├─ CaptureDiagnostics.note(NOTIFICATION_RECEIVED)          [diagnostics]
   ├─ LocalSettings.isRecording()          ── no ──▶ RECORDING_DISABLED, return
   ├─ CapturePolicy.allowed(pkg)           ── no ──▶ SOURCE_NOT_ALLOWED, return
   ├─ FLAG_GROUP_SUMMARY                   ── yes ─▶ GROUP_SUMMARY_SKIPPED, return
   ├─ extras == null                       ── yes ─▶ EMPTY_NOTIFICATION, return
   ├─ conversationTitle = EXTRA_CONVERSATION_TITLE ?: EXTRA_TITLE ?: nameFor(pkg)
   ├─ shortcutId        = notification.shortcutId (nullable, blank-rejected)
   ├─ identity          = ConversationIdentity.of(pkg, title, shortcutId, isGroup)
   ├─ kind              = SourceRegistry.classify(...)
   ├─ previews(extras)                     ── empty ─▶ EMPTY_NOTIFICATION, return
   ├─ PARSE_SUCCESS(messages=N)
   ├─ (images on) ephemeral media URI bytes read HERE  ← grant lifetime
   ├─ window = newest MAX_PREVIEWS(500); overflow → MESSAGE_HISTORY_TRUNCATED
   ├─ discriminator per message; drop already-written ones for this sbn key
   ├─ messagesSubmitted += newItems.size
   └─ submit { … } ──▶ workQueue.offer (capacity 512; overflow → QUEUE_OVERFLOW)
                            │
                            ▼  single worker thread
                    build PendingMessage (image decode here, not main)
                    PREPARED.add(PreparedBatch(…))
                    flushPrepared()
                            │
                            ▼
                    InboxDatabase.saveBatches(groups)   ONE transaction
                            │  per row: save() → NEW | DUPLICATE | FAILURE
                            ▼
                    messages table (AES-GCM sealed content/image)
```

## 2. Conversation identity — the confirmed defect

`ConversationIdentity.kt:34-41`:

```kotlin
val key: String get() = buildString {
    append(packageName); append('\u0000')
    append(shortcutId?.takeIf { it.isNotBlank() } ?: title)   // prefers shortcut
    append('\u0000')
    append(title)                                             // ALWAYS appends title
}
```

`key` is the value stored in `messages.conversation_key` and used by the UI to
group boxes. Because `title` is appended **unconditionally**, a renamed contact
(or a source app that changes its notification title) yields a different
`conversation_key` for the same conversation → one chat splits into two boxes.

**Secondary problem:** when `shortcutId` is null the key falls back to `title`
alone, so two distinct groups with the same display name in the same package
collapse into one box. Neither behaviour is currently distinguished from the
other, and there is no `AMBIGUOUS` state.

## 3. Fingerprint contract (must not change)

`InboxDatabase.save()` (~line 229) + `CryptoStore.fingerprintInput()`:

```
conversationPart = conversationKey.ifEmpty { title }
fingerprint      = HMAC-SHA256( keystoreKey,
                     "pkg\0timestamp\0conversationPart\0sender\0body" [+ "\0" + discriminator] )
```

Consequences that constrain the whole design:

1. `conversation_key` is an **input to the dedup identity**. Changing it changes
   the fingerprint of every affected row.
2. Therefore the R1 decision — *alias mapping, never rewrite the legacy key* — is
   not a preference; rewriting would break dedup and DHC identity for all
   existing rows.
3. `fingerprintInput` is a **pure function** with no Android dependency, so the
   byte-for-byte contract can be pinned by a JVM test (no Keystore needed).

## 4. DHC V1 identity (must not change)

`dhc/DhcExporter.kt:74-97`:

```
material = conversationId \0 sourceApplication \0 (sourceTimestamp ?: "") \0 body
messageId = "msg_" + urlsafe_b64( sha256(material)[0..15] ).rstrip('=')
```

`integrations/dhc/protocol/contract.py:150-174` implements the same formula.
The two are already asserted equal by
`integrations/dhc/tests/python_interop_check.py`.

`conversationId` is passed in by the caller — today it is the legacy
`conversation_key`. Any future canonical id must be a **new versioned contract**,
never a silent change to V1.

## 5. Storage schema (version 5, confirmed)

```
messages(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  package_name TEXT NOT NULL,
  received_at INTEGER NOT NULL,
  source_ts INTEGER NOT NULL DEFAULT 0,
  conversation_key TEXT NOT NULL DEFAULT '',
  message_kind TEXT NOT NULL DEFAULT 'UNKNOWN',
  fingerprint BLOB NOT NULL UNIQUE,
  sealed_content BLOB NOT NULL,      -- AES-GCM {title,sender,body,sourceTs}
  sealed_image BLOB
)
indices: received_idx, conversation_idx,
         package_received_idx, conversation_received_idx, kind_received_idx
```

`onUpgrade` is additive + version-disjoint with `columnExists`/
`addColumnIfMissing` guards (P0-A). Version 5 is the current target.

## 6. UI read path

`MainActivity` opens its own `InboxDatabase` on an `io` executor and renders the
inbox from `recentWithState()`. Grouping is done in memory from
`conversation_key` (decrypted title is the display name). There is **no
Conversation table today** — grouping is a pure function of the stored key.

## 7. Ownership / concurrency

| State | Owner | Lifetime |
|---|---|---|
| `workQueue` (512) | listener instance | process |
| `PREPARED` | listener instance | process |
| worker thread | listener instance | until `onDestroy()` |
| `CaptureMetrics` | **process-global object** | process |
| `seenDiscriminators` | listener instance | process |
| SQLite | app | durable |

The worker is single-threaded, which is what makes dedup correct; `flushPrepared`
is only ever reached from that thread.

## 8. Known defects carried into v0.4.6

| # | Defect | File | Severity |
|---|---|---|---|
| D1 | key embeds mutable title | ConversationIdentity.kt:40 | P0 |
| D2 | fingerprint depends on conversation_key | InboxDatabase.kt:229 | P0 (constrains R1) |
| D3 | `readBytes` deadline cannot interrupt a blocking `read()` | ImageCapture.kt:61-77 | P1 |
| D4 | no Conversation/Alias table; no ambiguity state | (absent) | P0 |

## 9. UNVERIFIED
- Whether any real user device carries cert `c876051e…`. The emulator's installed
  builds use `46488e99…` (measured this session). `docs/SIGNING.md` claims the
  project uses key B (`c876051e`) — **that claim contradicts the measured
  artifact** and must be corrected in Phase 4.
- Play/F-Droid policy specifics (no network research performed yet).
