# v0.4.5 Architecture

Data flow, ownership and failure paths, as implemented in the source at HEAD
plus the reviewed working-tree changes. Every claim below is traceable to a
file; where behaviour is a known limit it says so.

## 1. Component map

| Component | File | Owns |
|---|---|---|
| NotificationListenerService | `PrivateNotificationListener.kt` | the worker thread, `workQueue`, `PREPARED`, `seenDiscriminators` |
| Parser (previews) | `PrivateNotificationListener.previews()` | extracting message text/sender/time/media from a `Bundle` |
| Policy | `CapturePolicy.kt` → `SourceRegistry.kt` | which package is captured, normalisation, classification |
| Conversation identity | `ConversationIdentity.kt` | stable `conversation_key` |
| Storage | `InboxDatabase.kt` | schema, migrations, `save`/`saveBatches`/`clear`/paging |
| Crypto | `CryptoStore.kt` | AES-GCM sealing, fingerprints, keystore access |
| Image | `ImageCapture.kt` | preview decode/downscale, URI byte read |
| Diagnostics | `CaptureDiagnostics.kt` | ring buffer, persisted codes, counters, connectivity flag |
| Metrics | `CaptureMetrics.kt` | **process-global** atomic counters + histograms |
| Latency | `PipelineLatency.kt` | T0..T5 stage samples |
| UI | `MainActivity.kt` | inbox list, settings, export, delete-all |
| Export / DHC | `DhcExport*.kt`, `integrations/` | encrypted export contract + Python connector fixture |
| Test bridge | `app/src/debug/.../DebugSettingsProvider.kt`, `TestDataIsolation.kt` | debug-only; **not in release** |

## 2. Capture path (main thread)

```
NotificationListenerService.onNotificationPosted(sbn)
  └─ capture(appContext, sbn)                           [MAIN THREAD]
       1 NOTIFICATION_RECEIVED            diagnostic
       2 LocalSettings.isRecording()      no  -> RECORDING_DISABLED, return
       3 CapturePolicy.allowed(pkg)       no  -> SOURCE_NOT_ALLOWED, return
       4 FLAG_GROUP_SUMMARY               yes -> GROUP_SUMMARY_SKIPPED, return
       5 extras == null                   yes -> EMPTY_NOTIFICATION, return
       6 title/shortcut/identity/kind
       7 previews(extras)                 empty -> EMPTY_NOTIFICATION, return
       8 PARSE_SUCCESS (messages=N)
       9 ephemeral media URI bytes read HERE (grant may be revoked after return)
      10 window = newest MAX_PREVIEWS (500), overflow -> MESSAGE_HISTORY_TRUNCATED
      11 discriminator per message; drop the ones already written for this key
      12 messagesSubmitted += newItems.size
      13 submit { ... }  -> workQueue.offer        [HANDOFF POINT]
```

Everything up to step 13 is synchronous. Step 9 is the only work that *must*
happen here (URI grant lifetime); decode and DB work do not.

## 3. Worker path (single writer thread)

```
worker loop:
  workQueue.take().run()          one capture task
  drain poll() until empty        non-blocking, so a trickle never waits
  flushPrepared()                 write everything PREPARED in ONE transaction

capture task body:
  re-check recording + policy (settings may have changed while queued)
  dequeued++, queueWait sample, WORKER_START
  build PendingMessage list (image decode happens here, not on main)
  PREPARED.add(PreparedBatch(...))
```

A single writer is what makes the dedup key reliable: the fingerprint check and
the insert are never racing another writer in-process.

## 4. Batch lifecycle and retry state machine

```
PreparedBatch(messages, packageName, enqueuedAt, t0, startTs, trace, context,
              attempts)
```

```
flushPrepared()
  drain PREPARED (all batches)
  db = InboxDatabase(app)
  results = saveBatches(groups)              ONE transaction, per-row results
  per batch:
    failedRows = rows whose result is FAILURE
    failedRows empty:
        COMMITTED latency sample (once for the batch)
        for each row: NEW->dbCommitted++, DUPLICATE->dbDuplicated++,
                      each with DB_INSERT_SUCCESS / DB_DUPLICATE
    failedRows non-empty:
        NEW/DUPLICATE rows already counted above (they really committed)
        attempts+1 < MAX_FLUSH_ATTEMPTS(3)
            -> requeue ONLY the failed rows, DB_INSERT_RETRY_SCHEDULED
        else
            -> dbFailed += failedRows.size, DB_INSERT_TERMINAL, batch dropped
    finally: db.close()
  if anything was requeued:
      sleep RETRY_BACKOFF_MS (1000) on THIS worker
      flushPrepared()          recursion depth bounded by MAX_FLUSH_ATTEMPTS
```

