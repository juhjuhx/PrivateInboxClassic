package tw.personal.privateinbox

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ThreadLocalRandom

/** Local-only, bounded notification-pipeline diagnostics.
 *
 * Records one stage/result per event in a fixed-size in-process ring buffer AND in a
 * bounded, retained on-device store, so stage history survives a process restart.
 * It never stores message bodies, image data, image URIs, full extras, contact data,
 * keys or plaintext rows. Everything stays on-device.
 *
 * A notification's [Event.traceId] is a short non-private id assigned when the
 * notification enters the pipeline and reused by every later stage for that same
 * notification, so one capture attempt can be followed end to end. The per-event
 * [Event.id] is a global sequence (unique per event); the [Event.traceId] is what
 * correlates the stages of one notification.
 */
object CaptureDiagnostics {

    // -- listener connection states (permission-granted != connected) ----------
    const val STATE_UNKNOWN = "UNKNOWN"
    const val STATE_PERMISSION_DENIED = "PERMISSION_DENIED"
    const val STATE_PERMISSION_GRANTED_NOT_CONNECTED = "PERMISSION_GRANTED_NOT_CONNECTED"
    const val STATE_CONNECTED = "CONNECTED"
    const val STATE_DISCONNECTED = "DISCONNECTED"

    // -- lifecycle / result codes --------------------------------------------
    const val LISTENER_CONNECTED = "LISTENER_CONNECTED"
    const val LISTENER_DISCONNECTED = "LISTENER_DISCONNECTED"
    const val NOTIFICATION_RECEIVED = "NOTIFICATION_RECEIVED"
    const val RECORDING_DISABLED = "RECORDING_DISABLED"
    const val SOURCE_NOT_ALLOWED = "SOURCE_NOT_ALLOWED"
    const val GROUP_SUMMARY_SKIPPED = "GROUP_SUMMARY_SKIPPED"
    const val EMPTY_NOTIFICATION = "EMPTY_NOTIFICATION"
    const val PARSE_FAILED = "PARSE_FAILED"
    const val PARSE_SUCCESS = "PARSE_SUCCESS"
    /**
     * A single notification carried more messages than the safety bound allows,
     * so the oldest ones were dropped. The error type carries the count, e.g.
     * "dropped=37", so the loss is visible rather than silent.
     */
    const val MESSAGE_HISTORY_TRUNCATED = "MESSAGE_HISTORY_TRUNCATED"

    /**
     * The work queue was full, so a notification was REJECTED rather than
     * silently dropped. The error type carries the queue depth.
     */
    const val QUEUE_OVERFLOW = "QUEUE_OVERFLOW"

    /**
     * The worker threw while processing a notification. A dead worker would
     * silently stop ALL capture, so the failure is recorded and the worker
     * continues.
     */
    const val WORKER_FAILURE = "WORKER_FAILURE"

    /**
     * Notifications that were still active when the listener connected were
     * re-read, because they may not have been captured while the service was
     * not bound. The error type carries the count.
     */
    const val ACTIVE_RECOVERED = "ACTIVE_RECOVERED"
    const val ENCRYPT_FAILED = "ENCRYPT_FAILED"
    const val DB_OPEN_FAILED = "DB_OPEN_FAILED"
    const val DB_INSERT_FAILED = "DB_INSERT_FAILED"
    const val DB_DUPLICATE = "DB_DUPLICATE"
    const val DB_INSERT_SUCCESS = "DB_INSERT_SUCCESS"
    const val UI_REFRESH_SUCCESS = "UI_REFRESH_SUCCESS"
    const val UI_REFRESH_FAILED = "UI_REFRESH_FAILED"
    const val MEDIA_URI_UNAVAILABLE = "MEDIA_URI_UNAVAILABLE"
    const val MEDIA_DECODE_FAILED = "MEDIA_DECODE_FAILED"

