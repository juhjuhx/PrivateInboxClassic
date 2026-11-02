package tw.personal.privateinbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G3-A — the REAL capture pipeline, not a new'd resolver.
 *
 * Every test here drives `PrivateNotificationListener.capture()` — the same
 * entry point the system's `onNotificationPosted` calls — and asserts on the
 * rows the production write path produced, through the production reader.
 *
 * The listener posts through its own worker thread, so results are awaited by
 * polling the committed state (the same discipline the burst suite uses).
 */
@RunWith(AndroidJUnit4::class)
class CapturePipelineIntegrationInstrumentedTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val pkg = "org.telegram.messenger"
    private val pkgOther = "com.whatsapp"

    @Before
    fun setUp() {
        DestructiveTestAssumption.requireIsolated(ctx)
        LocalSettings.setRecording(ctx, true)
        LocalSettings.setCaptureImages(ctx, false)
        LocalSettings.updateSources(ctx, setOf(pkg, pkgOther))
        TestDataIsolation.clearDatabase(ctx)
    }

    @After
    fun tearDown() {
        TestDataIsolation.tryClearDatabase(ctx)
    }

    // ---------------------------------------------------------------- helpers

    private fun notification(
        channel: String, title: String, body: String, shortcut: String?
    ): Notification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channel, "t", NotificationManager.IMPORTANCE_HIGH)
        )
        val builder = Notification.Builder(ctx, channel)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentText(body)
        if (shortcut != null) builder.setShortcutId(shortcut)
        return builder.build()
    }

    private fun sbn(notification: Notification, pkgName: String): StatusBarNotification =
        StatusBarNotificationFactory.create(notification, pkgName)!!

    /** Drive the real capture path and wait for the row to land. */
    private fun captureAndWait(
        pkgName: String, title: String, body: String,
        shortcut: String?, channel: String,
        timeoutMs: Long = 8000
    ): PipelineState {
        // T01-A fix (v2): wait for a STABLE observable state instead of a
        // wrong-field match. Stability = the (messageCount, linkCount) pair is
        // unchanged for 3 consecutive samples, OR the deadline passed. This works
        // for a fresh capture (count grows), a re-delivery (nothing changes —
        // dedup), and a rename (count grows, links grow), without guessing which.
        var lastCount = -1
        var lastLinks = -1
        var stable = 0
        val listener = PrivateNotificationListener()
        listener.capture(ctx, sbn(notification(channel, title, body, shortcut), pkgName))
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var state = pipelineState()
        while (System.nanoTime() < deadline) {
            state = pipelineState()
            if (state.messageCount == lastCount && state.links == lastLinks) {
                stable++
                if (stable >= 3) break
            } else {
                stable = 0
            }
            lastCount = state.messageCount
            lastLinks = state.links
            Thread.sleep(150)
        }
        listener.onDestroy()
        return pipelineState()
    }

    /** Everything the pipeline produced, read through production paths. */
    private data class PipelineState(
        val messageCount: Int,
        val rows: List<Row>,
        val links: Int
    ) {
        fun linkedFor(title: String): Row? = rows.firstOrNull { it.key == title }
    }

    private data class Row(
        val id: Long, val title: String, val key: String,
        val fingerprint: String, val conversationId: String?
    )

    private fun pipelineState(): PipelineState {
        val db = InboxDatabase(ctx)
        val rows = ArrayList<Row>()
        var links = 0
        try {
            db.readableDatabase.rawQuery(
                "SELECT m.id, m.conversation_key, m.fingerprint, " +
                    "l.conversation_id FROM messages m " +
                    "LEFT JOIN message_conversation l ON l.message_id = m.id " +
                    "ORDER BY m.id",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val conv = if (c.isNull(3)) null else c.getString(3)
                    if (conv != null) links++
                    rows.add(
                        Row(
                            c.getLong(0),
                            // title lives in the sealed blob; use the legacy key
                            // suffix as the test handle (synthetic titles only)
                            c.getString(1), c.getString(1),
                            c.getBlob(2).joinToString("") { "%02x".format(it) },
                            conv
                        )
                    )
                }
            }
        } finally { db.close() }
        return PipelineState(rows.size, rows, links)
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun newMessageIsStoredAndLinkedThroughTheRealPipeline() {
        val state = captureAndWait(pkg, "Alice", "hello from the real pipeline", "sc-pipe-1", "p1")
        assertEquals("one message stored", 1, state.messageCount)
        assertEquals(
            "and its canonical link was created by the listener itself",
            1, state.links
        )
        assertNotNull("the link points at a conversation", state.rows[0].conversationId)
        assertTrue(
            "the conversation id is a canonical cv_ id",
            state.rows[0].conversationId!!.startsWith("cv_")
        )
    }

    @Test
    fun redeliveryOfTheSameMessageDedupsAndDoesNotDuplicateTheLink() {
        val first = captureAndWait(pkg, "Alice", "hello dedup", "sc-pipe-2", "p2")
        assertEquals(1, first.messageCount)

        // Re-deliver the IDENTICAL notification (same shortcut, title, body).
        val second = captureAndWait(pkg, "Alice", "hello dedup", "sc-pipe-2", "p2")
        assertEquals(
            "the re-delivery must not add a message row",
            first.messageCount, second.messageCount
        )
        assertEquals(
            "and must not duplicate the canonical link",
            first.links, second.links
        )
    }

    @Test
    fun renameKeepsOneCanonicalConversation() {
        val before = captureAndWait(pkg, "Alice", "before rename", "sc-pipe-3", "p3")
        val after = captureAndWait(pkg, "Alice Chen", "after rename", "sc-pipe-3", "p3")

        // Both messages exist (the fingerprint input embeds the title — the
        // documented limitation), but the canonical conversation is ONE.
        val store = ConversationStore(ctx)
        try {
            assertEquals(
                "the rename must not split the canonical conversation",
                1, store.conversationCount(pkg)
            )
        } finally { store.close() }
        assertEquals(
            "both messages are linked to a canonical id",
            before.links + 1, after.links
        )
        assertEquals(
            "and both links point at the SAME conversation",
            after.rows[0].conversationId, after.rows[1].conversationId
        )
    }

    @Test
    fun sameTitleDifferentShortcutsAreTwoConversations() {
        captureAndWait(pkg, "家族", "group A", "sc-ga", "p4a")
        captureAndWait(pkg, "家族", "group B", "sc-gb", "p4b")

        val state = pipelineState()
        assertEquals(2, state.messageCount)
        assertNotNull(state.rows[0].conversationId)
        assertNotNull(state.rows[1].conversationId)
        assertNotEquals(
            "same title, different shortcut: two conversations",
            state.rows[0].conversationId, state.rows[1].conversationId
        )
        val store = ConversationStore(ctx)
        try {
            assertEquals(2, store.conversationCount(pkg))
        } finally { store.close() }
    }

    @Test
    fun sameTitleInDifferentPackagesNeverMerges() {
        captureAndWait(pkg, "同名", "tg body", "sc-x", "p5a")
        captureAndWait(pkgOther, "同名", "wa body", "sc-x", "p5b")

        val state = pipelineState()
        assertEquals(2, state.messageCount)
        assertNotEquals(
            "different packages never merge",
            state.rows[0].conversationId, state.rows[1].conversationId
        )
    }

    @Test
    fun noShortcutStillStoresAndLinksAmbiguously() {
        val state = captureAndWait(pkg, "沒有 shortcut 的聊天", "body", null, "p6")
        assertEquals("the message is stored", 1, state.messageCount)
        assertNotNull(
            "even a title-only conversation gets a canonical link",
            state.rows[0].conversationId
        )
        val store = ConversationStore(ctx)
        try {
            val cid = state.rows[0].conversationId!!
            assertTrue(
                "and it is flagged ambiguous",
                store.isAmbiguous(cid)
            )
        } finally { store.close() }
    }

    @Test
    fun listenerRestartDoesNotBreakLinking() {
        // Capture, destroy the listener (simulates service restart), capture again.
        val listener1 = PrivateNotificationListener()
        listener1.capture(ctx, sbn(notification("p7", "Restart", "before restart", "sc-r1"), pkg))
        Thread.sleep(1200)
        listener1.onDestroy()

        val listener2 = PrivateNotificationListener()
        listener2.capture(ctx, sbn(notification("p7", "Restart", "after restart", "sc-r1"), pkg))
        Thread.sleep(1200)
        listener2.onDestroy()

        val state = pipelineState()
        assertTrue("messages from both captures stored", state.messageCount >= 2)
        val store = ConversationStore(ctx)
        try {
            assertEquals(
                "a service restart must not split the conversation",
                1, store.conversationCount(pkg)
            )
        } finally { store.close() }
    }

    @Test
    fun pendingLinksAreReconciledAfterRestart() {
        // Store a message and remove its link (simulating a link failure).
        captureAndWait(pkg, "Reconcile", "body", "sc-rc", "p8")
        val db = InboxDatabase(ctx)
        try {
            db.writableDatabase.execSQL("DELETE FROM message_conversation")
        } finally { db.close() }

        assertEquals("link removed", 0, pipelineState().links)

        // A fresh listener reconciles pending links on its first worker action.
        val listener = PrivateNotificationListener()
        listener.capture(ctx, sbn(notification("p8", "Reconcile", "trigger reconcile", "sc-rc"), pkg))
        Thread.sleep(1500)
        listener.onDestroy()

        assertTrue(
            "the reconcile pass must restore the links",
            pipelineState().links >= 2
        )
    }

    @Test
    fun burstOfNotificationsAllLandInTheirOwnConversations() {
        val listener = PrivateNotificationListener()
        repeat(10) { i ->
            listener.capture(
                ctx, sbn(notification("p9", "Convo$i", "burst body $i", "sc-burst-$i"), pkg)
            )
        }
        Thread.sleep(3000)
        listener.onDestroy()

        val state = pipelineState()
        assertEquals("all 10 messages stored", 10, state.messageCount)
        assertEquals("all 10 linked", 10, state.links)
        val store = ConversationStore(ctx)
        try {
            assertEquals(
                "10 distinct conversations",
                10, store.conversationCount(pkg)
            )
        } finally { store.close() }
    }
}
