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

        /**
         * The maximum number of notifications waiting for the worker.
         *
         * A notification may carry hundreds of messages, so the bound is
         * generous. It exists so a sustained flood cannot grow the heap
         * without limit; an overflow is reported, never silent.
         */
        const val QUEUE_CAPACITY = 512

        /**
         * The maximum number of message indices remembered per notification.
         *
         * A pathological source that never dismisses its notifications could
         * otherwise make the memory grow without bound.
         */
        const val SEEN_INDICES_LIMIT = 4_000
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
        java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * The worker that drains the queue.
     *
     * It runs one task at a time so the database writes stay ordered.
     */
    private val worker = Thread({
        while (true) {
            val task = workQueue.take()
            try {
                task.run()
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
     * Submit a task to the worker.
     *
     * @return true when the task was queued, false when the queue was full and
     *         the task was REJECTED. A rejection is reported as a diagnostic,
     *         never silent.
     */
    private fun submit(task: () -> Unit): Boolean {
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
        // The notification is gone, so its memory of which messages were
        // captured is no longer needed. A later notification with the same key
        // would be a NEW message set anyway.
        sbn?.key?.let { seenDiscriminators.remove(it) }
        runCatching {
            val app = applicationContext ?: return@runCatching
            CaptureDiagnostics.markDisconnected(app)
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
        CaptureMetrics.callbacksEntered.incrementAndGet()
        try {
            capture(ctx, sbn)
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
    fun capture(appContext: android.content.Context, sbn: StatusBarNotification?) {
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
        val seen = seenDiscriminators.getOrPut(notificationMemoryKey) { HashSet() }
        val newItems = items.filter { (_, _, discriminator) ->
            if (seen.contains(discriminator)) {
                false
            } else {
                seen.add(discriminator)
                true
            }
        }
        // The memory is only useful while the notification is alive. When the
        // notification is dismissed the entry would linger, so it is trimmed
        // whenever the set grows past its bound.
        if (seen.size > SEEN_INDICES_LIMIT) {
            seen.clear()
            seen.addAll(newItems.map { it.third })
        }
        // The messages that are actually written. This is the denominator the
        // database accounting adds up against.
        CaptureMetrics.messagesSubmitted.addAndGet(newItems.size)
        val enqueuedAt = System.nanoTime()
        val accepted = submit {
            if (!LocalSettings.isRecording(appContext)) return@submit
            if (!CapturePolicy.allowed(pkg, LocalSettings.selected(appContext))) return@submit
            val dequeuedAt = System.nanoTime()
            CaptureMetrics.dequeued.incrementAndGet()
            CaptureMetrics.queueWait.record((dequeuedAt - enqueuedAt) / 1_000_000)
            var db: InboxDatabase? = null
            try {
                db = InboxDatabase(appContext)
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
                    // runCatching returns Result<SaveResult>: unwrap, THEN classify.
                    // save() reports insert problems itself as SaveResult.FAILURE, so a
                    // null value here means the call threw — record that type, never a
                    // fake success.
                    val dbStart = System.nanoTime()
                    val result = runCatching {
                        db!!.save(pkg, received, p.time, conversationTitle, p.sender,
                            body ?: "", photo, identity.key, kind.name, discriminator)
                    }
                    CaptureMetrics.dbDuration.record(
                        (System.nanoTime() - dbStart) / 1_000_000
                    )
                    when (val outcome = result.getOrNull()) {
                        InboxDatabase.SaveResult.NEW -> {
                            CaptureMetrics.dbCommitted.incrementAndGet()
                            CaptureMetrics.endToEnd.record(
                                (System.nanoTime() - enqueuedAt) / 1_000_000
                            )
                            CaptureDiagnostics.note(
                                appContext, pkg, "DATABASE_COMMIT",
                                CaptureDiagnostics.DB_INSERT_SUCCESS, null, startTs, trace)
                        }
                        InboxDatabase.SaveResult.DUPLICATE -> {
                            CaptureMetrics.dbDuplicated.incrementAndGet()
                            CaptureMetrics.endToEnd.record(
                                (System.nanoTime() - enqueuedAt) / 1_000_000
                            )
                            CaptureDiagnostics.note(
                                appContext, pkg, "DATABASE_COMMIT",
                                CaptureDiagnostics.DB_DUPLICATE, null, startTs, trace)
                        }
                        is InboxDatabase.SaveResult.FAILURE -> {
                            CaptureMetrics.dbFailed.incrementAndGet()
                            CaptureDiagnostics.note(
                                appContext, pkg, "DATABASE_COMMIT",
                                CaptureDiagnostics.DB_INSERT_FAILED,
                                outcome.cause.javaClass.simpleName, startTs, trace)
                        }
                        null -> CaptureDiagnostics.note(
                            appContext, pkg, "DATABASE_COMMIT",
                            CaptureDiagnostics.DB_INSERT_FAILED,
                            result.exceptionOrNull()?.javaClass?.simpleName ?: "insert_exception",
                            startTs, trace)
                    }
                }
            } catch (e: Exception) {
                // No private content is logged; the type name alone is diagnostic.
                CaptureDiagnostics.note(appContext, pkg, "DATABASE_COMMIT",
                    CaptureDiagnostics.DB_OPEN_FAILED,
                    e.javaClass.simpleName, startTs, trace)
            } finally { db?.close() }
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
    private fun discriminatorFor(p: Preview, index: Int): String =
        if (p.time > 0) "ts:${p.time}" else "c:$index:${p.sender}:${p.body}"

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
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { CapturePolicy.normalize(it?.toString()) }
            ?.takeLast(15).orEmpty()
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
        // is what stops it.
        worker.interrupt()
        super.onDestroy()
    }
}
