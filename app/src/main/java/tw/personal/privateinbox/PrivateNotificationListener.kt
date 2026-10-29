package tw.personal.privateinbox

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ArrayBlockingQueue

/** Only stores user-selected sources. No network or chat-account privileges. */
class PrivateNotificationListener : NotificationListenerService() {
    /**
     * The maximum number of messages taken from ONE notification.
     *
     * This is a safety bound, not a message limit: a single group notification
     * can legitimately carry hundreds of messages, and every one of them has to
     * be captured. The bound exists only so a pathological notification cannot
     * exhaust memory. When it IS reached, the number of dropped messages is
     * recorded as a diagnostic instead of being lost silently.
     */
    companion object {
        const val MAX_PREVIEWS = 500
        const val QUEUE_CAPACITY = 512
        const val MAX_FLUSH_ATTEMPTS = 3
        const val SEEN_INDICES_LIMIT = 4_000

        /**
         * Delay in milliseconds before a failed batch is retried.
         *
         * An immediate recursive re-flush burns the whole retry budget within
         * milliseconds, which defeats the retry machine twice: a transient
         * fault (SQLite busy for a second, a brief disk stall) outlives all
         * attempts, and a test (or an operator) has no window to clear the
         * fault before the batch is reported TERMINAL and dropped. The delay
         * is bounded and small; it does not change the no-new-notifications
         * completion property, and it does not add a thread or a scheduler.
         */
        const val RETRY_BACKOFF_MS = 1000L
    }

    /**
     * The bounded work queue.
     *
     * A single writer keeps the SQLite writes ordered, which is what the dedup
     * key depends on. But an UNBOUNDED queue turns a burst into an unbounded
     * backlog: the listener falls further and further behind the source, and the
     * lag is invisible. The queue is therefore bounded, and an overflow is
     * REPORTED rather than silently dropped.
     *
     * The bound is generous (512 notifications) because a notification may
     * carry hundreds of messages, but it is a bound: a sustained flood must
     * not be able to grow the heap without limit.
     */
    private val workQueue = ArrayBlockingQueue<Runnable>(QUEUE_CAPACITY)

    /**
     * The message indices already written, per notification.
     *
     * A group notification is delivered as a growing history: revision N
     * presents every message so far. Only the newest one is new. This map is
     * the memory of which indices have been written, so the write
     * amplification is removed at the SOURCE instead of being absorbed by the
     * database.
     *
     * The key is the notification key, which is unique per posted notification
     * and is REMOVED when the notification is dismissed, so the map does not
     * grow without bound in normal operation. The size limit is a second
     * safety net for a source that never dismisses its notifications.
     */
    private val seenDiscriminators =
        java.util.concurrent.ConcurrentHashMap<String, HashMap<String, Int>>()

    /**
     * The worker that drains the queue.
     *
     * It runs one task at a time so the database writes stay ordered.
     */
    /**
     * The messages one notification contributed, and the context needed to
     * report on them.
     *
     * The worker collects these from the queue and writes them together, in
     * ONE transaction.
     */
    private class PreparedBatch(
        val messages: List<InboxDatabase.PendingMessage>,
        val packageName: String,
        val enqueuedAt: Long,
        val t0: Long,
        val startTs: Long,
        val trace: String,
        /**
         * The context the capture ran with. It is carried with the batch
         * because the service's own context is not always usable.
         */
        val context: android.content.Context,
        /** How many failed flushes this batch has survived. 0 = first write. */
        val attempts: Int = 0
    ) {
        /** A copy of this batch carrying an incremented retry counter. */
        fun withAttempt(next: Int): PreparedBatch =
            PreparedBatch(messages, packageName, enqueuedAt, t0, startTs, trace, context, next)

        /**
         * A copy carrying only the rows that still need writing, with the
         * retry counter advanced.
         *
         * Rows fail INDIVIDUALLY (save() reports per-row FAILURE while the
         * transaction commits its successful rows), so a retry must carry the
         * failed subset only. Re-sending the whole batch would re-insert rows
         * that already committed and turn them into a flood of DUPLICATE work.
         */
        fun withMessages(subset: List<tw.personal.privateinbox.InboxDatabase.PendingMessage>,
                         next: Int): PreparedBatch =
            PreparedBatch(subset, packageName, enqueuedAt, t0, startTs, trace, context, next)
    }

    /**
     * The batches that are prepared but not yet written, owned by THIS
     * listener instance.
     *
     * Each instance owns its queue, its prepared list and its worker, so an
     * instance that the system did not construct (the catch-up path and the
     * tests construct their own) still has a worker that drains what that same
     * instance prepared. Nothing crosses instances, so no lock is needed
     * between them; the lock below only orders the queue task that appends
     * against the worker that drains.
     *
     * The batch carries the context it was prepared with. A system-created
     * service instance always has an applicationContext, but an instance built
     * by a caller may not, and the write must not depend on it.
     */
    private val PREPARED = ArrayList<PreparedBatch>()

    /**
     * The batches that are prepared but not yet written.
     *
     * The queue task PREPARES the rows and appends them here; the worker
     * writes them in one transaction. That split is what makes the batching
     * possible: the transaction can span several notifications.
     */

    /**
     * The worker thread.
     *
     * It runs one task at a time so the database writes stay ordered, but it
     * DRAINS the queue first: several notifications' worth of messages are
     * written in ONE transaction.
     *
     * The measurement that motivated it: every transaction costs an fsync,
     * and the burst benchmark measured 59 ms per insert at the median. A
     * 500-message burst therefore spent about 30 SECONDS waiting for the
     * disk, and that wait IS the queue wait, because the single writer could
     * not drain the queue.
     */
    private val worker = Thread({
        while (true) {
            try {
                workQueue.take().run()
                // Drain whatever else is already waiting. The drain is
                // NON-BLOCKING: a low-traffic notification must never wait
                // for a batch to fill.
                while (true) {
                    val next = workQueue.poll() ?: break
                    next.run()
                }
                flushPrepared()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return@Thread
            } catch (e: Throwable) {
                // One malformed notification must never kill the worker: a dead
                // worker silently stops ALL capture. The failure type is
                // recorded, never the content.
                runCatching {
                    val app = applicationContext
                    if (app != null) {
                        CaptureDiagnostics.note(
                            app, null, "WORKER", CaptureDiagnostics.WORKER_FAILURE,
                            e.javaClass.simpleName
                        )
                    }
                }
            }
        }
    }, "capture-worker").apply {
        isDaemon = true
        start()
    }