    private const val PREFS = "capture_diag"
    private const val RING_CAPACITY = 500
    private const val PERSIST_CAPACITY = 100
    private const val PERSIST_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
    private const val K_PERSISTED = "persisted_events"
    private const val K_CONNECTED_FLAG = "connected_flag"
    private const val K_CONNECTED_TS = "connected_ts"
    private const val K_DISCONNECTED_TS = "disconnected_ts"
    private const val K_RECEIVED = "received"
    private const val K_PARSE_OK = "parse_ok"
    private const val K_DB_OK = "db_ok"
    private const val K_FAILED = "failed"
    private const val K_LAST_CODE = "last_code"
    private const val K_LAST_ERR = "last_err_type"
    private const val K_LAST_EVENT_TS = "last_diagnostic_event_at"
    private const val K_LAST_RECEIVED_TS = "last_notification_received_at"
    private const val K_LAST_SUCCESS_TS = "last_successful_capture_at"
    private const val K_LAST_ID = "last_id"
    private const val K_SEQ = "seq"

    /** Schema version of the exported JSON report. Bump when the shape changes. */
    const val EXPORT_SCHEMA_VERSION = 2

    data class Event(
        val ts: Long,
        val id: String,
        val traceId: String,
        val sourcePkg: String?,
        val stage: String,
        val code: String,
        val errorType: String?
    ) {
        /** Compact, private-content-free line. */
        fun toLine(): String =
            String.format("%d %-8s %-28s %-24s %s %s",
                ts, traceId, sourcePkg ?: "-", stage, code, errorType ?: "-")
    }

    @Volatile private var listenerConnected = false

    // ------------------------------------------------------------ ids

    /** A short, non-private per-notification trace id. Contains no message content;
     *  reused by every pipeline stage of one notification so it can be followed
     *  end to end via [eventsFor]. */
    fun newTraceId(): String =
        "t" + ThreadLocalRandom.current().nextLong(0L, 36L).toString(36) +
            System.nanoTime().toString(36)

    // ---------------------------------------------------------------- lifecycle

    /** The only thing that may earn CONNECTED: the system actually delivered a
     *  live bind to this listener instance. Persisted so the UI/test process sees
     *  "bound at least once since the last disconnect" — still NOT the same as a
     *  live in-process connection. */
    fun markConnected(ctx: Context) {
        listenerConnected = true
        prefs(ctx).edit()
            .putLong(K_CONNECTED_TS, System.currentTimeMillis())
            .putBoolean(K_CONNECTED_FLAG, true)
            .apply()
        note(ctx, ctx.packageName, "lifecycle", LISTENER_CONNECTED)
    }

    fun markDisconnected(ctx: Context) {
        listenerConnected = false
        prefs(ctx).edit()
            .putLong(K_DISCONNECTED_TS, System.currentTimeMillis())
            .putBoolean(K_CONNECTED_FLAG, false)
            .apply()
        note(ctx, ctx.packageName, "lifecycle", LISTENER_DISCONNECTED)
    }

    /** Live in-process evidence. Resets to false on a process restart; the system
     *  re-delivers onListenerConnected() on the next bind, which is the only way
     *  it can become true again. A grant flag never counts as this. */
    fun isListenerConnectedInProcess(): Boolean = listenerConnected

    fun hasConnectedEvidence(ctx: Context): Boolean {
        if (listenerConnected) return true
        return prefs(ctx).getBoolean(K_CONNECTED_FLAG, false)
    }

    /** Public API (minSdk 28): does the system currently consider this component a
     *  granted notification listener. No hidden/reflected method. */
    fun listenerAccessGranted(ctx: Context): Boolean {
        val component = ComponentName(ctx, PrivateNotificationListener::class.java)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationListenerAccessGranted(component)
    }

    /** Honest status, resilient to device reboot / process rebuild / de-authorization:
     *  - not granted  -> PERMISSION_DENIED (covers the user revoking access)
     *  - live bound   -> CONNECTED (only from an onListenerConnected() event)
     *  - granted but no live bind yet (fresh boot, process just restarted, or the
     *    system has not re-bound) -> PERMISSION_GRANTED_NOT_CONNECTED.
     *  A granted permission is NEVER reported as CONNECTED. */
    fun resolveStatus(ctx: Context): String = when {
        !listenerAccessGranted(ctx) -> STATE_PERMISSION_DENIED
        isListenerConnectedInProcess() -> STATE_CONNECTED
        else -> STATE_PERMISSION_GRANTED_NOT_CONNECTED
    }

