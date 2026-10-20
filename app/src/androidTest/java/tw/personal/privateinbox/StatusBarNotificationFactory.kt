package tw.personal.privateinbox

import android.app.Notification
import android.os.Process
import android.service.notification.StatusBarNotification

/**
 * Builds a real [StatusBarNotification] for tests.
 *
 * The constructor is not part of the public API surface on every platform
 * version, so it is resolved reflectively. If a platform version changes the
 * signature, [create] returns null and the caller skips that assertion rather
 * than failing for an unrelated reason.
 */
object StatusBarNotificationFactory {

    fun create(notification: Notification, packageName: String): StatusBarNotification? {
        return try {
            val ctor = StatusBarNotification::class.java.getConstructor(
                String::class.java, String::class.java, Int::class.javaPrimitiveType,
                String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Notification::class.java,
                android.os.UserHandle::class.java, Long::class.javaPrimitiveType
            )
            ctor.newInstance(
                packageName, packageName, 0, "tag-test", 0, 0, 0, notification,
                android.os.UserHandle.getUserHandleForUid(Process.myUid()),
                System.currentTimeMillis()
            )
        } catch (_: Throwable) {
            null
        }
    }
}
