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

    // ---- v0.4.7 listener reliability diagnostics ---------------------------
    /**
     * A rebind request was ISSUED. This is NOT evidence of a connection: the
     * system may ignore it, refuse it, or bind later. Kept separate from
     * LISTENER_CONNECTED so a request can never be mistaken for a live bind.
     */
    const val REBIND_REQUESTED = "REBIND_REQUESTED"
    /** requestRebind() returned true (the call was accepted, still not a bind). */
    const val REBIND_ACCEPTED = "REBIND_ACCEPTED"
    /** requestRebind() threw or returned false. */
    const val REBIND_REFUSED = "REBIND_REFUSED"
    /** A rebind was requested but suppressed because the cooldown had not elapsed. */
    const val REBIND_COOLDOWN = "REBIND_COOLDOWN"
    /**
     * The listener service instance was destroyed. Distinct from
     * onListenerDisconnected(): onDestroy means the process/service is going
     * away, which on OriginOS may happen without a clean disconnect callback.
     */
    const val LISTENER_DESTROYED = "LISTENER_DESTROYED"
    /**
     * Recorded once at process start. Lets a reader tell "the process restarted"
     * apart from "the listener never connected", which look identical if only
     * the in-memory connection flag is inspected.
     */
    const val PROCESS_STARTED = "PROCESS_STARTED"
    /**
     * Stored connection evidence was older than the staleness window, so it is
     * NOT treated as evidence of a current connection.
     */
    const val CONNECTION_EVIDENCE_STALE = "CONNECTION_EVIDENCE_STALE"

    /**
     * The source app removed a notification we had already captured.
     *
     * A removal is observed for DIAGNOSTICS ONLY: committed rows stay
     * immutable, because the whole point of the archive is that a message
     * retracted in the source app remains readable here. The error type
     * distinguishes the reason class so support can tell a user-driven
     * dismiss (swipe) from a source-app retraction (unsend):
     *
     *   - SOURCE_CANCELLED: the source app itself cancelled it (REASON_APP_CANCEL*)
     *   - USER_DISMISSED: the user swiped it away (REASON_USER_*, REASON_CANCELLED_BY_USER)
     *   - REMOVAL_REASON_UNKNOWN: any other or unavailable reason
     */
    const val NOTIFICATION_REMOVED = "NOTIFICATION_REMOVED"
    const val REMOVAL_SOURCE_CANCELLED = "SOURCE_CANCELLED"
    const val REMOVAL_USER_DISMISSED = "USER_DISMISSED"
    const val REMOVAL_REASON_UNKNOWN = "REMOVAL_REASON_UNKNOWN"
    const val ENCRYPT_FAILED = "ENCRYPT_FAILED"
    const val DB_OPEN_FAILED = "DB_OPEN_FAILED"
    const val DB_INSERT_FAILED = "DB_INSERT_FAILED"
    const val DB_DUPLICATE = "DB_DUPLICATE"
    const val DB_INSERT_SUCCESS = "DB_INSERT_SUCCESS"
    const val DB_INSERT_RETRY_SCHEDULED = "DB_INSERT_RETRY_SCHEDULED"
    const val DB_INSERT_TERMINAL = "DB_INSERT_TERMINAL"
    // v0.4.6 identity layer diagnostics. Type names and counts only; never any
    // conversation title, sender, body or URI.
    const val SCHEMA_REPAIR_FAILED = "SCHEMA_REPAIR_FAILED"
    const val IDENTITY_RESOLVED = "IDENTITY_RESOLVED"
    const val IDENTITY_AMBIGUOUS = "IDENTITY_AMBIGUOUS"
    const val ALIAS_CONFLICT = "ALIAS_CONFLICT"
    const val LINK_COMMITTED = "LINK_COMMITTED"
    const val LINK_RECONCILE_PENDING = "LINK_RECONCILE_PENDING"
    const val LINK_RECONCILED = "LINK_RECONCILED"
    const val LINK_FAILED = "LINK_FAILED"
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
    // v0.4.7 process/listener identity, persisted so a restart is visible
    private const val K_PROCESS_SESSION_ID = "process_session_id"
    private const val K_PROCESS_START_TS = "process_start_ts"
    private const val K_PROCESS_START_COUNT = "process_start_count"
    private const val K_LISTENER_INSTANCE_ID = "listener_instance_id"
    private const val K_LISTENER_INSTANCE_COUNT = "listener_instance_count"
    private const val K_LAST_REBIND_TS = "last_rebind_ts"
    private const val K_REBIND_COUNT = "rebind_count"
    private const val K_LAST_DISCONNECT_REASON = "last_disconnect_reason"

    /**
     * How long stored connection evidence stays meaningful. Past this, a
     * CONNECTED flag left over from a previous process is reported as STALE
     * rather than as current connectivity. Chosen to outlive a normal
     * screen-off period but not a multi-hour background kill.
     */
    private const val CONNECTION_EVIDENCE_TTL_MS = 30L * 60L * 1000L

    /** Minimum gap between automatic rebind attempts, to avoid a request storm. */
    const val REBIND_COOLDOWN_MS = 60L * 1000L

    /** Schema version of the exported JSON report. Bump when the shape changes. */
    const val EXPORT_SCHEMA_VERSION = 3

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

    // ---------------------------------------------------- process / instance

    /**
     * Identity of THIS process. Assigned once on first use and persisted, so a
     * reader can distinguish "the process restarted" from "the listener never
     * connected". Both counters are monotonic across restarts.
     */
    @Volatile private var processSessionId: String? = null
    @Volatile private var processStartTs: Long = 0L
    @Volatile private var processStartCount: Int = 0

    /** Identity of THIS listener service instance, distinct from the process. */
    @Volatile private var listenerInstanceId: String? = null

    private fun ensureProcessSession(ctx: Context) {
        if (processSessionId != null) return
        synchronized(this) {
            if (processSessionId != null) return
            val p = prefs(ctx)
            val startTs = System.currentTimeMillis()
            val count = p.getInt(K_PROCESS_START_COUNT, 0) + 1
            val sid = "p" + count + "-" + startTs.toString(36)
            p.edit()
                .putString(K_PROCESS_SESSION_ID, sid)
                .putLong(K_PROCESS_START_TS, startTs)
                .putInt(K_PROCESS_START_COUNT, count)
                .apply()
            processSessionId = sid
            processStartTs = startTs
            processStartCount = count
        }
        // One event per process start: makes restarts countable in the export.
        note(ctx, ctx.packageName, "lifecycle", PROCESS_STARTED, errorType = "n=$processStartCount")
    }

    fun processSession(ctx: Context): String {
        ensureProcessSession(ctx)
        return processSessionId ?: "unknown"
    }

    fun processStartTimestamp(ctx: Context): Long {
        ensureProcessSession(ctx)
        return processStartTs
    }

    fun processStartCount(ctx: Context): Int {
        ensureProcessSession(ctx)
        return processStartCount
    }

    /** Called from onListenerConnected; a new instance id per bind. */
    fun newListenerInstance(ctx: Context): String {
        ensureProcessSession(ctx)
        synchronized(this) {
            val count = prefs(ctx).getInt(K_LISTENER_INSTANCE_COUNT, 0) + 1
            val iid = "l" + count + "-" + System.currentTimeMillis().toString(36)
            prefs(ctx).edit()
                .putString(K_LISTENER_INSTANCE_ID, iid)
                .putInt(K_LISTENER_INSTANCE_COUNT, count)
                .apply()
            listenerInstanceId = iid
            return iid
        }
    }

    fun listenerInstance(ctx: Context): String? = listenerInstanceId

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
        val iid = newListenerInstance(ctx)
        prefs(ctx).edit()
            .putLong(K_CONNECTED_TS, System.currentTimeMillis())
            .putBoolean(K_CONNECTED_FLAG, true)
            .apply()
        note(ctx, ctx.packageName, "lifecycle", LISTENER_CONNECTED, errorType = iid)
    }

    fun markDisconnected(ctx: Context, reason: String? = null) {
        listenerConnected = false
        prefs(ctx).edit()
            .putLong(K_DISCONNECTED_TS, System.currentTimeMillis())
            .putBoolean(K_CONNECTED_FLAG, false)
            .putString(K_LAST_DISCONNECT_REASON, reason ?: "-")
            .apply()
        note(ctx, ctx.packageName, "lifecycle", LISTENER_DISCONNECTED, errorType = reason)
    }

    /** onDestroy: the service instance is going away. On OriginOS this may
     *  happen without a preceding clean onListenerDisconnected(). */
    fun markDestroyed(ctx: Context) {
        val was = listenerConnected
        listenerConnected = false
        prefs(ctx).edit().putBoolean(K_CONNECTED_FLAG, false).apply()
        note(ctx, ctx.packageName, "lifecycle", LISTENER_DESTROYED,
            errorType = if (was) "was_connected" else "was_not_connected")
    }

    /** Live in-process evidence. Resets to false on a process restart; the system
     *  re-delivers onListenerConnected() on the next bind, which is the only way
     *  it can become true again. A grant flag never counts as this. */
    fun isListenerConnectedInProcess(): Boolean = listenerConnected

    /**
     * Is there CONNECTED evidence that is still meaningful?
     *
     * A persisted flag from a previous process is only evidence if it is recent
     * — a multi-hour-old flag on a device that aggressively kills background
     * work says nothing about right now. Past [CONNECTION_EVIDENCE_TTL_MS] the
     * caller is told the evidence is stale rather than current.
     */
    fun hasConnectedEvidence(ctx: Context): Boolean {
        if (listenerConnected) return true
        val p = prefs(ctx)
        if (!p.getBoolean(K_CONNECTED_FLAG, false)) return false
        val ts = p.getLong(K_CONNECTED_TS, 0L)
        if (ts <= 0L) return false
        val age = System.currentTimeMillis() - ts
        if (age > CONNECTION_EVIDENCE_TTL_MS) {
            note(ctx, ctx.packageName, "lifecycle", CONNECTION_EVIDENCE_STALE,
                errorType = "age_ms=" + age)
            return false
        }
        return true
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

    /**
     * Ask the system to (re)bind the listener.
     *
     * A successful call only means the REQUEST was accepted — it is explicitly
     * NOT recorded as a connection. Only onListenerConnected() can do that.
     *
     * [userInitiated] = true skips the cooldown (an explicit tap should always
     * be honoured); automatic callers are rate-limited so a disconnected
     * listener cannot produce a request storm.
     *
     * @return true when the request was issued.
     */
    fun rebind(ctx: Context, userInitiated: Boolean = true): Boolean {
        val now = System.currentTimeMillis()
        val last = prefs(ctx).getLong(K_LAST_REBIND_TS, 0L)
        if (!userInitiated && last > 0 && now - last < REBIND_COOLDOWN_MS) {
            note(ctx, ctx.packageName, "lifecycle", REBIND_COOLDOWN,
                errorType = "elapsed_ms=" + (now - last))
            return false
        }
        note(ctx, ctx.packageName, "lifecycle", REBIND_REQUESTED,
            errorType = if (userInitiated) "user" else "auto")
        val component = ComponentName(ctx, PrivateNotificationListener::class.java)
        val ok = try {
            NotificationListenerService.requestRebind(component)
            true
        } catch (_: Throwable) {
            false
        }
        val p = prefs(ctx)
        if (ok) {
            p.edit()
                .putLong(K_LAST_REBIND_TS, now)
                .putInt(K_REBIND_COUNT, p.getInt(K_REBIND_COUNT, 0) + 1)
                .apply()
            note(ctx, ctx.packageName, "lifecycle", REBIND_ACCEPTED)
        } else {
            note(ctx, ctx.packageName, "lifecycle", REBIND_REFUSED)
        }
        return ok
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
        "disconnected_ts" to prefs(ctx).getLong(K_DISCONNECTED_TS, 0L),
        "rebind_count" to prefs(ctx).getInt(K_REBIND_COUNT, 0),
        "last_rebind_ts" to prefs(ctx).getLong(K_LAST_REBIND_TS, 0L),
        "process_start_count" to prefs(ctx).getInt(K_PROCESS_START_COUNT, 0)
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
        val p = prefs(ctx)
        val root = JSONObject()
            .put("schema_version", EXPORT_SCHEMA_VERSION)
            .put("app", "Private Inbox Classic")
            .put("version", BuildConfig.VERSION_NAME)
            .put("version_code", BuildConfig.VERSION_CODE)
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

                // ---- v0.4.7 process / connection identity ----------------
                put("process_session_id", processSession(ctx))
                put("process_start_ts", processStartTimestamp(ctx))
                put("process_start_count", processStartCount(ctx))
                put("listener_instance_id", listenerInstance(ctx) ?: "-")
                put("listener_instance_count", p.getInt(K_LISTENER_INSTANCE_COUNT, 0))
                put("listener_connected_in_process", isListenerConnectedInProcess())
                put("connection_evidence", hasConnectedEvidence(ctx))
                put("connected_ts", p.getLong(K_CONNECTED_TS, 0L))
                put("disconnected_ts", p.getLong(K_DISCONNECTED_TS, 0L))
                put("last_disconnect_reason", p.getString(K_LAST_DISCONNECT_REASON, "-"))
                put("rebind_count", p.getInt(K_REBIND_COUNT, 0))
                put("last_rebind_ts", p.getLong(K_LAST_REBIND_TS, 0L))
                put("rebind_cooldown_ms", REBIND_COOLDOWN_MS)
                put("connection_evidence_ttl_ms", CONNECTION_EVIDENCE_TTL_MS)
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
