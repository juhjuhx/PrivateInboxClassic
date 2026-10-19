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

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || !LocalSettings.isRecording(this)) return
        val pkg = sbn.packageName
        if (!CapturePolicy.allowed(pkg, LocalSettings.selected(this))) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = sbn.notification.extras ?: return
        val conversationTitle = CapturePolicy.normalize(
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        ) ?: CapturePolicy.nameFor(pkg)
        val previews = previews(extras)
        if (previews.isEmpty()) return
        val captureImages = LocalSettings.captureImages(this)
        // Only the raw byte read has to happen inside the callback: the provider may
        // revoke the ephemeral URI grant as soon as the callback returns. Decoding
        // and downscaling are CPU bound and happen on the worker thread below.
        val pictureBytes = if (captureImages) readPictureBytes(extras) else null
        val received = System.currentTimeMillis()
        val window = previews.takeLast(25)
        val items = window.mapIndexed { idx, p ->
            val last = idx == window.lastIndex
            val fromMessage = if (captureImages && p.mediaMime?.startsWith("image/") == true)
                ImageCapture.readBytes(this, p.mediaUri) else null
            p to (fromMessage ?: pictureBytes?.takeIf { last })
        }
        worker.execute {
            if (!LocalSettings.isRecording(applicationContext)) return@execute
            if (!CapturePolicy.allowed(pkg, LocalSettings.selected(applicationContext))) return@execute
            val db = InboxDatabase(applicationContext)
            try {
                for ((p, rawBytes) in items) {
                    // Decoding + JPEG re-encode is CPU bound; keep it off the callback.
                    val photo = rawBytes?.let { ImageCapture.toPreview(it, p.mediaMime) }
                    val body = CapturePolicy.normalize(p.body) ?: if (photo != null) "📷 照片" else continue
                    db.save(pkg, received, p.time, conversationTitle, p.sender, body, photo)
                }
            } catch (_: Exception) {
                // Do not log messages, photo contents or remote provider paths.
            } finally { db.close() }
        }
    }

    /** Read the notification's own picture bytes (BigPictureStyle bitmap or icon). */
    private fun readPictureBytes(extras: Bundle): ByteArray? {
        val raw = if (Build.VERSION.SDK_INT >= 33)
            extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
        else @Suppress("DEPRECATION") (extras.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap)
        if (raw != null) return ImageCapture.asPreview(raw)
        if (Build.VERSION.SDK_INT >= 31) {
            val icon = if (Build.VERSION.SDK_INT >= 33)
                extras.getParcelable(Notification.EXTRA_PICTURE_ICON, Icon::class.java)
            else @Suppress("DEPRECATION") (extras.getParcelable(Notification.EXTRA_PICTURE_ICON) as? Icon)
            if (icon != null) {
                val drawable = runCatching { icon.loadDrawable(this) }.getOrNull()
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
