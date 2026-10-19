package tw.personal.privateinbox

import android.app.Notification
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
        val previews = previews(extras, sbn.postTime)
        if (previews.isEmpty()) return
        val captureImages = LocalSettings.captureImages(this)
        // Read ephemeral content URIs on the listener callback, before providers revoke them.
        val picture = if (captureImages) runCatching { ImageCapture.fromNotification(this, extras) }.getOrNull() else null
        val received = System.currentTimeMillis()
        val items = previews.takeLast(25).mapIndexed { idx, p ->
            val image = if (!captureImages) null else {
                val fromMessage = if (p.mediaMime?.startsWith("image/") == true)
                    ImageCapture.fromUri(this, p.mediaUri) else null
                fromMessage ?: picture?.takeIf { idx == previews.takeLast(25).lastIndex }
            }
            p to image
        }
        worker.execute {
            if (!LocalSettings.isRecording(applicationContext)) return@execute
            if (!CapturePolicy.allowed(pkg, LocalSettings.selected(applicationContext))) return@execute
            val db = InboxDatabase(applicationContext)
            try {
                for ((p, photo) in items) {
                    val body = CapturePolicy.normalize(p.body) ?: if (photo != null) "📷 照片" else continue
                    db.save(pkg, received, p.time, conversationTitle, p.sender, body, photo)
                }
            } catch (_: Exception) {
                // Do not log messages, photo contents or remote provider paths.
            } finally { db.close() }
        }
    }

    @Suppress("DEPRECATION")
    private fun previews(extras: Bundle, time: Long): List<Preview> {
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
            val compat = bundles.mapNotNull { it as? Bundle }.mapNotNull { m ->
                val body = CapturePolicy.normalize(m.getCharSequence("text")?.toString()).orEmpty()
                val uri = m.getParcelable("uri") as? Uri
                if (body.isEmpty() && uri == null) null else Preview(
                    body, CapturePolicy.normalize(m.getCharSequence("sender")?.toString()).orEmpty(),
                    m.getLong("time", time), uri, m.getString("type")
                )
            }
            if (compat.isNotEmpty()) return compat
        }
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { CapturePolicy.normalize(it?.toString()) }
            ?.takeLast(15).orEmpty()
        if (lines.isNotEmpty()) return lines.map { Preview(it, "", time) }
        val text = CapturePolicy.normalize(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        )
        // BigPicture notifications sometimes have no text; save the picture-only entry.
        return listOf(Preview(text.orEmpty(), "", time))
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
