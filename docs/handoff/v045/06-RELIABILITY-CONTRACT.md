# v0.4.5 Reliability Contract

What the app guarantees, what it does not, and the exact stage names that
carry each meaning. Anything not listed here is not a guarantee.

## 1. Stage semantics (one notification, one trace id)

| Stage | Code | Meaning |
|---|---|---|
| received | `NOTIFICATION_RECEIVED` | the listener's callback ran. **Only** an indication that Android delivered it to us. |
| parsed | `PARSE_SUCCESS` / `EMPTY_NOTIFICATION` | a message list was extracted (or there was nothing to extract). |
| queued | `CaptureMetrics.queued` + T2 `ENQUEUE` | accepted into the bounded work queue. Not durable. |
| prepared | (internal) `PREPARED` | rows staged in memory by the worker. Not durable. |
| persisted | `DB_INSERT_SUCCESS` + T5 `COMMITTED` | the SQLite transaction committed those rows and `dbCommitted`/`dbDuplicated` was incremented **after** the commit call returned. |
| durable failure | `DB_INSERT_TERMINAL` + `dbFailed` | retries exhausted; the rows are dropped and counted. |
| retrying | `DB_INSERT_RETRY_SCHEDULED` | not yet final; neither committed nor failed. |

**Durability begins only at `DB_INSERT_SUCCESS`/`DB_DUPLICATE`.** Everything
before it is recoverable only while the process lives.

## 2. Retry and terminal failure

- `MAX_FLUSH_ATTEMPTS = 3` counts **total attempts**. `PreparedBatch.attempts`
  starts at 0 and is incremented on each failure, so a batch is retried at most
  twice after the first try.
- Backoff between attempts is `RETRY_BACKOFF_MS = 1000`, executed on the single
  worker thread. There is no scheduler and no extra thread.
- Retries run with **no new notification**: the worker re-flushes after the
  backoff, so a fault that clears within roughly two seconds still recovers.
- Success stops retrying immediately (a committed row is never re-queued).
- Only the rows that actually failed are retried; rows that committed in the
  same transaction are never re-sent.
- A row is counted exactly once in a terminal state: `dbCommitted`,
  `dbDuplicated` or `dbFailed`. A retrying row is counted in none of them.
- Terminal failure always emits `DB_INSERT_TERMINAL` with the exception
  **type name** (never content) before being dropped.

## 3. Accounting identity

Per message, the contract is:

```
submitted == committed + duplicated + terminal_failed    (when nothing is pending)
```

While any batch is retrying, the outstanding rows are neither committed nor
failed; they must be reported as **pending** rather than forced into the
identity. Tests that assert the identity must first drain the retry window.

Note on `dbFailed`: it is accumulated with `addAndGet(size)`, i.e. per message,
not per batch, so the identity holds at message granularity.

## 4. Duplicate handling

- Identity is the fingerprint HMAC over package, source timestamp (or the
  per-message discriminator when the source supplies none), conversation key,
  sender+body and discriminator.
- Two notifications carrying the same message produce `DUPLICATE`, not a second
  row.
- Two genuinely different messages with identical text are kept apart by the
  discriminator, which is stable across revisions of the same notification.
- The listener additionally remembers, per notification key, how many times each
  discriminator was already written. That removes the write amplification of a
  growing MessagingStyle history at the source. The memory is dropped when the
  notification is removed and is capped by `SEEN_INDICES_LIMIT`.
- Catch-up on `onListenerConnected()` re-submits the active notifications; the
  fingerprint dedup makes that idempotent.

## 5. Process death (explicitly NOT covered)

`PREPARED`, the work queue and the per-notification memory live **only in the
process**. If the process dies between capture and commit, those messages are
lost, and the platform will not re-deliver a notification it already delivered.

This is a known limitation, not a bug being hidden. The remedy is the
Persistent Intake Journal (DESIGN_ONLY, see 07). No claim is made that the
archive survives process death for in-flight messages; the archive itself is
durable once committed.

## 6. Listener reconnection

- After a rebind, `onListenerConnected()` recovers the notifications still in
  the tray (once, not a poll loop).
- Anything the user dismissed while the service was down is unrecoverable: the
  platform no longer has it.
- Notifications the phone never received cannot be recovered by any means —
  there is no network path by design (no INTERNET permission).

## 7. OEM background restrictions

Aggressive battery managers (common on some Chinese OEM ROMs) may kill the
listener or delay rebinding. Consequences under this contract:
- capture stops while the service is unbound;
- on rebind, tray-recovery applies (section 6), so *currently displayed*
  notifications are caught up;
- messages delivered and dismissed entirely inside the gap are lost.

The app cannot defend against a killed process beyond returning quickly, which
is why the capture path is guarded and does no blocking work on the main thread.

## 8. Queue, ordering, and bounds

- Queue capacity `QUEUE_CAPACITY = 512` notifications; overflow is reported as
  `QUEUE_OVERFLOW` and the submission is rejected (never silently dropped
  without a diagnostic).
- `MAX_PREVIEWS = 500` messages per notification; overflow is reported as
  `MESSAGE_HISTORY_TRUNCATED` and the newest are kept.
- A single writer preserves capture order; retries are re-queued at the front of
  `PREPARED` so a conversation's messages keep their order.
- While a retry backoff is sleeping, the worker is not taking new tasks. The
  delay is bounded (≤ 2 × 1000 ms per batch) and the queue absorbs the arrival
  rate; a sustained burst is bounded by the queue capacity.

## 9. Error and diagnostic boundaries

- No message content is ever logged, persisted in diagnostics, or included in
  exception text. Only package name, stage, code, timestamp, trace id and
  exception **type name**.
- Diagnostics are bounded (ring + capped persisted list) and expose counters
  that distinguish "received", "parsed", "committed", "duplicated", "failed".

## 10. Security boundary

- No `INTERNET` permission; no analytics, tracking or ad SDK.
- No Accessibility Service, no root, no sandbox bypass.
- All content AES-GCM sealed; keys only in the Android Keystore.
- `FLAG_SECURE` unconditional.
- Destructive test helpers (`TestDataIsolation`) refuse to run against the
  production package id — enforced in code, not by convention.
- The debug provider exists only in `app/src/debug/`.
