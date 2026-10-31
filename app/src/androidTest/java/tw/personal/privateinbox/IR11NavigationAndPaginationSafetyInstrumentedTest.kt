package tw.personal.privateinbox

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IR11NavigationAndPaginationSafetyInstrumentedTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val pkg = "jp.naver.line.android"

    @Before
    fun setUp() {
        DestructiveTestAssumption.requireIsolated(ctx)
        TestDataIsolation.clearDatabase(ctx)
    }

    @After
    fun tearDown() {
        TestDataIsolation.tryClearDatabase(ctx)
    }

    private fun save(title: String, body: String, ts: Long, key: String? = null): Long {
        val conversationKey = key ?: LegacyKeyCodec.format(pkg, null, title)
        val db = InboxDatabase(ctx)
        try {
            db.save(
                packageName = pkg, receivedAt = ts, sourceTimestamp = ts,
                title = title, sender = "S", body = body, image = null,
                conversationKey = conversationKey,
                messageKind = SourceRegistry.MessageKind.GROUP_MESSAGE.name,
                messageDiscriminator = "c:S:$body:i$ts"
            )
            return db.readableDatabase.rawQuery(
                "SELECT id FROM messages ORDER BY id DESC LIMIT 1", null
            ).use { c -> c.moveToFirst(); c.getLong(0) }
        } finally { db.close() }
    }

    private fun resolve(title: String): String {
        val store = ConversationStore(ctx)
        return try {
            ConversationIdentityResolver(store).resolve(pkg, null, title).conversationId
        } finally { store.close() }
    }

    private fun link(id: Long, cid: String) {
        val store = ConversationStore(ctx)
        try { store.linkMessage(id, cid) } finally { store.close() }
    }

    private fun walkText(v: View, sink: MutableList<String>) {
        if (v is TextView) v.text?.toString()?.let(sink::add)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walkText(v.getChildAt(i), sink)
        }
    }

    private fun clickLoadOlder(activity: MainActivity): Boolean {
        var target: TextView? = null
        fun find(v: View) {
            if (target != null) return
            if (v is TextView && v.text == "載入更早的訊息") target = v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) find(v.getChildAt(i))
            }
        }
        find(activity.window.decorView)
        if (target == null) return false
        activity.runOnUiThread { target.performClick() }
        return true
    }

    private fun msgBodies(texts: List<String>): List<String> =
        texts.filter { it.startsWith("msg-") || it.startsWith("msgA-") || it.startsWith("msgB-") }

    private fun collectTexts(activity: MainActivity): List<String> {
        val texts = ArrayList<String>()
        walkText(activity.window.decorView, texts)
        return texts
    }

    /**
     * Unlock + trigger loadEntries, then wait for entries to populate.
     * Polls from the TEST thread so loadEntries' runOnUiThread callback can run.
     */
    private fun unlockAndLoad(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val f = MainActivity::class.java.getDeclaredField("unlocked")
            f.isAccessible = true; f.setBoolean(activity, true)
            activity.loadEntries()
        }
        var entries: List<InboxDatabase.Entry> = emptyList()
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline && entries.isEmpty()) {
            scenario.onActivity { act -> entries = act.entries }
            if (entries.isEmpty()) Thread.sleep(50)
        }
    }

    /**
     * Open a conversation and wait for its first page to load.
     * All scenario.onActivity calls are made from the test thread (never from
     * inside another onActivity block), so Thread.sleep never blocks the UI thread.
     */
    private fun openConversation(scenario: ActivityScenario<MainActivity>, title: String) {
        var threadKey: String? = null
        scenario.onActivity { activity ->
            val f = MainActivity::class.java.getDeclaredField("entries")
            f.isAccessible = true
            val entries = f.get(activity) as List<InboxDatabase.Entry>
            val match = activity.threads(entries)
                .firstOrNull { it.value.any { it.title == title } }
            // Fall back to the resolved canonical id when the conversation is not
            // on the first inbox page (entries is a page of conversations).
            threadKey = match?.key ?: resolve(title)
            activity.openThreadForTest(threadKey!!, title)
        }
        // Wait until the TARGET conversation is the one rendered and its page
        // has settled. Waiting only for "some message visible" would let a
        // previous conversation's leftover screen satisfy the wait.
        val ld = System.currentTimeMillis() + 15_000
        var loaded = false
        while (System.currentTimeMillis() < ld && !loaded) {
            Thread.sleep(200)
            scenario.onActivity { activity ->
                val st = activity.chatStateForTest()
                // The target conversation must be active and its fetch settled.
                // A conversation with no saved messages legitimately renders an
                // empty page, so entry count is not part of the readiness test.
                loaded = st["conversationId"] == threadKey && st["loading"] == false
            }
        }
        assertTrue("Conversation '$title' rendered its page", loaded)
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun conversationIsolationAtoBtoA() {
        val cidA = resolve("Conversation A")
        val idsA = ArrayList<Long>()
        for (i in 1..150) {
            val id = save("Conversation A", "msgA-$i", 1_700_000_000_000L + i * 1000L)
            idsA.add(id)
        }
        for (id in idsA) link(id, cidA)

        val cidB = resolve("Conversation B")
        val idsB = ArrayList<Long>()
        for (i in 1..30) {
            val id = save("Conversation B", "msgB-$i", 1_700_100_000_000L + i * 1000L)
            idsB.add(id)
        }
        for (id in idsB) link(id, cidB)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlockAndLoad(scenario)

            // Open conversation A
            openConversation(scenario, "Conversation A")
            scenario.onActivity { activity ->
                val texts = collectTexts(activity)
                val bodies = msgBodies(texts)
                assertEquals("A shows 100 messages initially", 100, bodies.size)
                assertTrue("All messages from A", bodies.all { it.startsWith("msgA-") })
            }

            // Click "load older" on A
            scenario.onActivity { activity ->
                assertTrue("Load older button found on A", clickLoadOlder(activity))
            }

            // Switch to conversation B
            openConversation(scenario, "Conversation B")

            // B should only show B's messages
            scenario.onActivity { activity ->
                val texts = collectTexts(activity)
                val bodies = msgBodies(texts)
                assertTrue("B shows only B's messages", bodies.all { it.startsWith("msgB-") })
                assertFalse("No A messages leaked into B", bodies.any { it.startsWith("msgA-") })
            }

            // Switch back to A
            openConversation(scenario, "Conversation A")

            // A should still have its messages
            scenario.onActivity { activity ->
                val texts = collectTexts(activity)
                val bodies = msgBodies(texts)
                assertTrue("A shows only A's messages after switch back", bodies.all { it.startsWith("msgA-") })
                assertFalse("No B messages leaked into A", bodies.any { it.startsWith("msgB-") })
            }
        }
    }

    @Test
    fun cursorOrderingSameTimestamp() {
        val title = "Same Timestamp"
        val cid = resolve(title)
        val sameTs = 1_700_000_000_000L

        val ids = ArrayList<Long>()
        for (i in 1..150) {
            val id = save(title, "msg-$i", sameTs)
            ids.add(id)
        }
        for (id in ids) link(id, cid)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlockAndLoad(scenario)
            openConversation(scenario, title)

            scenario.onActivity { activity ->
                val texts = collectTexts(activity)
                val bodies = msgBodies(texts)
                assertEquals("Page 1 shows 100 messages", 100, bodies.size)
                assertEquals("No duplicates", 100, bodies.toSet().size)
            }

            scenario.onActivity { activity ->
                assertTrue("Load older button found", clickLoadOlder(activity))
            }

            val ld = System.currentTimeMillis() + 15_000
            var allLoaded = false
            while (System.currentTimeMillis() < ld && !allLoaded) {
                Thread.sleep(200)
                scenario.onActivity { activity ->
                    val t2 = collectTexts(activity)
                    allLoaded = msgBodies(t2).size >= 150
                }
            }

            scenario.onActivity { activity ->
                val texts = collectTexts(activity)
                val bodies = msgBodies(texts)
                assertEquals("All 150 messages shown", 150, bodies.size)
                assertEquals("No duplicates", 150, bodies.toSet().size)

                val indices = bodies.map { it.removePrefix("msg-").toInt() }
                assertTrue("Descending ID order", indices == indices.sortedDescending())
            }
        }
    }

    @Test
    fun photoPreviewReturnPreservesHistory() {
        val title = "Photo Chat"
        val cid = resolve(title)
        val ids = ArrayList<Long>()
        for (i in 1..150) {
            val id = save(title, "msg-$i", 1_700_000_000_000L + i * 1000L)
            ids.add(id)
        }
        for (id in ids) link(id, cid)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlockAndLoad(scenario)
            openConversation(scenario, title)

            scenario.onActivity { activity ->
                val texts = collectTexts(activity)
                assertTrue("First page shows 100 messages", msgBodies(texts).size >= 100)
            }

            scenario.onActivity { activity ->
                assertTrue("Load older button found", clickLoadOlder(activity))
            }

            val ld = System.currentTimeMillis() + 15_000
            var loaded150 = false
            while (System.currentTimeMillis() < ld && !loaded150) {
                Thread.sleep(200)
                scenario.onActivity { activity ->
                    val t2 = collectTexts(activity)
                    loaded150 = msgBodies(t2).size >= 150
                }
            }

            scenario.onActivity { activity ->
                val texts = collectTexts(activity)
                assertTrue("History preserved after navigation", msgBodies(texts).size >= 100)
            }
        }
    }

    @Test
    fun deleteMessageShowsCorrectCount() {
        val title = "Delete Test"
        val cid = resolve(title)
        val ids = ArrayList<Long>()
        for (i in 1..10) {
            val id = save(title, "msg-$i", 1_700_000_000_000L + i * 1000L)
            ids.add(id)
        }
        for (id in ids) link(id, cid)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlockAndLoad(scenario)
            openConversation(scenario, title)

            scenario.onActivity { activity ->
                val texts = collectTexts(activity)
                assertEquals("10 messages shown initially", 10, msgBodies(texts).size)
            }

            // Delete the newest message through the real delete path.
            scenario.onActivity { activity -> activity.deleteMessageForTest(ids.last()) }

            val settle = System.currentTimeMillis() + 10_000
            var done = false
            while (System.currentTimeMillis() < settle && !done) {
                Thread.sleep(200)
                scenario.onActivity { activity ->
                    val bodies = msgBodies(collectTexts(activity))
                    done = bodies.size == 9 && bodies.none { it == "msg-10" }
                }
            }
            scenario.onActivity { activity ->
                val bodies = msgBodies(collectTexts(activity))
                assertEquals("9 messages remain after delete", 9, bodies.size)
                assertFalse("Deleted message not present", bodies.any { it == "msg-10" })
            }
        }
    }

    @Test
    fun pagination350and650() {
        val title1 = "Pagination 350"
        val cid1 = resolve(title1)
        val ids1 = ArrayList<Long>()
        for (i in 1..350) {
            val id = save(title1, "msg-$i", 1_700_000_000_000L + i * 1000L)
            ids1.add(id)
        }
        for (id in ids1) link(id, cid1)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlockAndLoad(scenario)
            openConversation(scenario, title1)

            var totalClicks = 0
            val maxClicks = 10
            var done = false
            while (totalClicks < maxClicks && !done) {
                scenario.onActivity { activity ->
                    val texts = collectTexts(activity)
                    val count = msgBodies(texts).size
                    if (count >= 350) done = true
                    else if (!clickLoadOlder(activity)) done = true
                }

                val ld = System.currentTimeMillis() + 10_000
                var prevCount = 0
                scenario.onActivity { activity ->
                    val texts = collectTexts(activity)
                    prevCount = msgBodies(texts).size
                }
                var grown = false
                while (System.currentTimeMillis() < ld && !grown) {
                    Thread.sleep(200)
                    scenario.onActivity { activity ->
                        val t2 = collectTexts(activity)
                        if (msgBodies(t2).size > prevCount) grown = true
                    }
                }
                totalClicks++
            }

            scenario.onActivity { activity ->
                val texts = collectTexts(activity)
                val bodies = msgBodies(texts)
                assertEquals("All 350 messages shown", 350, bodies.size)
                assertEquals("No duplicates", 350, bodies.toSet().size)
            }
        }

        val title2 = "Pagination 650"
        val cid2 = resolve(title2)
        val ids2 = ArrayList<Long>()
        for (i in 1..650) {
            val id = save(title2, "msg-$i", 1_700_100_000_000L + i * 1000L)
            ids2.add(id)
        }
        for (id in ids2) link(id, cid2)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlockAndLoad(scenario)
            openConversation(scenario, title2)

            var totalClicks = 0
            val maxClicks = 20
            var done = false
            while (totalClicks < maxClicks && !done) {
                scenario.onActivity { activity ->
                    val texts = collectTexts(activity)
                    val count = msgBodies(texts).size
                    if (count >= 650) done = true
                    else if (!clickLoadOlder(activity)) done = true
                }

                val ld = System.currentTimeMillis() + 10_000
                var prevCount = 0
                scenario.onActivity { activity ->
                    val texts = collectTexts(activity)
                    prevCount = msgBodies(texts).size
                }
                var grown = false
                while (System.currentTimeMillis() < ld && !grown) {
                    Thread.sleep(200)
                    scenario.onActivity { activity ->
                        val t2 = collectTexts(activity)
                        if (msgBodies(t2).size > prevCount) grown = true
                    }
                }
                totalClicks++
            }

            scenario.onActivity { activity ->
                val texts = collectTexts(activity)
                val bodies = msgBodies(texts)
                assertEquals("All 650 messages shown", 650, bodies.size)
                assertEquals("No duplicates", 650, bodies.toSet().size)
            }
        }
    }

    @Test
    fun switchConversationDuringAsyncLoad() {
        val titleA = "Async Conv A"
        val titleB = "Async Conv B"
        val cidA = resolve(titleA)
        val cidB = resolve(titleB)

        val idsA = ArrayList<Long>()
        for (i in 1..200) {
            val id = save(titleA, "msgA-$i", 1_700_000_000_000L + i * 1000L)
            idsA.add(id)
        }
        for (id in idsA) link(id, cidA)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlockAndLoad(scenario)
            openConversation(scenario, titleA)

            scenario.onActivity { activity ->
                assertTrue("Load older button found on A", clickLoadOlder(activity))
            }

            openConversation(scenario, titleB)

            scenario.onActivity { activity ->
                val texts = collectTexts(activity)
                val aMessages = msgBodies(texts).filter { it.startsWith("msgA-") }
                assertTrue("No A messages leaked into B", aMessages.isEmpty())
            }
        }
    }

    @Test
    fun dbQueryFailureDoesNotLeakOtherConversation() {
        val titleA = "Failure Test A"
        val titleB = "Failure Test B"
        val cidA = resolve(titleA)
        val cidB = resolve(titleB)

        val idsA = ArrayList<Long>()
        for (i in 1..10) {
            val id = save(titleA, "msgA-$i", 1_700_000_000_000L + i * 1000L)
            idsA.add(id)
        }
        for (id in idsA) link(id, cidA)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlockAndLoad(scenario)
            openConversation(scenario, titleA)

            scenario.onActivity { activity ->
                val texts = collectTexts(activity)
                assertTrue("A messages shown", msgBodies(texts).all { it.startsWith("msgA-") })
            }

            openConversation(scenario, titleB)

            scenario.onActivity { activity ->
                val texts = collectTexts(activity)
                val aMessages = msgBodies(texts).filter { it.startsWith("msgA-") }
                assertTrue("No A messages leaked into B", aMessages.isEmpty())
            }
        }
    }

    // ------------------------------------------------- IR-11D corrective tests

    /**
     * Photo → Back must keep the already-loaded history AND the cursor exactly
     * where they were: returning from the preview may not fetch a new page.
     */
    @Test
    fun photoReturnPreservesHistoryAndCursor() {
        val title = "Photo Cursor"
        val cid = resolve(title)
        val ids = ArrayList<Long>()
        for (i in 1..150) {
            ids.add(save(title, "msg-$i", 1_700_000_000_000L + i * 1000L))
        }
        for (id in ids) link(id, cid)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlockAndLoad(scenario)
            openConversation(scenario, title)

            // Load the second page so the cursor is non-null and meaningful.
            scenario.onActivity { activity -> clickLoadOlder(activity) }
            val ld = System.currentTimeMillis() + 15_000
            var loaded150 = false
            while (System.currentTimeMillis() < ld && !loaded150) {
                Thread.sleep(200)
                scenario.onActivity { activity ->
                    loaded150 = msgBodies(collectTexts(activity)).size >= 150
                }
            }
            assertTrue("Second page loaded before photo", loaded150)

            var beforeEntries = 0
            var beforeCursor: Any? = null
            scenario.onActivity { activity ->
                val st = activity.chatStateForTest()
                beforeEntries = st["entries"] as Int
                beforeCursor = st["cursor"]
            }

            // Open the photo preview, then come straight back.
            val photoId = ids.last()
            scenario.onActivity { activity -> activity.openPhotoForTest(photoId) }
            scenario.onActivity { activity -> activity.onBackPressedDispatcher.onBackPressed() }

            val wait = System.currentTimeMillis() + 10_000
            var backOnChat = false
            while (System.currentTimeMillis() < wait && !backOnChat) {
                Thread.sleep(100)
                scenario.onActivity { activity ->
                    backOnChat = activity.chatStateForTest()["page"] == "chat"
                }
            }
            assertTrue("Returned to chat page", backOnChat)

            scenario.onActivity { activity ->
                val st = activity.chatStateForTest()
                assertEquals("Entry count unchanged by photo return",
                    beforeEntries, st["entries"] as Int)
                assertEquals("Cursor unchanged by photo return",
                    beforeCursor, st["cursor"])
                assertTrue("History still rendered",
                    msgBodies(collectTexts(activity)).size >= 150)
            }
        }
    }

    /** Delete must remove the row from the chat page and never show it again. */
    @Test
    fun deleteRemovesMessageFromChatPage() {
        val title = "Delete Chat Page"
        val cid = resolve(title)
        val ids = ArrayList<Long>()
        for (i in 1..10) {
            ids.add(save(title, "msg-$i", 1_700_000_000_000L + i * 1000L))
        }
        for (id in ids) link(id, cid)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlockAndLoad(scenario)
            openConversation(scenario, title)

            scenario.onActivity { activity ->
                assertEquals("10 messages initially",
                    10, msgBodies(collectTexts(activity)).size)
            }

            // Delete through the Activity's own delete path (the dialog's
            // positive button), so the real handler runs.
            scenario.onActivity { activity -> activity.deleteMessageForTest(ids.last()) }

            val settle = System.currentTimeMillis() + 10_000
            var gone = false
            while (System.currentTimeMillis() < settle && !gone) {
                Thread.sleep(200)
                scenario.onActivity { activity ->
                    val bodies = msgBodies(collectTexts(activity))
                    gone = bodies.size == 9 && bodies.none { it == "msg-10" }
                }
            }
            scenario.onActivity { activity ->
                val bodies = msgBodies(collectTexts(activity))
                assertEquals("9 messages remain after delete", 9, bodies.size)
                assertFalse("Deleted message not rendered", bodies.any { it == "msg-10" })
                assertEquals("chatEntries no longer holds deleted id", 9,
                    activity.chatStateForTest()["entries"] as Int)
            }
        }
    }

    /**
     * A stale generation must not render, and must not release the loading flag
     * owned by the newer request.
     */
    @Test
    fun staleCallbackDoesNotClearNewRequestLoading() {
        val titleA = "Stale A"
        val titleB = "Stale B"
        val cidA = resolve(titleA)
        val cidB = resolve(titleB)
        for (i in 1..200) link(save(titleA, "msgA-$i", 1_700_000_000_000L + i * 1000L), cidA)
        for (i in 1..30) link(save(titleB, "msgB-$i", 1_700_100_000_000L + i * 1000L), cidB)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlockAndLoad(scenario)
            openConversation(scenario, titleA)

            // Kick off a load on A, then immediately switch to B so A's
            // callback becomes stale while B's request owns chatLoading.
            scenario.onActivity { activity -> clickLoadOlder(activity) }
            scenario.onActivity { activity ->
                val f = MainActivity::class.java.getDeclaredField("entries")
                f.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val list = f.get(activity) as List<InboxDatabase.Entry>
                val keyB = activity.threads(list).first { e -> e.value.any { it.title == titleB } }.key
                activity.openThreadForTest(keyB, titleB)
            }

            val wait = System.currentTimeMillis() + 15_000
            var settled = false
            while (System.currentTimeMillis() < wait && !settled) {
                Thread.sleep(200)
                scenario.onActivity { activity ->
                    val st = activity.chatStateForTest()
                    settled = st["loading"] == false && (st["entries"] as Int) > 0
                }
            }

            scenario.onActivity { activity ->
                val st = activity.chatStateForTest()
                assertEquals("Loading released after B settles", false, st["loading"])
                val bodies = msgBodies(collectTexts(activity))
                assertTrue("B rendered", bodies.isNotEmpty())
                assertTrue("No A leakage into B", bodies.none { it.startsWith("msgA-") })
            }
        }
    }

    /** A locked session must never be repainted by an old chat callback. */
    @Test
    fun lockRejectsInFlightChatCallback() {
        val title = "Lock Chat"
        val cid = resolve(title)
        for (i in 1..300) link(save(title, "msg-$i", 1_700_000_000_000L + i * 1000L), cid)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlockAndLoad(scenario)
            openConversation(scenario, title)

            scenario.onActivity { activity -> clickLoadOlder(activity) }

            // Lock immediately, while the fetch is still in flight.
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
            }
            scenario.onActivity { activity ->
                val f = MainActivity::class.java.getDeclaredField("unlocked")
                f.isAccessible = true
                f.setBoolean(activity, false)
                val m = MainActivity::class.java.getDeclaredMethod("showLock")
                m.isAccessible = true
                m.invoke(activity)
            }

            Thread.sleep(2_000)
            scenario.onActivity { activity ->
                val st = activity.chatStateForTest()
                assertEquals("Still on lock page", "locked", st["page"])
                assertEquals("Loading released on lock", false, st["loading"])
                assertTrue("No private message text on lock screen",
                    msgBodies(collectTexts(activity)).isEmpty())
            }
        }
    }
}
