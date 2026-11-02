package tw.personal.privateinbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Person
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/** Posts the fixed self-test notification through the real Android notification
 *  pipeline (NotificationManager -> system -> NotificationListenerService), so an
 *  end-to-end capture can be verified without a real LINE / Messenger account.
 *
 *  On Android 13+ the caller must hold POST_NOTIFICATIONS; that permission is used
 *  ONLY for this app's own notifications and is not required for capturing LINE etc.
 */
object SelfTestNotifier {
    const val CHANNEL_ID = "self_test"
    const val NOTIFICATION_ID = 0x51CE
    const val TITLE = "Private Inbox Self Test"
    const val SENDER = "Test User"
    const val BODY = "Hello Private Inbox"

    /** True when the app may post its own notifications on this Android version. */
    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    /** Post the fixed test notification. Returns false on Android 13+ when
     *  POST_NOTIFICATIONS has not been granted. */
    fun post(ctx: Context): Boolean = post(ctx, System.currentTimeMillis())

    /** Post with an explicit message timestamp so repeated posts of the identical
     *  self-test notification produce a stable dedup fingerprint.
     *
     *  [bodyOverride] lets a caller tag the message with a unique marker so the
     *  resulting row can be positively identified as belonging to *this* run,
     *  instead of matching a stale row left over from an earlier run. */
    fun post(ctx: Context, fixedTimeMs: Long, bodyOverride: String? = null): Boolean {
        if (!canPost(ctx)) return false
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Private Inbox Self Test",
                    NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val body = bodyOverride ?: BODY
        val person = Person.Builder().setName(SENDER).build()
        val style = Notification.MessagingStyle(person)
        style.addMessage(Notification.MessagingStyle.Message(
            body, fixedTimeMs, person))
        val notification = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_classic)
            .setContentTitle(TITLE)
            .setContentText(body)
            .setStyle(style)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
        return true
    }

    /**
     * IR-06: post an ARBITRARY self-test notification (id/title/body/shortcut)
     * through the real NotificationManager.
     *
     * The fixed [post] above always uses the same title and channel, which is
     * enough for a smoke test but not for the E2E suite: that suite needs a
     * unique marker body per run and a shortcut id so the canonical resolver
     * has a STRONG signal to link on.
     *
     * Returns false when the app may not post (Android 13+ without
     * POST_NOTIFICATIONS) — the caller must treat that as a real failure, never
     * as a pass.
     */
    fun postCustom(
        ctx: Context,
        id: Int,
        title: String,
        body: String,
        shortcutId: String?
    ): Boolean {
        if (!canPost(ctx)) return false
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Private Inbox Self Test",
                    NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val person = Person.Builder().setName(SENDER).build()
        val style = Notification.MessagingStyle(person)
        style.addMessage(Notification.MessagingStyle.Message(body, System.currentTimeMillis(), person))
        val builder = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_classic)
            .setContentTitle(title.ifEmpty { TITLE })
            .setContentText(body)
            .setStyle(style)
            .setAutoCancel(true)
        // The shortcut id is what lets the resolver produce a STRONG alias; it
        // travels as the notification's short-cut id, exactly like a real
        // messaging app sets it.
        if (!shortcutId.isNullOrEmpty()) {
            builder.setShortcutId(shortcutId)
        }
        nm.notify(id, builder.build())
        return true
    }

    /** Whether the user has granted this app notification access (the listener
     *  permission) — the precondition for the self-test notification to arrive. */
    fun listenerPermissionGranted(ctx: Context): Boolean =
        Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?.contains(ctx.packageName) == true
}