Failure path for an exception before per-row accounting (open failure, an
unexpected throwable): every drained batch is requeued at the FRONT of PREPARED
(attempts+1) or reported TERMINAL, then re-flushed after the same backoff.

Properties:
- Success is the only state that releases a row for good.
- The backoff is on the worker, so retries complete with **no new notification**.
- Recursion is bounded (≤3), no busy loop, no extra thread/scheduler.
- Retries keep capture order: rows are carried in original order and requeued at
  the FRONT of PREPARED.

## 5. Database

```sql
messages(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  package_name TEXT NOT NULL,
  received_at INTEGER NOT NULL,        -- capture time (this device)
  source_ts INTEGER NOT NULL DEFAULT 0,-- sender's time when supplied, else 0
  conversation_key TEXT NOT NULL DEFAULT '',
  message_kind TEXT NOT NULL DEFAULT 'UNKNOWN',
  fingerprint BLOB NOT NULL UNIQUE,    -- HMAC identity, the dedup key
  sealed_content BLOB NOT NULL,        -- AES-GCM {title,sender,body,sourceTs}
  sealed_image BLOB
)
indices: received_idx, conversation_idx,
         package_received_idx, conversation_received_idx, kind_received_idx
```

- `onCreate` builds the current schema; `onUpgrade` is **additive and
  version-disjoint** with `columnExists`/`addColumnIfMissing` guards, so a
  v1 database that historically hit `source_ts` twice no longer crashes
  (P0-A, committed in bb36c83).
- `saveBatches(groups)` opens one transaction for every group. Each row's
  exception is caught inside `save()` and reported as `SaveResult.FAILURE`
  (`CONFLICT_IGNORE` covers the duplicate case as `DUPLICATE`); the transaction
  still commits the rows that succeeded. **A batch can therefore be mixed.**
- `clear()` deletes all rows then truncates the WAL, with a bounded retry
  (10 × 250 ms) on `SQLITE_BUSY` because it races the capture worker.

## 6. Crypto and privacy boundary

- All content and image bytes are sealed with AES-GCM via `CryptoStore`; keys
  live only in the Android Keystore. No key is hard-coded.
- The fingerprint is an HMAC over `(pkg, ts, conversation, sender\0body,
  discriminator)` — it is not reversible and leaks no content.
- Nothing logs message content: diagnostics carry package, stage, code,
  timestamps and exception **type names** only.
- `FLAG_SECURE` is unconditional; no INTERNET permission; no analytics SDK.

## 7. Connectivity and catch-up

`onListenerConnected()` calls `CaptureDiagnostics.markConnected` and then reads
`activeNotifications` once, submitting each through the normal `capture()` path.
Dedup makes this idempotent: an already-stored message is recognised by
fingerprint and reported DUPLICATE, not stored twice.

`onNotificationRemoved(...)` records the removal reason class and drops the
per-notification memory entry. It deliberately does **not** clear the
connectivity flag (a removal says nothing about the binding); the previous code
did, which corrupted the connectivity evidence.

## 8. UI read path

`MainActivity` opens its own `InboxDatabase` on an `io` executor, pages through
`recentWithState()` (encrypted rows decrypted on read), and reports key state so
undecryptable rows are surfaced rather than silently dropped. Delete-all runs on
the same executor, never on the main thread.

## 9. Error paths summary

| Failure | Where handled | User/诊断 visible as |
|---|---|---|
| transient SQLite failure | per-row FAILURE → retry w/ backoff | `DB_INSERT_RETRY_SCHEDULED` |
| persistent failure | attempts exhausted | `DB_INSERT_TERMINAL`, `dbFailed` |
| DB open failure | catch(Throwable) path | `DB_OPEN_FAILED`, retry |
| malformed notification | guarded capture | `EMPTY_NOTIFICATION` etc., listener survives |
| worker task throws | per-task try/catch | `WORKER_FAILURE`, worker keeps running |
| queue full | `offer` false | `QUEUE_OVERFLOW` |
| keystore row unreadable | read path | key state reported, row kept until purge |
| process death | **not handled** | in-memory batches lost (see 06 contract) |
