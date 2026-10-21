package tw.personal.privateinbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Person
import android.content.Context
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger

/**
 * A synthetic notification sender, used to drive the capture pipeline under burst
 * conditions.
 *
 * The notifications are posted through the platform's [NotificationManager], so
 * the system routes them to the bound NotificationListenerService exactly as a
 * messaging app's notifications would be routed.
 *
 * The poster is the APP package itself. The established self-test path
 * ([SelfTestNotifier.post]) already proves the round trip works from the app
 * package, so no separate helper application is needed.
 *
 * No real account and no real chat content is involved: every message body is
 * a synthetic marker.
 */
class BurstNotifier {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val nm: NotificationManager get() = ctx.getSystemService(NotificationManager::class.java)

    /** A counter that makes every synthetic body unique across the whole run. */
    private val seq = AtomicInteger(0)

    companion object {
        const val CHANNEL_ID = "burst_test"
        const val NOTIFICATION_ID_BASE = 0x4200

        /** The conversation title used by the synthetic group. */
        const val GROUP_TITLE = "Synthetic Burst Group"

        /** The sender shown in every synthetic message. */
        const val SENDER = "Synthetic Sender"
    }

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "burst test", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    /**
     * Post a group notification whose MessagingStyle carries [messageCount]
     * messages, where the messages are numbered [firstIndex] .. [firstIndex + count - 1].
     *
     * @param notificationId the notification id (one per conversation)
     * @param postTime the message timestamp. Passing the SAME value for repeated
     *        posts models a source that does not provide a per-message timestamp.
     */
    fun postGroupUpdate(
        notificationId: Int,
        conversationTitle: String = GROUP_TITLE,
        messageCount: Int,
        firstIndex: Int = 0,
        postTime: Long = 0L,
        asSummary: Boolean = false,
        groupKey: String? = null
    ): List<String> {
        ensureChannel()
        val style = Notification.MessagingStyle(
            Person.Builder().setName(SENDER).setKey("synthetic-sender").build()
        )
        val bodies = ArrayList<String>()
        for (i in 0 until messageCount) {
            val index = firstIndex + i
            val body = "synthetic-burst-message #$index"
            bodies += body
            val timestamp = if (postTime > 0) postTime else 1_700_000_000_000L + index
            style.addMessage(
                Notification.MessagingStyle.Message(
                    body, timestamp, Person.Builder().setName(SENDER).build()
                )
            )
        }
        // The conversation title is set on the STYLE as well as on the
        // builder. A MessagingStyle carries its own conversation title, and
        // when it is absent the platform falls back to a default one, so two
        // different conversations would be indistinguishable. Real messaging
        // apps set it on the style.
        style.conversationTitle = conversationTitle
        val builder = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(conversationTitle)
            .setContentText(bodies.last())
            .setStyle(style)
        // The group is only applied when the caller asks for it. A grouped
        // notification behaves differently in the system's routing, so the
        // ungrouped form is the default and matches the established self-test
        // path.
        if (groupKey != null) builder.setGroup(groupKey)
        if (asSummary) {
            builder.setGroupSummary(true)
        }
        nm.notify(notificationId, builder.build())
        return bodies
    }

    /** Post one notification whose MessagingStyle holds two identical bodies. */
    fun postTwoIdenticalMessages(
        notificationId: Int,
        conversationTitle: String = "Synthetic Duplicate Group",
        body: String = "synthetic-duplicate-text"
    ) {
        ensureChannel()
        val person = Person.Builder().setName(SENDER).build()
        val style = Notification.MessagingStyle(person)
        style.conversationTitle = conversationTitle
        // Same text, but two separate messages.
        style.addMessage(Notification.MessagingStyle.Message(body, 1_700_000_000_000L, person))
        style.addMessage(Notification.MessagingStyle.Message(body, 1_700_000_000_001L, person))
        nm.notify(
            notificationId,
            Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(conversationTitle)
                .setStyle(style)
                .build()
        )
    }

    /** Post a plain (non-MessagingStyle) notification with N text lines. */
    fun postInboxUpdate(notificationId: Int, lineCount: Int, groupKey: String? = null) {
        ensureChannel()
        val style = Notification.InboxStyle()
        for (i in 0 until lineCount) {
            style.addLine("synthetic-inbox-line #$i")
        }
        val builder = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Synthetic Inbox")
            .setStyle(style)
        if (groupKey != null) builder.setGroup(groupKey)
        nm.notify(notificationId, builder.build())
    }

    /** Cancel a notification, simulating the source removing it. */
    fun cancel(notificationId: Int) {
        nm.cancel(notificationId)
    }

    /** The notifications the system currently considers active. */
    @Suppress("DEPRECATION")
    fun activeNotifications(): List<android.service.notification.StatusBarNotification> {
        val list: Array<android.service.notification.StatusBarNotification>? =
            if (Build.VERSION.SDK_INT >= 23) nm.activeNotifications else null
        return list?.filterNotNull() ?: emptyList()
    }

    /** How many notifications the app currently has active. */
    fun activeNotificationCount(): Int = activeNotifications().size
}