    /**
     * The canonical-conversation store.
     *
     * Created lazily on the WORKER thread (never in the notification callback)
     * and reused, so it opens one connection pool instead of one per flush.
     * It lives on the service instance: if the service dies, the next open
     * repairs the schema and the reconcile pass recovers any links that were
     * still pending.
     */
    private var canonicalStore: ConversationStore? = null

    /** Guard so the pending-link reconcile runs once per process. */
    private val reconciled = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun canonicalStore(context: android.content.Context): ConversationStore =
        canonicalStore ?: ConversationStore(context).also { canonicalStore = it }

    /** Release the canonical store; called from [onDestroy]. */
    private fun closeCanonicalStore() {
        runCatching { canonicalStore?.close() }
        canonicalStore = null
    }

    /**
     * Take the prepared batches and write them in ONE transaction.
     *
     * One database handle is opened for the whole group, and one transaction
     * covers every row of every batch. The per-row results are reported
     * exactly as they were before, so the accounting is unchanged.
     */
    private fun flushPrepared() {
        val batches: List<PreparedBatch>
        synchronized(PREPARED) {
            if (PREPARED.isEmpty()) return
            batches = ArrayList(PREPARED)
            PREPARED.clear()
        }
        // The context comes from the BATCH, not from the service.
        //
        // The service's own applicationContext is null in an instance that the
        // system did not construct -- which is what the catch-up path and the
        // tests do. Using it here silently discarded the prepared rows, and
        // the message simply never appeared.
        val app = batches.firstOrNull()?.context ?: applicationContext ?: return
        var db: InboxDatabase? = null
        // P0-B (v0.4.5 reliability review): the batches were drained from
        // PREPARED before the write. If the write failed -- SQLite busy, disk
        // full, a rolled-back transaction, a failed open -- the rows were gone
        // for good: the queue had already released them, and nothing re-queued
        // them. The fix keeps a BOUNDED retry: every batch that did not commit
        // is put back on PREPARED with attempt+1, and only a batch that has
        // exhausted MAX_FLUSH_ATTEMPTS is reported TERMINAL and dropped. A
        // successful commit is the ONLY state that releases a batch for good;
        // this is not the durable journal (a process death between capture and
        // commit still loses the in-memory batches), but a failed flush no
        // longer converts a recoverable error into silent data loss.
        val toRetry = ArrayList<PreparedBatch>()
        try {
            // One-time reconcile of links left pending by an earlier process.
            // Runs on the worker, using the BATCH's context (a system-bound
            // listener has applicationContext, but a test-driven instance does
            // not — the batch context works for both). It is guarded by "there
            // really are unlinked rows" so a healthy database pays nothing.
            db = InboxDatabase(app)
            if (reconciled.compareAndSet(false, true)) {
                val unlinked = db!!.readableDatabase.rawQuery(
                    "SELECT count(*) FROM messages m LEFT JOIN message_conversation l " +
                        "ON l.message_id = m.id WHERE l.message_id IS NULL",
                    null
                ).use { c -> c.moveToFirst(); c.getInt(0) }
                if (unlinked > 0) {
                    CaptureDiagnostics.note(
                        app, null, "IDENTITY",
                        CaptureDiagnostics.LINK_RECONCILE_PENDING, "count=$unlinked"
                    )
                    reconcilePendingLinks(app)
                }
            }
            val dbStart = System.nanoTime()
            // ONE transaction for every drained group. A transaction per
            // group would be no better than a transaction per notification:
            // the fsync is the cost, and it is paid once per commit.
            val groups = batches.map { it.messages }.filter { it.isNotEmpty() }
            var saveBatchesThrew: Throwable? = null
            val allResults = if (groups.isEmpty()) emptyList() else
                runCatching { db!!.saveBatches(groups) }
                    .getOrElse { e ->
                        saveBatchesThrew = e
                        groups.map { g -> List(g.size) { InboxDatabase.SaveResult.FAILURE(e) } }
                    }
            // The duration is recorded once per MESSAGE, so the sample count
            // still matches the number of rows written. The whole flush is
            // attributed to each message, which is the honest reading when
            // they share one transaction: they shared one fsync.
            val flushMillis = (System.nanoTime() - dbStart) / 1_000_000
            val messageCount = groups.sumOf { it.size }.coerceAtLeast(1)
            repeat(messageCount) {
                CaptureMetrics.dbDuration.record(flushMillis)
            }
            var groupIndex = 0
            for (batch in batches) {
                if (batch.messages.isEmpty()) continue
                val results = allResults.getOrElse(groupIndex) { emptyList() }
                groupIndex++
                // A batch is not all-or-nothing at the ROW level.
                //
                // saveBatches() runs one transaction, but each row's failure is
                // caught inside save() and reported as SaveResult.FAILURE while
                // the transaction still commits the rows that succeeded
                // (verified against real SQLite: row 1 aborted, rows 2 and 3
                // committed). So a batch can legitimately come back as
                // [NEW, FAILURE, NEW].
                //
                // A previous version retried only when EVERY row failed, and
                // silently dropped the failed rows of a MIXED batch while
                // counting them as failed: a recoverable error turned into
                // permanent loss for exactly those messages. The failed rows
                // are now retried on their own, and the successful rows are
                // accounted for (and get their COMMITTED sample) immediately.
                val failedIndices = results.indices.filter {
                    results[it] is InboxDatabase.SaveResult.FAILURE
                }
                if (failedIndices.isEmpty()) {
                    // T5: the SQLite commit is done. The difference from T0 is
                    // the DURABLE latency: how long it took for the message to
                    // be on disk. Recorded ONLY when the whole batch committed.
                    PipelineLatency.record(
                        PipelineLatency.Stage.COMMITTED,
                        android.os.SystemClock.elapsedRealtimeNanos() - batch.t0
                    )
                }
                for (i in results.indices) {
                    when (val outcome = results[i]) {
                        is InboxDatabase.SaveResult.NEW -> {
                            CaptureMetrics.dbCommitted.incrementAndGet()
                            CaptureMetrics.endToEnd.record(
                                (System.nanoTime() - batch.enqueuedAt) / 1_000_000
                            )
                            CaptureDiagnostics.note(
                                app, batch.packageName, "DATABASE_COMMIT",
                                CaptureDiagnostics.DB_INSERT_SUCCESS, null,
                                batch.startTs, batch.trace)
                        }
                        is InboxDatabase.SaveResult.DUPLICATE -> {
                            CaptureMetrics.dbDuplicated.incrementAndGet()
                            CaptureMetrics.endToEnd.record(
                                (System.nanoTime() - batch.enqueuedAt) / 1_000_000
                            )
                            CaptureDiagnostics.note(
                                app, batch.packageName, "DATABASE_COMMIT",
                                CaptureDiagnostics.DB_DUPLICATE, null,
                                batch.startTs, batch.trace)
                        }
                        is InboxDatabase.SaveResult.FAILURE -> {
                            // Accounted for by the retry/terminal block below,
                            // never here: a row that is going to be retried must
                            // not already be reported as a final failure.
                        }
                    }
                }
                // Canonical linking happens AFTER the commit, from the same
                // worker thread. It resolves each batch's identity once (one
                // alias lookup per notification, not per row) and links every
                // committed row to that conversation. A link failure never
                // discards the committed ciphertext: the row stays linked only
                // in `message_conversation`, and `reconcilePendingLinks()` on
                // the next open recovers anything left pending.
                if (failedIndices.isEmpty()) {
                    // IR-03: hand the linker the ids SQLite actually assigned,
                    // so it never re-derives them from the NUL-containing key.
                    val committedIds = results.mapNotNull { r ->
                        (r as? InboxDatabase.SaveResult.NEW)?.id
                    }
                    linkBatchToCanonicalConversation(app, batch, committedIds)
                }
                if (failedIndices.isEmpty()) continue
                // Only the failed rows are carried forward. They keep their
                // capture order, so the conversation order is preserved.
                val failedMessages = failedIndices.map { batch.messages[it] }
                val attempt = batch.attempts + 1
                val causeName = (saveBatchesThrew ?: (results[failedIndices.first()]
                    as InboxDatabase.SaveResult.FAILURE).cause).javaClass.simpleName
                if (attempt < MAX_FLUSH_ATTEMPTS) {
                    toRetry.add(batch.withMessages(failedMessages, attempt))
                    CaptureDiagnostics.note(
                        app, batch.packageName, "DATABASE_COMMIT",
                        CaptureDiagnostics.DB_INSERT_RETRY_SCHEDULED, causeName,
                        batch.startTs, batch.trace)
                } else {
                    CaptureMetrics.dbFailed.addAndGet(failedMessages.size)
                    CaptureDiagnostics.note(
                        app, batch.packageName, "DATABASE_COMMIT",
                        CaptureDiagnostics.DB_INSERT_TERMINAL, causeName,
                        batch.startTs, batch.trace)
                }
            }
        } catch (e: Throwable) {
            // The flush itself blew up before any per-row accounting (open
            // failure, unexpected exception). EVERY drained batch is unaccounted
            // for, so all of them go back to PREPARED (bounded), or are
            // reported terminal when they have exhausted the budget. Nothing
            // here logs message content; the type name alone is diagnostic.
            val surviving = ArrayList<PreparedBatch>()
            for (batch in batches) {
                if (batch.messages.isEmpty()) continue
                val attempt = batch.attempts + 1
                if (attempt < MAX_FLUSH_ATTEMPTS) {
                    surviving.add(batch.withAttempt(attempt))
                } else {
                    CaptureMetrics.dbFailed.addAndGet(batch.messages.size)
                    CaptureDiagnostics.note(
                        app, batch.packageName, "DATABASE_COMMIT",
                        CaptureDiagnostics.DB_INSERT_TERMINAL, e.javaClass.simpleName,
                        batch.startTs, batch.trace)
                }
            }
            synchronized(PREPARED) {
                // Requeue at the FRONT so ordering by capture time is kept.
                PREPARED.addAll(0, surviving)
            }
            // Re-flush surviving retries after the SAME bounded backoff so a
            // transient fault (db open failure, disk stall) has a window to
            // clear. Bounded by MAX_FLUSH_ATTEMPTS.
            if (surviving.isNotEmpty()) {
                try {
                    Thread.sleep(RETRY_BACKOFF_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                flushPrepared()
            }
            // No private content is logged; the type name alone is diagnostic.
            CaptureDiagnostics.note(app, batches.firstOrNull()?.packageName,
                "DATABASE_COMMIT", CaptureDiagnostics.DB_OPEN_FAILED,
                e.javaClass.simpleName)
            return
        } finally { db?.close() }
        // The retry queue is re-armed AFTER the try/finally so a batch that
        // succeeded is never re-queued, and so the retry list is bounded.
        // The re-flush runs on the SAME worker thread after a BOUNDED backoff,
        // so (a) a transient fault has a window to clear before the next
        // attempt, and (b) retries complete even when no new notification
        // ever arrives (the worker drains PREPARED after every queue task and
        // the backoff sleep lives on this worker, not on a new thread).
        // Recursion depth stays bounded by MAX_FLUSH_ATTEMPTS.
        if (toRetry.isNotEmpty()) {
            synchronized(PREPARED) {
                PREPARED.addAll(0, toRetry)
            }
            try {
                Thread.sleep(RETRY_BACKOFF_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            flushPrepared()
        }
    }

    /**
     * Resolve the batch's conversation identity and link its committed rows.
     *
     * Runs on the worker thread, after the transaction has committed, so it can
     * never break the write path: any failure here leaves the rows safely stored
     * and only the LINK pending. Identity resolution is one alias lookup per
     * batch (the notification's own identity), not one per row.
     *
     * The link is found by legacy key + received_at window, because
     * `InboxDatabase.SaveResult` does not carry the row id and the `messages`
     * schema is frozen. The rows written by THIS batch are the ones with this
     * legacy key that have no link yet.
     */
    private fun linkBatchToCanonicalConversation(
        app: android.content.Context,
        batch: PreparedBatch,
        /**
         * IR-03: the ids SQLite actually assigned to this batch's NEW rows,
         * reported by save(). Linking these ids cannot confuse this batch with
         * a concurrent notification for the same conversation, and it never
         * compares the NUL-containing legacy key.
         */
        newRowIds: List<Long> = emptyList()
    ) {
        if (batch.messages.isEmpty()) return
        if (newRowIds.isEmpty()) return
        val startNs = System.nanoTime()
        try {
            val store = canonicalStore(app)
            val identity = ConversationIdentity.of(
                packageName = batch.packageName,
                title = batch.messages.first().title,
                shortcutId = null,        // kept from the legacy key path
                isGroup = false
            )
            // The legacy key the fingerprint used — resolving it must NOT change
            // the key itself.
            val legacyKey = batch.messages.first().conversationKey.ifEmpty {
                CapturePolicy.nameFor(batch.packageName)
            }
            val parsed = LegacyKeyCodec.parse(legacyKey)
            val resolution = ConversationIdentityResolver(store).resolve(
                batch.packageName, parsed.middle.takeIf { parsed.middleLooksLikeShortcut },
                parsed.title
            )

            // Diagnostics: counts and codes only, never content.
            CaptureDiagnostics.note(
                app, batch.packageName, "IDENTITY",
                if (resolution.ambiguous) CaptureDiagnostics.IDENTITY_AMBIGUOUS
                else CaptureDiagnostics.IDENTITY_RESOLVED, null,
                batch.startTs, batch.trace)

            // Link EXACTLY the rows this batch inserted. No key comparison, no
            // "newest N" heuristic: the ids come straight from the insert.
            val ids = newRowIds
            var linked = 0
            for (id in ids) {
                try {
                    store.linkMessage(id, resolution.conversationId)
                    linked++
                } catch (e: Throwable) {
                    CaptureDiagnostics.note(
                        app, batch.packageName, "IDENTITY",
                        CaptureDiagnostics.LINK_RECONCILE_PENDING,
                        e.javaClass.simpleName, batch.startTs, batch.trace)
                    return   // leave the rest for reconciliation
                }
            }
            CaptureDiagnostics.note(
                app, batch.packageName, "IDENTITY",
                CaptureDiagnostics.LINK_COMMITTED, "count=$linked",
                batch.startTs, batch.trace)
            CaptureDiagnostics.note(
                app, batch.packageName, "IDENTITY",
                "IDENTITY_LATENCY", "micros=${(System.nanoTime() - startNs) / 1000}",
                batch.startTs, batch.trace)
        } catch (e: Throwable) {
            // The message is safe; only the link is pending. Never rethrow.
            CaptureDiagnostics.note(
                app, batch.packageName, "IDENTITY",
                CaptureDiagnostics.LINK_FAILED, e.javaClass.simpleName,
                batch.startTs, batch.trace)
            // R01 ROOT CAUSE (first-hand evidence: 18x LINK_FAILED
            // SQLiteReadOnlyDatabaseException in the full-suite LINKDIAG dump):
            // a test's clearDatabase deletes the DB FILE while this store's
            // pooled connection still points at the old inode. Reads keep
            // "working" against the unlinked (deleted) file, every write is
            // read-only — and it never recovers on its own. Drop the store so
            // the next flush opens a fresh helper against the NEW file. On
            // production the same self-heal covers an externally replaced or
            // read-only-mounted database file.
            if (e is android.database.sqlite.SQLiteException) {
                closeCanonicalStore()
            }
        }
    }

    /**
     * Re-link any committed message that has no canonical link.
     *
     * Called after the schema repair on the first worker action; makes a link
     * failure from a previous process recoverable without any user action.
     */
    private fun reconcilePendingLinks(context: android.content.Context) {
        try {
            val n = canonicalStore(context).linkAllMessages()
            if (n > 0) {
                CaptureDiagnostics.note(
                    context, null, "IDENTITY",
                    CaptureDiagnostics.LINK_RECONCILED, "count=$n"
                )
            }
        } catch (e: Throwable) {
            CaptureDiagnostics.note(
                context, null, "IDENTITY",
                CaptureDiagnostics.LINK_RECONCILE_PENDING, e.javaClass.simpleName
            )
        }
    }

    /**
     * Submit a task to the worker.
     *
     * @return true when the task was queued, false when the queue was full and
     *         the task was REJECTED. A rejection is reported as a diagnostic,
     *         never silent.
     */
    private fun submit(task: () -> Unit): Boolean {
        val enqueueStart = android.os.SystemClock.elapsedRealtimeNanos()
        val queued = workQueue.offer({
            try {
                task()
            } catch (e: Throwable) {
                // A dead worker silently stops ALL capture, so the failure is
                // recorded and the worker keeps running.
                CaptureMetrics.workerFailures.incrementAndGet()
                runCatching {
                    val app = applicationContext
                    if (app != null) {
                        CaptureDiagnostics.note(
                            app, null, "WORKER", CaptureDiagnostics.WORKER_FAILURE,
                            e.javaClass.simpleName
                        )
                    }
                }
            }
        })
        // T2: the enqueue succeeded. It is measured INSIDE submit(), because
        // only here is it known whether the task was actually accepted.
        PipelineLatency.record(
            PipelineLatency.Stage.ENQUEUE,
            android.os.SystemClock.elapsedRealtimeNanos() - enqueueStart
        )
        if (queued) {
            CaptureMetrics.queued.incrementAndGet()
            CaptureMetrics.observeQueueDepth(workQueue.size)
        } else {
            CaptureMetrics.queueOverflow.incrementAndGet()
            CaptureMetrics.observeQueueDepth(workQueue.size)
            runCatching {
                val app = applicationContext
                if (app != null) {
                    CaptureDiagnostics.note(
                        app, null, "WORKER", CaptureDiagnostics.QUEUE_OVERFLOW,
                        "depth=${workQueue.size}"
                    )
                }
            }
        }
        return queued
    }

    private data class Preview(
        val body: String, val sender: String, val time: Long,
        val mediaUri: Uri? = null, val mediaMime: String? = null
    )

    override fun onListenerConnected() {
        // Live in-process connectivity evidence — the only thing that earns CONNECTED.
        // A system-bound service always has a non-null applicationContext; the catch
        // exists only so the hooks stay crash-proof when driven from tests on a bare
        // (unattached) instance. When a context is present, diagnostics are recorded.
        runCatching {
            val app = applicationContext ?: return@runCatching
            CaptureDiagnostics.markConnected(app)
        }
        // Catch up on the notifications that are STILL ACTIVE.
        //
        // A notification that has already been dismissed cannot be recovered —
        // the platform simply does not have it any more. But a notification
        // that is still on screen has NOT necessarily been captured: it may
        // have arrived while the service was not bound, or while the source
        // updated it. Reading the active list once at connect time recovers
        // exactly those.
        //
        // This is NOT a polling loop: it runs once per connection.
        runCatching {
            val app = applicationContext ?: return@runCatching
            val active = activeNotifications ?: return@runCatching
            var recovered = 0
            for (sbn in active) {
                // The same dedup key applies, so a notification that was already
                // captured is recognised as a duplicate and not stored twice.
                if (submit { capture(app, sbn) }) {
                    recovered++
                }
            }
            if (recovered > 0) {
                CaptureDiagnostics.note(app, null, "LISTENER_CONNECT",
                    CaptureDiagnostics.ACTIVE_RECOVERED, "count=$recovered")
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.key?.let { seenDiscriminators.remove(it) }
        runCatching {
            val app = applicationContext ?: return@runCatching
            CaptureDiagnostics.note(app, sbn?.packageName, "NOTIFICATION_REMOVED",
                CaptureDiagnostics.NOTIFICATION_REMOVED, CaptureDiagnostics.REMOVAL_REASON_UNKNOWN)
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: NotificationListenerService.RankingMap?,
        reason: Int
    ) {
        // The notification is gone, so its memory of which messages were
        // captured is no longer needed. A later notification with the same key
        // would be a NEW message set anyway.
        sbn?.key?.let { seenDiscriminators.remove(it) }
        runCatching {
            val app = applicationContext ?: return@runCatching
            // Retraction semantics (v0.4.5): a removal is OBSERVED, never
            // acted on. Committed rows stay immutable — the archive exists
            // precisely so a message retracted in the source app remains
            // readable here. Only the reason class is recorded, and the
            // package name only; never any message content.
            val reasonClass = removalReasonClassOf(reason)
            CaptureDiagnostics.note(app, sbn?.packageName, "NOTIFICATION_REMOVED",
                CaptureDiagnostics.NOTIFICATION_REMOVED, reasonClass)
            // NOTE: the connection flag is deliberately NOT touched here.
            // A notification removal says nothing about the service binding:
            // clearing it (a previous mistake) made every later test wait for
            // a reconnect that had already happened, and corrupted the
            // connectivity evidence in production diagnostics too.
        }
    }

    override fun onListenerDisconnected() {
        runCatching {
            val app = applicationContext ?: return@runCatching
            CaptureDiagnostics.markDisconnected(app)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // A notification listener that throws is killed by the system, which
        // silently stops ALL capture until the service is rebound. A single
        // malformed notification must never be able to do that, so the whole
        // capture path is guarded. The failure is recorded in diagnostics (type
        // name only, never message content).
        //
        // The service context is NOT usable as a fallback: a Service instance
        // created outside the system has a null base context, and even
        // ContextWrapper.getApplicationContext() would throw. Only a real,
        // attached application context is usable.
        val ctx = applicationContext
        if (ctx == null) {
            // No usable context: nothing can be recorded and nothing can be
            // stored. Bail out safely rather than throwing.
            return
        }
        // The callback's wall time is measured, because the callback runs on
        // the MAIN thread: a slow callback delays every other notification,
        // and the platform may coalesce or drop what arrives meanwhile.
        val callbackStart = System.nanoTime()
        // The latency stages use the MONOTONIC clock, so a user changing the
        // system clock cannot make a latency negative.
        val t0 = android.os.SystemClock.elapsedRealtimeNanos()
        CaptureMetrics.callbacksEntered.incrementAndGet()
        try {
            capture(ctx, sbn, t0)
        } catch (e: Throwable) {
            runCatching {
                CaptureDiagnostics.note(ctx, sbn?.packageName,
                    "SYSTEM_NOTIFICATION", CaptureDiagnostics.PARSE_FAILED,
                    e.javaClass.simpleName)
            }
        } finally {
            CaptureMetrics.callbacksExited.incrementAndGet()
            CaptureMetrics.callbackDuration.record(
                (System.nanoTime() - callbackStart) / 1_000_000
            )
        }
    }

    /** Core capture routine. Split out from the service callback so it can be
     *  exercised directly by instrumentation tests without a bound system service.
     *  [appContext] must be a usable application context. */
    fun capture(
        appContext: android.content.Context,
        sbn: StatusBarNotification?,
        t0: Long = android.os.SystemClock.elapsedRealtimeNanos()
    ) {
        val startTs = System.currentTimeMillis()
        if (sbn == null) {
            CaptureDiagnostics.note(appContext, null, "NOTIFICATION_PARSER",
                CaptureDiagnostics.EMPTY_NOTIFICATION, "null_sbn", startTs)
            return
        }
        val pkg = sbn.packageName
        // One trace id per notification, reused by every pipeline stage so a single
        // capture attempt can be followed end to end in diagnostics.
        val trace = CaptureDiagnostics.newTraceId()
        CaptureDiagnostics.note(appContext, pkg, "SYSTEM_NOTIFICATION",
            CaptureDiagnostics.NOTIFICATION_RECEIVED, null, startTs, trace)
        // The stage trace is written to the log, NOT to the diagnostics ring:
        // the ring does a SharedPreferences write per event, and this line is
        // on the MAIN thread. The benchmark reads the latency stages through
        // the provider instead.
        if (!LocalSettings.isRecording(appContext)) {
            CaptureMetrics.policyRejected.incrementAndGet()
            CaptureDiagnostics.note(appContext, pkg, "RECORDING_POLICY",
                CaptureDiagnostics.RECORDING_DISABLED, null, startTs, trace)
            return
        }
        if (!CapturePolicy.allowed(pkg, LocalSettings.selected(appContext))) {
            CaptureMetrics.policyRejected.incrementAndGet()
            CaptureDiagnostics.note(appContext, pkg, "SOURCE_POLICY",
                CaptureDiagnostics.SOURCE_NOT_ALLOWED, null, startTs, trace)
            return
        }
        CaptureMetrics.policyAccepted.incrementAndGet()
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            CaptureMetrics.groupSummarySkipped.incrementAndGet()
            CaptureDiagnostics.note(appContext, pkg, "NOTIFICATION_PARSER",
                CaptureDiagnostics.GROUP_SUMMARY_SKIPPED, null, startTs, trace)
            return
        }
        val extras = sbn.notification.extras
        if (extras == null) {
            CaptureDiagnostics.note(appContext, pkg, "NOTIFICATION_PARSER",
                CaptureDiagnostics.EMPTY_NOTIFICATION, "null_extras", startTs, trace)
            return
        }
        val conversationTitle = CapturePolicy.normalize(
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        ) ?: CapturePolicy.nameFor(pkg)
        // The conversation shortcut id is the platform's real conversation
        // discriminator. It is more reliable than the display title, which two
        // different contacts can share.
        val shortcutId = runCatching {
            sbn.notification.shortcutId?.takeIf { it.isNotBlank() }
        }.getOrNull()
        val hasMessageContainer = !extras.getParcelableArray(Notification.EXTRA_MESSAGES).isNullOrEmpty() ||
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES) != null
        val hasConversationShortcut = shortcutId != null
        val identity = ConversationIdentity.of(
            packageName = pkg,
            title = conversationTitle,
            shortcutId = shortcutId,
            isGroup = hasConversationShortcut
        )
        val kind = SourceRegistry.classify(
            packageName = pkg,
            hasMessageContainer = hasMessageContainer,
            conversationTitle = conversationTitle,
            appDisplayName = SourceRegistry.displayName(pkg),
            category = sbn.notification.category,
            hasConversationShortcut = hasConversationShortcut
        )
        val parseStart = System.nanoTime()
        val previews = previews(extras)
        if (previews.isEmpty()) {
            CaptureMetrics.parseFailed.incrementAndGet()
            CaptureDiagnostics.note(appContext, pkg, "NOTIFICATION_PARSER",
                CaptureDiagnostics.EMPTY_NOTIFICATION, "no_content", startTs, trace)
            return
        }
        CaptureMetrics.parseSucceeded.incrementAndGet()
        CaptureMetrics.messagesPresented.addAndGet(previews.size)
        CaptureDiagnostics.note(appContext, pkg, "NOTIFICATION_PARSER",
            CaptureDiagnostics.PARSE_SUCCESS, "messages=${previews.size}", startTs, trace)
        val captureImages = LocalSettings.captureImages(appContext)
        // Only the raw byte read has to happen inside the callback: the provider may
        // revoke the ephemeral URI grant as soon as the callback returns. Decoding
        // and downscaling are CPU bound and happen on the worker thread below.
        // TASK 5: when the image feature is off, NO image bytes are read or decoded —
        // `pictureBytes` and `fromMessage` are both null, so the worker does zero
        // image work. Raw-byte reads of a media URI happen here only when images are
        // on and the preview actually carries an image mime.
        val pictureBytes = if (captureImages) readPictureBytes(appContext, extras) else null
        val received = System.currentTimeMillis()
        // The message window is NOT truncated.
        //
        // A previous version took the last 25 previews, which silently DROPPED
        // the earlier messages of a group notification carrying more than 25 of
        // them. A busy group routinely exceeds that, and the dropped messages
        // were never recoverable: the source only sends the newest revision.
        //
        // The list is now bounded by a limit that is high enough to cover any
        // realistic group, and the number of messages that had to be dropped is
        // recorded so the loss is visible instead of silent.
        val totalPreviews = previews.size
        val window = if (totalPreviews > MAX_PREVIEWS) {
            // Keep the NEWEST messages: the oldest ones are the most likely to
            // have been captured already in an earlier revision.
            val truncated = totalPreviews - MAX_PREVIEWS
            CaptureMetrics.messagesTruncated.addAndGet(truncated)
            CaptureDiagnostics.note(appContext, pkg, "NOTIFICATION_PARSER",
                CaptureDiagnostics.MESSAGE_HISTORY_TRUNCATED, "dropped=$truncated",
                startTs, trace)
            previews.takeLast(MAX_PREVIEWS)
        } else {
            previews
        }
        // The message discriminator: a per-message value that is STABLE across
        // revisions of the same notification and DIFFERENT between two distinct
        // messages.
        //
        // A group notification is delivered as a growing list: revision 1
        // carries message 0, revision 2 carries messages 0 and 1, and so on.
        // The INDEX of a message in that list is therefore stable for the same
        // message, while the total count is not. Using the index keeps a
        // message from being stored twice when the source re-sends the
        // notification, and keeps two identical messages apart.
        //
        // The index is taken from the FULL preview list, not from the truncated
        // window, so it does not shift when the window is cut.
        // The index is taken from the FULL preview list, so it does not shift
        // when the window is cut.
        val windowStart = previews.size - window.size
        val items = window.mapIndexed { offset, p ->
            val index = windowStart + offset
            val last = index == previews.lastIndex
            val fromMessage = if (captureImages && p.mediaMime?.startsWith("image/") == true)
                ImageCapture.readBytes(appContext, p.mediaUri) else null
            Triple(p, fromMessage ?: pictureBytes?.takeIf { last }, discriminatorFor(p, index))
        }
        // Only the messages that are NEW in this revision are worth writing.
        //
        // A group notification is delivered as a growing history, so revision
        // N presents N messages while only ONE of them is new. Writing all of
        // them is pure write amplification: for a 500-message group it is 9,500
        // writes for 500 messages, and it saturates the work queue (the
        // measured queue depth was 476 with a 39 second write wait).
        //
        // The index of a message in the history is stable across revisions, so
        // the set of indices already written for THIS notification is the
        // memory of what has been captured. Only the indices not in that set
        // are new. The set is per notification, and it is bounded, so it
        // cannot grow without limit.
        val notificationMemoryKey = sbn.key ?: "$pkg#${sbn.id}"
        // The memory counts OCCURRENCES, it does not hold a set.
        //
        // A group history can contain two messages with the SAME content. A set
        // would treat the second one as already seen and drop it, so a genuine
        // message would be lost. A count per discriminator keeps both.
        val seen = seenDiscriminators.getOrPut(notificationMemoryKey) { HashMap() }
        // The rule: a message is new when its occurrence index within the
        // CURRENT window exceeds the number of times that discriminator was
        // already written for this notification.
        val occurrenceInWindow = HashMap<String, Int>()
        val newItems = items.filter { (_, _, discriminator) ->
            val index = (occurrenceInWindow[discriminator] ?: 0) + 1
            occurrenceInWindow[discriminator] = index
            val written = seen[discriminator] ?: 0
            if (index <= written) {
                false
            } else {
                seen[discriminator] = index
                true
            }
        }
        // The memory is only useful while the notification is alive. When the
        // notification is dismissed the entry would linger, so it is trimmed
        // whenever the map grows past its bound.
        if (seen.size > SEEN_INDICES_LIMIT) {
            seen.clear()
        }
        // The messages that are actually written. This is the denominator the
        // database accounting adds up against.
        CaptureMetrics.messagesSubmitted.addAndGet(newItems.size)
        val enqueuedAt = System.nanoTime()
        // T1: the snapshot is complete. Everything before this point happens
        // on the MAIN thread, so it is the number that matters most.
        PipelineLatency.record(
            PipelineLatency.Stage.SNAPSHOT,
            android.os.SystemClock.elapsedRealtimeNanos() - t0
        )
        val accepted = submit {
            if (!LocalSettings.isRecording(appContext)) return@submit
            if (!CapturePolicy.allowed(pkg, LocalSettings.selected(appContext))) return@submit
            val dequeuedAt = System.nanoTime()
            CaptureMetrics.dequeued.incrementAndGet()
            CaptureMetrics.queueWait.record((dequeuedAt - enqueuedAt) / 1_000_000)
            // T3: the worker started on this task. The difference from the
            // enqueue is the queue wait, and the difference from T0 is the
            // total time the event spent waiting.
            PipelineLatency.record(
                PipelineLatency.Stage.WORKER_START,
                dequeuedAt - t0
            )
            try {
                // The rows are PREPARED first, then written in ONE transaction.
                //
                // Every save() is its own transaction, and every transaction
                // costs an fsync. The burst benchmark measured 59 ms per insert
                // at the median, so a 500-message burst spent roughly 30 SECONDS
                // waiting for the disk -- and that wait IS the queue wait,
                // because the single writer could not drain the queue.
                //
                // Preparing first also keeps the CPU work (the image decode)
                // OUT of the transaction, so the transaction is held for the
                // shortest possible time.
                val pending = ArrayList<InboxDatabase.PendingMessage>(newItems.size)
                for ((p, rawBytes, discriminator) in newItems) {
                    var photo: ByteArray? = null
                    var mediaNote: String? = null
                    if (rawBytes != null) {
                        // Decoding + JPEG re-encode is CPU bound; keep it off the callback.
                        photo = runCatching { ImageCapture.toPreview(rawBytes, p.mediaMime) }.getOrNull()
                        if (photo == null) mediaNote = CaptureDiagnostics.MEDIA_DECODE_FAILED
                    }
                    mediaNote?.let {
                        // An image problem is recorded but never blocks the text row.
                        CaptureDiagnostics.note(appContext, pkg, "MEDIA", it, null, startTs, trace)
                    }
                    // §11 isolation: an image failure never drops the text row.
                    val body = CapturePolicy.normalize(p.body)
                        ?: if (photo != null) "📷 照片" else null
                    if (body == null && photo == null) continue
                    pending.add(
                        InboxDatabase.PendingMessage(
                            packageName = pkg,
                            receivedAt = received,
                            sourceTimestamp = p.time,
                            title = conversationTitle,
                            sender = p.sender,
                            body = body ?: "",
                            image = photo,
                            conversationKey = identity.key,
                            messageKind = kind.name,
                            messageDiscriminator = discriminator
                        )
                    )
                }
                if (pending.isEmpty()) return@submit
                // The rows are PREPARED, not written. The worker writes the
                // batches of several notifications in ONE transaction, which
                // is what removes the per-notification fsync.
                synchronized(PREPARED) {
                    PREPARED.add(
                        PreparedBatch(
                            pending, pkg, enqueuedAt, t0, startTs, trace, appContext
                        )
                    )
                }
            } catch (e: Exception) {
                // No private content is logged; the type name alone is diagnostic.
                CaptureDiagnostics.note(appContext, pkg, "DATABASE_COMMIT",
                    CaptureDiagnostics.DB_OPEN_FAILED,
                    e.javaClass.simpleName, startTs, trace)
            }
        }
    }

    /** Read the notification's own picture bytes (BigPictureStyle bitmap or icon). */
    /**
     * The per-message discriminator used for dedup and for the write
     * amplification filter.
     *
     * It must be derived from the MESSAGE, not from its position in the list.
     *
     * The platform caps the MessagingStyle history at 25 messages, so once a
     * group exceeds that the OLDEST message is dropped and every remaining
     * message SHIFTS its index. An index-based discriminator therefore
     * identifies a different message after every shift, and the messages
     * beyond 25 are never captured at all.
     *
     * The source timestamp is the strongest identity the platform offers, and
     * it is stable for the same message. When the source provides no
     * timestamp, the message's own content is used together with the sender:
     * two genuinely different messages differ in at least one of them, and the
     * same message keeps both.
     */
    /**
     * The per-message discriminator used for the dedup key.
     *
     * It must satisfy two properties at once:
     *
     *  1. STABLE across revisions of the same notification. A group
     *     notification is delivered as a growing history, so the same message
     *     appears in every revision. If the discriminator changed between
     *     revisions, the same message would be stored again and again.
     *
     *  2. DIFFERENT for two distinct messages, even when their text is
     *     identical.
     *
     * The source timestamp satisfies (1) and mostly satisfies (2), but it has
     * MILLISECOND resolution, so two messages sent in the same millisecond
     * collide. The index satisfies (2) but NOT (1): the platform caps the
     * history at 25 messages, so beyond that the oldest is dropped and every
     * remaining message SHIFTS its index.
     *
     * The discriminator is therefore built from the CONTENT -- the sender and
     * the body -- with the index as a last resort. The content is stable
     * across revisions, and two messages with identical content are genuinely
     * ambiguous; the index breaks that tie for as long as the window has not
     * shifted.
     */
    private fun discriminatorFor(p: Preview, index: Int): String =
        if (p.time > 0) "ts:${p.time}:c:${p.sender}:${p.body}"
        else "c:${p.sender}:${p.body}:i$index"

    private fun readPictureBytes(ctx: android.content.Context, extras: Bundle): ByteArray? {
        val raw = if (Build.VERSION.SDK_INT >= 33)
            extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
        else @Suppress("DEPRECATION") (extras.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap)
        if (raw != null) return ImageCapture.asPreview(raw)
        if (Build.VERSION.SDK_INT >= 31) {
            val icon = if (Build.VERSION.SDK_INT >= 33)
                extras.getParcelable(Notification.EXTRA_PICTURE_ICON, Icon::class.java)
            else @Suppress("DEPRECATION") (extras.getParcelable(Notification.EXTRA_PICTURE_ICON) as? Icon)
            if (icon != null) {
                val drawable = runCatching { icon.loadDrawable(ctx) }.getOrNull()
                if (drawable != null) {
                    val w = drawable.intrinsicWidth.coerceIn(1, 1280)
                    val h = drawable.intrinsicHeight.coerceIn(1, 1280)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, w, h)
                    drawable.draw(canvas)
                    return ImageCapture.asPreview(bmp)
                }
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun previews(extras: Bundle): List<Preview> {
        val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (!bundles.isNullOrEmpty()) {
            if (Build.VERSION.SDK_INT >= 30) {
                val parsed = runCatching {
                    Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)
                        .map { m ->
                            Preview(
                                CapturePolicy.normalize(m.text?.toString()).orEmpty(),
                                CapturePolicy.normalize(m.senderPerson?.name?.toString()).orEmpty(),
                                m.timestamp, m.dataUri, m.dataMimeType
                            )
                        }.filter { it.body.isNotEmpty() || it.mediaUri != null }
                }.getOrDefault(emptyList())
                if (parsed.isNotEmpty()) return parsed
            }
            // Android 9/10 compatibility: bundle field names are platform internals; tested separately.
            // When the platform gives no original time, store 0 — never invent one from post time.
            val compat = bundles.mapNotNull { it as? Bundle }.mapNotNull { m ->
                val body = CapturePolicy.normalize(m.getCharSequence("text")?.toString()).orEmpty()
                val uri = m.getParcelable("uri") as? Uri
                if (body.isEmpty() && uri == null) null else Preview(
                    body, CapturePolicy.normalize(m.getCharSequence("sender")?.toString()).orEmpty(),
                    m.getLong("time", 0L), uri, m.getString("type")
                )
            }
            if (compat.isNotEmpty()) return compat
        }
        // The InboxStyle lines are NOT truncated here. An earlier version took
        // the last 15 lines, which silently dropped the earlier lines of a long
        // digest; anything over MAX_PREVIEWS (500) is bounded by the shared
        // window above, which records every drop it makes.
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { CapturePolicy.normalize(it?.toString()) }
            .orEmpty()
        if (lines.isNotEmpty()) return lines.map { Preview(it, "", 0L) }
        val text = CapturePolicy.normalize(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        )
        // BigPicture notifications sometimes have no text; save the picture-only entry.
        return listOf(Preview(text.orEmpty(), "", 0L))
    }

    override fun onDestroy() {
        // The worker is a daemon thread and blocks on take(); interrupting it
        // is what stops it. We wait for the worker to actually finish so that
        // no in-flight database writes can race with the next test's clearDatabase().
        worker.interrupt()
        try {
            worker.join()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        closeCanonicalStore()
        super.onDestroy()
    }
}

/**
 * Map a platform removal reason to a stable reason class for diagnostics.
 *
 * A pure function so the JVM tests can pin the classification without an
 * Android device. The 2-arg callback records REMOVAL_REASON_UNKNOWN, and any
 * reason this app does not interpret falls into REMOVAL_REASON_UNKNOWN rather
 * than being guessed.
 */
internal fun removalReasonClassOf(reason: Int): String {
    return when (reason) {
        // The source app itself withdrew the notification: the closest
        // signal we get for an unsend/retraction in the source app.
        NotificationListenerService.REASON_APP_CANCEL,
        NotificationListenerService.REASON_APP_CANCEL_ALL,
        NotificationListenerService.REASON_CHANNEL_BANNED
        -> CaptureDiagnostics.REMOVAL_SOURCE_CANCELLED
        // The user acted on it (swipe, clear-all, stop the app).
        NotificationListenerService.REASON_CANCEL,
        NotificationListenerService.REASON_CANCEL_ALL,
        NotificationListenerService.REASON_USER_STOPPED
        -> CaptureDiagnostics.REMOVAL_USER_DISMISSED
        else -> CaptureDiagnostics.REMOVAL_REASON_UNKNOWN
    }
}
