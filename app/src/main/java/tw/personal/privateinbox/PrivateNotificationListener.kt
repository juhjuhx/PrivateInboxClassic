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
import java.util.concurrent.Executors

/** Only stores user-selected sources. No network or chat-account privileges. */
class PrivateNotificationListener : NotificationListenerService() {
    private val worker = Executors.newSingleThreadExecutor()
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
    }

    override fun onListenerDisconnected() {
        runCatching {
            val app = applicationContext ?: return@runCatching
            CaptureDiagnostics.markDisconnected(app)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        capture(applicationContext, sbn)
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
            CaptureDiagnostics.note(appContext, pkg, "RECORDING_POLICY",
                CaptureDiagnostics.RECORDING_DISABLED, null, startTs, trace)
            return
        }
        if (!CapturePolicy.allowed(pkg, LocalSettings.selected(appContext))) {
            CaptureDiagnostics.note(appContext, pkg, "SOURCE_POLICY",
                CaptureDiagnostics.SOURCE_NOT_ALLOWED, null, startTs, trace)
            return
        }
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
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
        val previews = previews(extras)
        if (previews.isEmpty()) {
            CaptureDiagnostics.note(appContext, pkg, "NOTIFICATION_PARSER",
                CaptureDiagnostics.EMPTY_NOTIFICATION, "no_content", startTs, trace)
            return
        }
        CaptureDiagnostics.note(appContext, pkg, "NOTIFICATION_PARSER",
            CaptureDiagnostics.PARSE_SUCCESS, null, startTs, trace)
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
        val window = previews.takeLast(25)
        val items = window.mapIndexed { idx, p ->
            val last = idx == window.lastIndex
            val fromMessage = if (captureImages && p.mediaMime?.startsWith("image/") == true)
                ImageCapture.readBytes(appContext, p.mediaUri) else null
            p to (fromMessage ?: pictureBytes?.takeIf { last })
        }
        worker.execute {
            if (!LocalSettings.isRecording(appContext)) return@execute
            if (!CapturePolicy.allowed(pkg, LocalSettings.selected(appContext))) return@execute
            var db: InboxDatabase? = null
            try {
                db = InboxDatabase(appContext)
                for ((p, rawBytes) in items) {
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
                    val result = runCatching {
                        db!!.save(pkg, received, p.time, conversationTitle, p.sender,
                            body ?: "", photo, identity.key, kind.name)
                    }
                    when (val outcome = result.getOrNull()) {
                        InboxDatabase.SaveResult.NEW -> CaptureDiagnostics.note(
                            appContext, pkg, "DATABASE_COMMIT",
                            CaptureDiagnostics.DB_INSERT_SUCCESS, null, startTs, trace)
                        InboxDatabase.SaveResult.DUPLICATE -> CaptureDiagnostics.note(
                            appContext, pkg, "DATABASE_COMMIT",
                            CaptureDiagnostics.DB_DUPLICATE, null, startTs, trace)
                        is InboxDatabase.SaveResult.FAILURE -> CaptureDiagnostics.note(
                            appContext, pkg, "DATABASE_COMMIT",
                            CaptureDiagnostics.DB_INSERT_FAILED,
                            outcome.cause.javaClass.simpleName, startTs, trace)
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
        worker.shutdown()
        super.onDestroy()
    }
}
