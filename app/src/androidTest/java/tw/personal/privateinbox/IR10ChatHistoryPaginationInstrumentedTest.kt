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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * IR-10 — chat detail pagination must actually page.
 *
 * Reproduction: 150 messages in one conversation → open chat → shows 100 → click
 * "載入更早的訊息" → should show 150.
 *
 * Root cause: `showChat()` always queries with `limit = CHAT_PAGE_SIZE` (100),
 * discarding the larger result that `loadOlderChatMessages()` fetched. The fix
 * is cursor-based paging via `beforeReceivedAt`/`beforeId` on both
 * `readConversationMessages` and `readMessagesByLegacyKey`.
 */
@RunWith(AndroidJUnit4::class)
class IR10ChatHistoryPaginationInstrumentedTest {

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
        texts.filter { it.startsWith("msg-") }

    /**
     * Unlock + load entries, then wait for them to populate.
     * We poll from the TEST thread (not UI thread) so loadEntries' runOnUiThread
     * callback can execute and populate entries.
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
            scenario.onActivity { activity ->
                entries = activity.entries
            }
            if (entries.isEmpty()) Thread.sleep(50)
        }
    }

    private fun openConversation(scenario: ActivityScenario<MainActivity>, title: String) {
        var threadKey: String? = null
        scenario.onActivity { activity ->
            val match = activity.threads(activity.entries)
                .firstOrNull { it.value.any { e -> e.title == title } }
            threadKey = match?.key ?: resolve(title)
            activity.openThreadForTest(threadKey!!, title)
        }
        // IR-11C: showChat() fetches off the UI thread and renders async, so poll
        // until the target conversation's first page has actually rendered.
        val deadline = System.currentTimeMillis() + 20_000
        var ready = false
        while (System.currentTimeMillis() < deadline && !ready) {
            Thread.sleep(150)
            scenario.onActivity { activity ->
                ready = msgBodies(collectTexts(activity)).isNotEmpty()
            }
        }
        check(ready) { "conversation '$title' did not render" }
    }

    /** IR-11C: takes the Activity directly so it never nests scenario.onActivity
     *  (which deadlocks now that showChat() renders asynchronously). */
    private fun collectTexts(activity: MainActivity): List<String> {
        val texts = ArrayList<String>()
        walkText(activity.window.decorView, texts)
        return texts
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun oneHundredFiftyCanonicalAllShownAfterLoad() {
        val title = "可分頁對話"
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
                assertEquals("page 1 shows 100 messages", 100, msgBodies(texts).size)
                assertTrue("load older button found", clickLoadOlder(activity))
            }
            val loadDeadline = System.currentTimeMillis() + 15_000
            var allShown = false
            while (System.currentTimeMillis() < loadDeadline && !allShown) {
                Thread.sleep(200)
                scenario.onActivity { activity ->
                    val t2 = collectTexts(activity)
                    allShown = msgBodies(t2).size >= 150
                }
            }
            scenario.onActivity { activity ->
                val t3 = collectTexts(activity)
                val allBodies = msgBodies(t3)
                assertEquals("all 150 messages shown", 150, allBodies.size)
                assertEquals("no duplicates", 150, allBodies.toSet().size)
                val indices = allBodies.map { it.removePrefix("msg-").toInt() }
                assertEquals("newest-first order", indices.sortedDescending(), indices)
            }
        }
    }

    @Test
    fun threeHundredFiftyCanonicalAllShownAfterRepeatedLoad() {
        val title = "多頁對話"
        val cid = resolve(title)
        val ids = ArrayList<Long>()
        for (i in 1..350) {
            val id = save(title, "msg-$i", 1_700_000_000_000L + i * 1000L)
            ids.add(id)
        }
        for (id in ids) link(id, cid)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlockAndLoad(scenario)
            openConversation(scenario, title)
            scenario.onActivity { activity ->
                Thread.sleep(300)
            }
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
                // Wait for async load to grow.
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
                val finalTexts = collectTexts(activity)
                val allBodies = msgBodies(finalTexts)
                assertEquals("all 350 messages shown", 350, allBodies.size)
                assertEquals("no duplicates", 350, allBodies.toSet().size)
                val indices = allBodies.map { it.removePrefix("msg-").toInt() }
                assertEquals("newest-first order", indices.sortedDescending(), indices)
            }
        }
    }

    @Test
    fun sixHundredFiftyCanonicalBeyondOldLimit() {
        val title = "大量對話"
        val cid = resolve(title)
        val ids = ArrayList<Long>()
        for (i in 1..650) {
            val id = save(title, "msg-$i", 1_700_000_000_000L + i * 1000L)
            ids.add(id)
        }
        for (id in ids) link(id, cid)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlockAndLoad(scenario)
            openConversation(scenario, title)
            scenario.onActivity { activity ->
                Thread.sleep(300)
            }
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
                val finalTexts = collectTexts(activity)
                val allBodies = msgBodies(finalTexts)
                assertEquals("all 650 messages shown", 650, allBodies.size)
                assertEquals("no duplicates", 650, allBodies.toSet().size)
            }
        }
    }

    @Test
    fun oneHundredFiftyUnlinkedLegacyAllShown() {
        val title = "未連結對話"
        val key = LegacyKeyCodec.format(pkg, null, title)
        val ids = ArrayList<Long>()
        for (i in 1..150) {
            val id = save(title, "msg-$i", 1_700_000_000_000L + i * 1000L, key)
            ids.add(id)
        }
        // No link() — genuinely unlinked

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlockAndLoad(scenario)
            openConversation(scenario, title)
            scenario.onActivity { activity ->
                val texts = collectTexts(activity)
                assertEquals("page 1 shows 100 messages", 100, msgBodies(texts).size)
                assertTrue("load older button found", clickLoadOlder(activity))
            }
            val ld = System.currentTimeMillis() + 15_000
            var allShown = false
            while (System.currentTimeMillis() < ld && !allShown) {
                Thread.sleep(200)
                scenario.onActivity { activity ->
                    val t2 = collectTexts(activity)
                    allShown = msgBodies(t2).size >= 150
                }
            }
            scenario.onActivity { activity ->
                val t3 = collectTexts(activity)
                val allBodies = msgBodies(t3)
                assertEquals("all 150 unlinked messages shown", 150, allBodies.size)
                assertEquals("no duplicates", 150, allBodies.toSet().size)
            }
        }
    }

    @Test
    fun sameTimestampDifferentIdNoOmission() {
        val title = "同時間戳"
        val cid = resolve(title)
        val sameTs = 1_700_000_000_000L
        val ids = ArrayList<Long>()
        for (i in 1..30) {
            val id = save(title, "msg-$i", sameTs)
            ids.add(id)
        }
        for (id in ids) link(id, cid)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlockAndLoad(scenario)
            openConversation(scenario, title)
            scenario.onActivity { activity ->
                val texts = collectTexts(activity)
                val allBodies = msgBodies(texts)
                assertEquals("all 30 same-timestamp messages shown", 30, allBodies.size)
                assertEquals("no duplicates", 30, allBodies.toSet().size)
            }
        }
    }
}
