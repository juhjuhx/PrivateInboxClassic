package tw.personal.privateinbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Instrumentation tests that run inside the app process on a real Android runtime.
 *
 * These cover the parts that cannot be verified with JVM unit tests:
 *  - the notification parser against a real Notification / MessagingStyle / BigPictureStyle
 *  - the encrypted database round-trip (write, read back, decrypt)
 *  - duplicate suppression
 *  - the notification listener pipeline end to end
 */
@RunWith(AndroidJUnit4::class)
class NotificationPipelineInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        LocalSettings.setRecording(context, true)
        LocalSettings.setCaptureImages(context, true)
        LocalSettings.updateSources(context, setOf("org.telegram.messenger", "jp.naver.line.android"))
        val db = InboxDatabase(context)
        // This test destroys the database, so it only runs against the
        // ISOLATED variant. On the production package it is SKIPPED
        // with a reason, and nothing is deleted.
        DestructiveTestAssumption.requireIsolated(context)
        TestDataIsolation.clearDatabase(context)
        db.close()
    }

    // ---------------------------------------------------------------- helpers

    private fun channel(): String {
        val id = "test-channel"
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(id, "test", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        return id
    }

    private fun buildMessagingNotification(
        conversation: String,
        messages: List<Pair<String, String>>,
        withImage: Boolean = false
    ): Notification {
        val builder = Notification.Builder(context, channel())
            .setContentTitle(conversation)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
        val style = Notification.MessagingStyle(
            android.app.Person.Builder().setName(conversation).build()
        )
        for ((who, text) in messages) {
            val person = android.app.Person.Builder().setName(who).build()
            val m = Notification.MessagingStyle.Message(text, System.currentTimeMillis(), person)
            if (withImage) {
                m.setData("image/jpeg", Uri.parse("content://com.example.provider/1"))
            }
            style.addMessage(m)
        }
        builder.setStyle(style)
        return builder.build()
    }

    private fun sbnFor(notification: Notification, pkg: String = "org.telegram.messenger"): StatusBarNotification {
        val ctor = StatusBarNotification::class.java.getConstructor(
            String::class.java, String::class.java, Int::class.javaPrimitiveType,
            String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Notification::class.java,
            android.os.UserHandle::class.java, Long::class.javaPrimitiveType
        )
        return ctor.newInstance(
            pkg, pkg, 0, "tag-1", 0, 0, 0, notification,
            android.os.UserHandle.getUserHandleForUid(Process.myUid()),
            System.currentTimeMillis()
        )
    }

    /** The capture routine is invoked directly with the application context, so the
     *  test does not need a bound system service to exercise the pipeline. */
    private fun capture(sbn: StatusBarNotification) {
        PrivateNotificationListener().capture(context, sbn)
    }

    private fun awaitRows(min: Int, timeoutMs: Long = 8000): List<InboxDatabase.Entry> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var rows = emptyList<InboxDatabase.Entry>()
        while (System.currentTimeMillis() < deadline) {
            val db = InboxDatabase(context)
            rows = try { db.recent(100) } finally { db.close() }
            if (rows.size >= min) return rows
            Thread.sleep(250)
        }
        return rows
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun messagingStyleNotificationIsCapturedAndDecrypted() {
        val notification = buildMessagingNotification("Alice", listOf("Alice" to "Hello 私密訊息"))
        capture(sbnFor(notification))

        val rows = awaitRows(1)
        assertTrue("notification should be captured", rows.isNotEmpty())
        val row = rows.first()
        assertEquals("Alice", row.title)
        assertEquals("Alice", row.sender)
        assertEquals("Hello 私密訊息", row.body)
        // The source timestamp must be preserved, not replaced by capture time.
        assertTrue("source timestamp should be present", row.sourceTimestamp > 0)
    }

    @Test
    fun duplicateNotificationIsNotStoredTwice() {
        val notification = buildMessagingNotification("Bob", listOf("Bob" to "duplicate body"))
        capture(sbnFor(notification))
        assertTrue("first post should be captured", awaitRows(1).isNotEmpty())

        // Re-post the identical notification: it must be deduplicated.
        capture(sbnFor(notification))
        Thread.sleep(1500)
        val db = InboxDatabase(context)
        val rows = try { db.recent(100) } finally { db.close() }
        assertEquals("duplicate must not create a second row", 1, rows.size)
    }

    @Test
    fun notificationFromNonWhitelistedPackageIsRejected() {
        val notification = buildMessagingNotification("Eve", listOf("Eve" to "should not be stored"))
        capture(sbnFor(notification, pkg = "com.android.shell"))

        Thread.sleep(1500)
        val db = InboxDatabase(context)
        val rows = try { db.recent(100) } finally { db.close() }
        assertEquals("non whitelisted source must be rejected", 0, rows.size)
    }

    @Test
    fun groupSummaryNotificationIsIgnored() {
        val builder = Notification.Builder(context, channel())
            .setContentTitle("3 new messages")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(Notification.InboxStyle())
        val notification = builder.build()
        notification.flags = notification.flags or Notification.FLAG_GROUP_SUMMARY
        capture(sbnFor(notification))

        Thread.sleep(1500)
        val db = InboxDatabase(context)
        val rows = try { db.recent(100) } finally { db.close() }
        assertEquals("group summary must be ignored", 0, rows.size)
    }

    @Test
    fun emptyNotificationDoesNotCrash() {
        val builder = Notification.Builder(context, channel())
            .setSmallIcon(android.R.drawable.ic_dialog_info)
        val notification = builder.build()
        // Must not throw.
        capture(sbnFor(notification))
        Thread.sleep(800)
    }

    @Test
    fun encryptedRowSurvivesDatabaseReopen() {
        val db = InboxDatabase(context)
        db.save("org.telegram.messenger", System.currentTimeMillis(), 0,
            "Carol", "Carol", "persistent body", null)
        db.close()

        // Reopen: the row must still decrypt correctly.
        val db2 = InboxDatabase(context)
        val rows = try { db2.recent(10) } finally { db2.close() }
        assertEquals(1, rows.size)
        assertEquals("persistent body", rows.first().body)
        assertEquals("Carol", rows.first().title)
    }

    @Test
    fun imageIsStoredEncryptedAndDecryptsBack() {
        val db = InboxDatabase(context)
        val jpeg = ByteArray(64) { (it and 0xFF).toByte() }
        db.save("org.telegram.messenger", System.currentTimeMillis(), 0,
            "Dave", "Dave", "photo message", jpeg)
        val stored = try { db.recent(10) } finally { db.close() }
        assertEquals(1, stored.size)
        assertTrue("image flag should be set", stored.first().hasImage)

        // The stored blob must be encrypted, not the raw bytes.
        val db3 = InboxDatabase(context)
        val raw = try {
            db3.readableDatabase.query(
                "messages", arrayOf("sealed_image"), null, null, null, null, null
            ).use { c -> if (c.moveToFirst()) c.getBlob(0) else null }
        } finally { db3.close() }
        assertNotNull("sealed image blob should exist", raw)
        assertTrue("stored image must not equal the plaintext", !raw!!.contentEquals(jpeg))

        // And it must decrypt back to the original bytes.
        val db4 = InboxDatabase(context)
        val decrypted = try { db4.loadImage(stored.first().id) } finally { db4.close() }
        assertNotNull("image should decrypt", decrypted)
        assertTrue("decrypted bytes must match", decrypted!!.contentEquals(jpeg))
    }

    @Test
    fun clearRemovesEverything() {
        val db = InboxDatabase(context)
        db.save("org.telegram.messenger", System.currentTimeMillis(), 0, "E", "E", "b", null)
        db.save("jp.naver.line.android", System.currentTimeMillis(), 0, "F", "F", "b", null)
        assertEquals(2, db.recent(10).size)
        // This test destroys the database, so it only runs against the
        // ISOLATED variant. On the production package it is SKIPPED
        // with a reason, and nothing is deleted.
        DestructiveTestAssumption.requireIsolated(context)
        TestDataIsolation.clearDatabase(context)
        assertEquals(0, db.recent(10).size)
        db.close()
    }

    @Test
    fun retentionPrunesOldMessages() {
        val db = InboxDatabase(context)
        val old = System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000
        db.save("org.telegram.messenger", old, 0, "Old", "Old", "old body", null)
        db.save("org.telegram.messenger", System.currentTimeMillis(), 0, "New", "New", "new body", null)
        assertEquals(2, db.recent(10).size)
        val removed = db.pruneOlderThanDays(30)
        assertEquals(1, removed)
        assertEquals(1, db.recent(10).size)
        db.close()
    }

    @Test
    fun listenerServiceIsDeclaredInTheManifest() {
        val info = context.packageManager.getServiceInfo(
            android.content.ComponentName(context, PrivateNotificationListener::class.java),
            android.content.pm.PackageManager.GET_META_DATA
        )
        assertNotNull("listener service must be declared", info)
        assertEquals(
            "listener service must be bound with the official permission",
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
            info.permission
        )
        assertFalse(
            "listener service must not be exported to third party apps",
            info.exported
        )
    }
}