    /** Ask the system to (re)bind the listener. User-initiated only; a rebind request
     *  that is merely accepted is NOT logged as a connection. */
    fun rebind(ctx: Context): Boolean {
        val component = ComponentName(ctx, PrivateNotificationListener::class.java)
        return try {
            NotificationListenerService.requestRebind(component)
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ---------------------------------------------------------------- recording

    /**
     * Append one stage result. [traceId] links every stage of one notification;
     *  [startTs] is when the notification entered the pipeline (drives elapsedMs).
     *  No private content is ever accepted here.
     *
     * THIS RUNS ON THE MAIN THREAD when it is called from the listener
     * callback, and it used to do a SharedPreferences read-modify-write PER
     * EVENT: four reads, a list deserialisation, a list serialisation and an
     * apply(). The callback path called it twenty times per notification, so one
     * notification cost twenty synchronous disk writes on the main thread.
     *
     * The counters and the events are now kept IN MEMORY, and the persistence
     * is a separate step that runs on a background thread. The observable
     * content is the same: the same events, the same counters, the same export.
     * The only difference is WHEN the bytes reach the disk.
     *
     * The trade-off, stated plainly: a process death loses the events that had
     * not been flushed yet. That is DIAGNOSTIC data, not the user's messages
     * -- those are in the encrypted database, which is committed separately --
     * and the diagnostics already have a retention window.
     */
    @Synchronized
    fun note(ctx: Context, sourcePkg: String?, stage: String, code: String,
             errorType: String? = null, startTs: Long? = null, traceId: String? = null) {
        val now = System.currentTimeMillis()
        val seq = nextSeq++
        val id = "d" + seq.toString(36)
        val event = Event(now, id, traceId ?: newTraceId(), sourcePkg, stage, code, errorType)
        ring.addLast(event)
        while (ring.size > RING_CAPACITY) ring.removeFirst()

        // The counters are kept in memory. They are flushed with the events.
        if (code == NOTIFICATION_RECEIVED) countersReceived++
        if (code == PARSE_SUCCESS) countersParseOk++
        if (code == DB_INSERT_SUCCESS || code == DB_DUPLICATE) countersDbOk++
        if (code.endsWith("_FAILED") || code == EMPTY_NOTIFICATION) countersFailed++
        countersDirty = true

        // The "last" values are in memory too, and counters() merges them with
        // the persisted ones, so a reader never sees a stale value.
        lastCode = code
        lastErr = errorType
        lastEventTs = now
        lastId = id
        if (code == NOTIFICATION_RECEIVED) lastReceivedTs = now
        if (code == DB_INSERT_SUCCESS || code == DB_DUPLICATE) lastSuccessTs = now

        // The event is queued for the next flush. The queue is bounded: a
        // flood cannot grow it without limit, and the OLDEST event is dropped
        // rather than the newest, because the newest is the one that matters
        // for a live diagnosis.
        pending.add(event)
        while (pending.size > PENDING_CAPACITY) pending.removeFirst()
        // A flush is requested only when the queue is worth a write. The
        // flush reads and rewrites the WHOLE persisted event list, so doing it
        // per event would be quadratic.
        if (pending.size >= FLUSH_THRESHOLD) requestFlush(ctx)
    }

    /**
     * The in-memory counters.
     *
     * They are flushed to SharedPreferences together with the events, and
     * counters() merges them with the persisted values, so a restart does not
     * lose the totals and a reader always sees the current numbers.
     */
    private var nextSeq = 0L
    private var countersReceived = 0L
    private var countersParseOk = 0L
    private var countersDbOk = 0L
    private var countersFailed = 0L
    private var countersDirty = false
    private var lastCode: String? = null
    private var lastErr: String? = null
    private var lastEventTs = 0L
    private var lastId: String? = null
    private var lastReceivedTs = 0L
    private var lastSuccessTs = 0L

    /** The events that have not been written to the disk yet. */
    private val pending = ArrayDeque<Event>()

    /** The maximum number of unflushed events. */
    private const val PENDING_CAPACITY = 200

    /**
     * The number of pending events that triggers a flush.
     *
     * A flush reads and rewrites the whole persisted event list, so doing it
     * per event would be quadratic in the number of events.
     */
    private const val FLUSH_THRESHOLD = 16

    /**
     * Ask the writer thread to flush.
     *
     * The flush runs on a single background thread, so the main thread never
     * waits for the disk. If the writer is busy, the events simply wait for
     * the next flush.
     *
     * The flushes are COALESCED. Submitting one task per event was a
     * regression: the callback records twenty events per notification, so a
     * burst of a hundred notifications submitted two thousand tasks, each
     * taking the SharedPreferences write lock. The main thread's own reads
     * then blocked behind that queue, and the measured cost of posting a
     * hundred notifications went from 130 ms to 116 SECONDS.
     *
     * At most ONE flush task is in flight. When it runs, it writes everything
     * that is pending, and it re-arms itself if more arrived while it was
     * writing.
     */
    private fun requestFlush(ctx: Context) {
        if (!flushScheduled.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        flushExecutor.execute {
            try {
                flush(app)
            } finally {
                flushScheduled.set(false)
                // If events arrived while the flush was running, one more
                // flush is needed. The flag is cleared first, so this
                // schedules a fresh task.
                if (hasPending()) requestFlush(app)
            }
        }
    }

    /** True when a flush task is in flight. */
    private val flushScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    @Synchronized
    private fun hasPending(): Boolean = pending.isNotEmpty() || countersDirty

    /**
     * Write the pending events and counters to the disk.
     *
     * It runs on the writer thread and it is idempotent: the events that were
     * written are removed from the pending queue exactly once.
     *
     * IMPORTANT: the monitor is NOT held during the disk write.
     *
     * `note()` is @Synchronized on this object, and the capture worker calls
     * it twenty times per message. Holding the monitor across the write made
     * every one of those calls block until the write finished, and the
     * measured commit latency went to FORTY SECONDS under a burst. The
     * snapshot of the pending state is taken under the monitor, the write
     * happens outside it, and the state is updated under the monitor again.
     */
    private fun flush(ctx: Context) {
        val events: List<Event>
        val counts: LongArray
        val snapshot: Pair<String, String>
        synchronized(this) {
            if (pending.isEmpty() && !countersDirty) return
            events = pending.toList()
            counts = longArrayOf(
                countersReceived, countersParseOk, countersDbOk, countersFailed
            )
            snapshot = Pair(lastCode ?: "", lastErr ?: "")
        }

        // The disk write happens WITHOUT the monitor.
        val p = prefs(ctx).edit()
        p.putLong(K_SEQ, nextSeq)
        if (snapshot.first.isNotEmpty()) p.putString(K_LAST_CODE, snapshot.first)
        if (snapshot.second.isNotEmpty()) p.putString(K_LAST_ERR, snapshot.second)
        p.putLong(K_LAST_EVENT_TS, lastEventTs)
        lastId?.let { p.putString(K_LAST_ID, it) }
        if (lastReceivedTs > 0) p.putLong(K_LAST_RECEIVED_TS, lastReceivedTs)
        if (lastSuccessTs > 0) p.putLong(K_LAST_SUCCESS_TS, lastSuccessTs)
        p.putLong(K_RECEIVED, persistedLong(ctx, K_RECEIVED) + counts[0])
        p.putLong(K_PARSE_OK, persistedLong(ctx, K_PARSE_OK) + counts[1])
        p.putLong(K_DB_OK, persistedLong(ctx, K_DB_OK) + counts[2])
        p.putLong(K_FAILED, persistedLong(ctx, K_FAILED) + counts[3])
        // Bounded cross-restart store: append, apply retention, cap.
        val persisted = persistedList(ctx).toMutableList()
        persisted.addAll(events)
        val cutoff = System.currentTimeMillis() - PERSIST_RETENTION_MS
        p.putString(
            K_PERSISTED,
            serialize(persisted.filter { it.ts >= cutoff }.takeLast(PERSIST_CAPACITY))
        )
        p.apply()

        // The counters are now on the disk; the in-memory copy must not be
        // counted a second time. Only the counters that were part of THIS
        // snapshot are cleared, so a note() that arrived during the write is
        // not lost.
        synchronized(this) {
            countersReceived -= counts[0]
            countersParseOk -= counts[1]
            countersDbOk -= counts[2]
            countersFailed -= counts[3]
            if (countersReceived == 0L && countersParseOk == 0L &&
                countersDbOk == 0L && countersFailed == 0L
            ) {
                countersDirty = false
            }
            events.forEach { pending.remove(it) }
        }
    }

    private fun persistedLong(ctx: Context, key: String): Long =
        prefs(ctx).getLong(key, 0L)

    /**
     * The single background thread that writes the diagnostics.
     *
     * One thread, so the writes are serialised and the ordering is kept.
     */
    private val flushExecutor = java.util.concurrent.Executors.newSingleThreadExecutor {
        Thread(it, "capture-diagnostics-writer").apply { isDaemon = true }
    }

    /** Bounded in-process ring buffer. Every access happens inside a
     *  @Synchronized method on this object, so the object monitor already
     *  serialises it. (@Synchronized cannot annotate a property with a backing
     *  field, and would not be needed here anyway.) */
    private val ring = ArrayDeque<Event>()

    @Synchronized
    fun recent(ctx: Context, limit: Int = 20): List<Event> {
        val cutoff = System.currentTimeMillis() - PERSIST_RETENTION_MS
        val merged = (persistedList(ctx).filter { it.ts >= cutoff } + ring.toList())
            .distinct().sortedBy { it.ts }.takeLast(limit.coerceIn(0, RING_CAPACITY))
        return merged
    }

    /** End-to-end stages of one notification, keyed by the trace id assigned at
     *  NOTIFICATION_RECEIVED. */
    @Synchronized
    fun eventsFor(ctx: Context, traceId: String): List<Event> {
        val cutoff = System.currentTimeMillis() - PERSIST_RETENTION_MS
        return (persistedList(ctx).filter { it.ts >= cutoff } + ring.toList())
            .distinct().filter { it.traceId == traceId }.sortedBy { it.ts }
    }

    /**
     * The counters, merged with the values that have not been flushed yet.
     *
     * The in-memory counters are added to the persisted ones, so a reader
     * always sees the CURRENT totals even when the disk write is still
     * pending. Without the merge a reader would see a number that is up to
     * one flush behind.
     */
    @Synchronized
    fun counters(ctx: Context) = mapOf(
        "received" to (persistedLong(ctx, K_RECEIVED) + countersReceived),
        "parse_ok" to (persistedLong(ctx, K_PARSE_OK) + countersParseOk),
        "db_ok" to (persistedLong(ctx, K_DB_OK) + countersDbOk),
        "failed" to (persistedLong(ctx, K_FAILED) + countersFailed),
        "last_code" to (lastCode ?: prefs(ctx).getString(K_LAST_CODE, null) ?: "-"),
        "last_err" to (lastErr ?: prefs(ctx).getString(K_LAST_ERR, null) ?: "-"),
        "last_diagnostic_event_at" to
            (if (lastEventTs > 0) lastEventTs else prefs(ctx).getLong(K_LAST_EVENT_TS, 0L)),
        "last_notification_received_at" to
            (if (lastReceivedTs > 0) lastReceivedTs else prefs(ctx).getLong(K_LAST_RECEIVED_TS, 0L)),
        "last_successful_capture_at" to
            (if (lastSuccessTs > 0) lastSuccessTs else prefs(ctx).getLong(K_LAST_SUCCESS_TS, 0L)),
        "last_id" to (lastId ?: prefs(ctx).getString(K_LAST_ID, null) ?: "-"),
        "connected_ts" to prefs(ctx).getLong(K_CONNECTED_TS, 0L),
        "disconnected_ts" to prefs(ctx).getLong(K_DISCONNECTED_TS, 0L)
    )

    /** Reset all diagnostic state, including the cross-restart store. */
    @Synchronized
    fun clear(ctx: Context) {
        ring.clear()
        // The in-memory state has to be cleared too, or the next flush would
        // write the counters that were just reset.
        pending.clear()
        nextSeq = 0L
        countersReceived = 0L
        countersParseOk = 0L
        countersDbOk = 0L
        countersFailed = 0L
        countersDirty = false
        lastCode = null
        lastErr = null
        lastEventTs = 0L
        lastId = null
        lastReceivedTs = 0L
        lastSuccessTs = 0L
        prefs(ctx).edit()
            .putLong(K_RECEIVED, 0L).putLong(K_PARSE_OK, 0L).putLong(K_DB_OK, 0L)
            .putLong(K_FAILED, 0L).putString(K_LAST_CODE, null).putString(K_LAST_ERR, null)
            .putLong(K_LAST_EVENT_TS, 0L).putLong(K_LAST_RECEIVED_TS, 0L)
            .putLong(K_LAST_SUCCESS_TS, 0L)
            .putString(K_LAST_ID, null).putString(K_PERSISTED, null)
            .putLong(K_CONNECTED_TS, 0L).putLong(K_DISCONNECTED_TS, 0L)
            .putBoolean(K_CONNECTED_FLAG, false)
            .putLong(K_SEQ, 0L)
            .apply()
    }

    /** Anonymous JSON export. Built with JSONObject/JSONArray so every value is
     *  escaped; the result is re-parsed so a malformed export can never ship.
     *  No message content, image bytes, URIs or contact data is included. */
    @Synchronized
    fun export(ctx: Context): String {
        val c = counters(ctx)
        val events = JSONArray()
        recent(ctx, 50).forEach { ev ->
            events.put(JSONObject()
                .put("ts", ev.ts)
                .put("id", ev.id)
                .put("traceId", ev.traceId)
                .put("pkg", ev.sourcePkg ?: "-")
                .put("stage", ev.stage)
                .put("code", ev.code)
                .put("err", ev.errorType ?: "-"))
        }
        val root = JSONObject()
            .put("schema_version", EXPORT_SCHEMA_VERSION)
            .put("app", "Private Inbox Classic")
            .put("version", "0.4.0-alpha")
            .put("diagnostics", JSONObject().apply {
                put("received", c["received"])
                put("parse_success", c["parse_ok"])
                put("db_writes", c["db_ok"])
                put("failures", c["failed"])
                put("listener_status", resolveStatus(ctx))
                put("listener_permission_granted", listenerAccessGranted(ctx))
                put("last_error", c["last_err"])
                put("last_code", c["last_code"])
                put("last_diagnostic_event_at", c["last_diagnostic_event_at"])
                put("last_notification_received_at", c["last_notification_received_at"])
                put("last_successful_capture_at", c["last_successful_capture_at"])
            })
            .put("recent_events", events)
        val json = root.toString()
        // Round-trip validation: throw if the string is not itself valid JSON.
        require(JSONObject(json).length() >= 4) { "diagnostics export failed self-validation" }
        return json
    }

    // ---- bounded persistence ------------------------------------------------
    private const val NULL_PKG = "-"

    private fun serialize(list: List<Event>): String {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject()
                .put("ts", e.ts).put("id", e.id).put("traceId", e.traceId)
                .put("pkg", e.sourcePkg ?: NULL_PKG)
                .put("stage", e.stage).put("code", e.code)
                .put("err", e.errorType ?: NULL_PKG))
        }
        return arr.toString()
    }

    private fun persistedList(ctx: Context): List<Event> {
        val raw = prefs(ctx).getString(K_PERSISTED, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Event(o.getLong("ts"), o.getString("id"), o.optString("traceId", "-"),
                    o.optString("pkg", NULL_PKG).takeIf { it != NULL_PKG },
                    o.getString("stage"), o.getString("code"),
                    o.optString("err", NULL_PKG).takeIf { it != NULL_PKG })
            }
        }.getOrDefault(emptyList())
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
