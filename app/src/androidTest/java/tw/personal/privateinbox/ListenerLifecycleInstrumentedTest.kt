package tw.personal.privateinbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Person
import android.content.Context
import android.os.Build
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Direct-parser listener test: builds a StatusBarNotification and calls the service's
 * capture() on a real (unbound) instance, then asserts the pipeline stage outcomes.
 *
 * This is a DIRECT_PARSER_TEST, not a system E2E test — it proves parsing/storage works
 * but does not prove the Android system routes real notifications to the listener.
 * System routing is covered by SelfTestSystemNotificationInstrumentedTest instead.
 */
@RunWith(AndroidJUnit4::class)
class ListenerLifecycleInstrumentedTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun makeSbn(pkg: String, conversation: String, body: String, tsMs: Long): StatusBarNotification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val channel = "lc-test"
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(channel, "lc", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val person = Person.Builder().setName(conversation).build()
        val style = Notification.MessagingStyle(person)
        style.addMessage(Notification.MessagingStyle.Message(body, tsMs, person))
        val notification = Notification.Builder(ctx, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(conversation)
            .setContentText(body)
            .setStyle(style)
            .build()
        // Same 10-arg constructor the existing NotificationPipelineInstrumentedTest uses
        // (the 14-arg variant was removed on newer APIs).
        val ctor = StatusBarNotification::class.java.getConstructor(
            String::class.java, String::class.java, Int::class.javaPrimitiveType,
            String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Notification::class.java,
            android.os.UserHandle::class.java, Long::class.javaPrimitiveType
        )
        return ctor.newInstance(
            pkg, pkg, 0, channel, 0, 0, 0, notification,
            android.os.UserHandle.getUserHandleForUid(Process.myUid()),
            System.currentTimeMillis()
        )
    }

    @Test
    fun connectionStateHooksRunWithoutCrashing() {
        val service = PrivateNotificationListener()
        service.onListenerConnected()
        service.onListenerDisconnected()
        assertTrue(true)
    }

    @Test
    fun allowedSourceNotificationStoredViaDirectCapture() {
        val pkg = "org.telegram.messenger"
        LocalSettings.setRecording(ctx, true)
        val sel = LocalSettings.selected(ctx).toMutableSet()
        sel.add(pkg)
        LocalSettings.updateSources(ctx, sel)

        val service = PrivateNotificationListener()
        val ts = System.currentTimeMillis()
        service.capture(ctx, makeSbn(pkg, "LC Test", "lc probe $ts", ts))

        val db = InboxDatabase(ctx)
        val row = try {
            // The wait is generous because the instrumentation tests share one
            // app process: a loaded machine makes the single writer slower,
            // and the row has to be written by that writer.
            val deadline = System.currentTimeMillis() + 45_000
            var found: InboxDatabase.Entry? = null
            while (System.currentTimeMillis() < deadline) {
                found = db.recent(500).firstOrNull { it.body == "lc probe $ts" }
                if (found != null) break
                Thread.sleep(300)
            }
            found
        } finally { db.close() }
        assertNotNull("direct capture of an allowed source must store the row", row)
    }

    @Test
    fun disallowedSourceRejectedViaSourcePolicy() {
        val pkg = "com.example.bank"
        LocalSettings.setRecording(ctx, true)
        val sel = LocalSettings.selected(ctx)
        assertTrue(pkg !in sel)
        val service = PrivateNotificationListener()
        val ts = System.currentTimeMillis()
        service.capture(ctx, makeSbn(pkg, "Bank", "bank probe $ts", ts))
        Thread.sleep(TimeUnit.SECONDS.toMillis(3))

        val db = InboxDatabase(ctx)
        val found = try { db.recent(500).any { it.body == "bank probe $ts" } } finally { db.close() }
        assertEquals("unlisted source must never be stored", false, found)
    }
}
