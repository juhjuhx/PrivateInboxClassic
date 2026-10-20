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
    private const val K_LAST_ID = "last_id"
    private const val K_SEQ = "seq"

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

    /** Append one stage result. [traceId] links every stage of one notification;
     *  [startTs] is when the notification entered the pipeline (drives elapsedMs).
     *  No private content is ever accepted here. */
    @Synchronized
    fun note(ctx: Context, sourcePkg: String?, stage: String, code: String,
             errorType: String? = null, startTs: Long? = null, traceId: String? = null) {
        val now = System.currentTimeMillis()
        val seq = prefs(ctx).getLong(K_SEQ, 0L) + 1L
        val id = "d" + seq.toString(36)
        val event = Event(now, id, traceId ?: newTraceId(), sourcePkg, stage, code, errorType)
        ring.addLast(event)
        while (ring.size > RING_CAPACITY) ring.removeFirst()

        val p = prefs(ctx).edit()
        p.putLong(K_SEQ, seq)
        p.putString(K_LAST_CODE, code)
        p.putString(K_LAST_ERR, errorType)
        p.putLong(K_LAST_EVENT_TS, now)
        p.putString(K_LAST_ID, id)
        if (code == NOTIFICATION_RECEIVED) p.putLong(K_LAST_RECEIVED_TS, now)
        p.putLong(K_RECEIVED, prefs(ctx).getLong(K_RECEIVED, 0L) +
            if (code == NOTIFICATION_RECEIVED) 1L else 0L)
        p.putLong(K_PARSE_OK, prefs(ctx).getLong(K_PARSE_OK, 0L) +
            if (code == PARSE_SUCCESS) 1L else 0L)
        p.putLong(K_DB_OK, prefs(ctx).getLong(K_DB_OK, 0L) +
            if (code == DB_INSERT_SUCCESS || code == DB_DUPLICATE) 1L else 0L)
        p.putLong(K_FAILED, prefs(ctx).getLong(K_FAILED, 0L) + when {
            code.endsWith("_FAILED") || code == EMPTY_NOTIFICATION ||
                code == RECORDING_DISABLED || code == SOURCE_NOT_ALLOWED -> 1L
            else -> 0L
        })
        // Bounded cross-restart store: append, apply retention, cap. SharedPreferences
        // is the cross-process-safe, corruption-free mechanism here (the listener and
        // the UI share the main process; the test process is separate).
        val persisted = persistedList(ctx).toMutableList()
        persisted.add(event)
        val cutoff = now - PERSIST_RETENTION_MS
        p.putString(K_PERSISTED, serialize(persisted.filter { it.ts >= cutoff }.takeLast(PERSIST_CAPACITY)))
        p.apply()
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

    fun counters(ctx: Context) = mapOf(
        "received" to prefs(ctx).getLong(K_RECEIVED, 0L),
        "parse_ok" to prefs(ctx).getLong(K_PARSE_OK, 0L),
        "db_ok" to prefs(ctx).getLong(K_DB_OK, 0L),
        "failed" to prefs(ctx).getLong(K_FAILED, 0L),
        "last_code" to (prefs(ctx).getString(K_LAST_CODE, null) ?: "-"),
        "last_err" to (prefs(ctx).getString(K_LAST_ERR, null) ?: "-"),
        "last_diagnostic_event_at" to prefs(ctx).getLong(K_LAST_EVENT_TS, 0L),
        "last_notification_received_at" to prefs(ctx).getLong(K_LAST_RECEIVED_TS, 0L),
        "last_id" to (prefs(ctx).getString(K_LAST_ID, null) ?: "-"),
        "connected_ts" to prefs(ctx).getLong(K_CONNECTED_TS, 0L),
        "disconnected_ts" to prefs(ctx).getLong(K_DISCONNECTED_TS, 0L)
    )

    /** Reset all diagnostic state, including the cross-restart store. */
    @Synchronized
    fun clear(ctx: Context) {
        ring.clear()
        prefs(ctx).edit()
            .putLong(K_RECEIVED, 0L).putLong(K_PARSE_OK, 0L).putLong(K_DB_OK, 0L)
            .putLong(K_FAILED, 0L).putString(K_LAST_CODE, null).putString(K_LAST_ERR, null)
            .putLong(K_LAST_EVENT_TS, 0L).putLong(K_LAST_RECEIVED_TS, 0L)
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
            .put("app", "Private Inbox Classic")
            .put("version", "0.3.2")
            .put("diagnostics", JSONObject().apply {
                put("received", c["received"])
                put("parse_success", c["parse_ok"])
                put("db_writes", c["db_ok"])
                put("failures", c["failed"])
                put("listener_status", resolveStatus(ctx))
                put("last_error", c["last_err"])
                put("last_code", c["last_code"])
                put("last_diagnostic_event_at", c["last_diagnostic_event_at"])
                put("last_notification_received_at", c["last_notification_received_at"])
            })
            .put("recent_events", events)
        val json = root.toString()
        // Round-trip validation: throw if the string is not itself valid JSON.
        require(JSONObject(json).length() >= 3) { "diagnostics export failed self-validation" }
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
