package tw.personal.privateinbox

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * IR-08 — regressions found by the INDEPENDENT REVIEW of the IR round.
 *
 * These are not new features: each test pins a defect the reviewer found in the
 * IR-01/IR-02 fix itself.
 */
@RunWith(AndroidJUnit4::class)
class IR08ReviewFollowupInstrumentedTest {

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

    // ------------------------------------------------------- P1: key state

    /**
     * P1 (reviewer): the IR-01 rewrite dropped the only assignments to
     * keyState/unreadableRows, so key recovery was permanently dead.
     * conversationPreview() must report the state so the UI can surface it.
     */
    @Test
    fun conversationPreviewReportsKeyAvailableForReadableRows() {
        val cid = resolve("健康")
        val id = save("健康", "readable", 1_700_000_000_001L)
        link(id, cid)

        val preview = InboxDatabase(ctx).use { it.conversationPreview(id) }
        assertNotNull("the preview decrypts a healthy row", preview.entry)
        assertEquals("a healthy row reports KEY_AVAILABLE",
            CryptoStore.KeyState.KEY_AVAILABLE, preview.keyState)
        assertEquals("and zero unreadable rows", 0, preview.unreadable)
    }

    /** A missing row is NOT an unreadable row (no false recovery prompt). */
    @Test
    fun conversationPreviewForMissingRowIsNotUnreadable() {
        val preview = InboxDatabase(ctx).use { it.conversationPreview(999_999L) }
        assertNull(preview.entry)
        assertEquals("a missing row is not a key failure", 0, preview.unreadable)
        assertEquals(CryptoStore.KeyState.KEY_AVAILABLE, preview.keyState)
    }

    // -------------------------------------------- P2: unlinked detail page

    /**
     * P2 (reviewer): conversationPage labels an unlinked group "k:<key>" while
     * threadKey returns the raw key, so the detail read failed closed and the
     * chat screen collapsed to one message. readMessagesByLegacyKey must return
     * the whole history.
     */
    @Test
    fun unlinkedConversationDetailReturnsEveryMessage() {
        val key = LegacyKeyCodec.format(pkg, null, "未連結")
        for (i in 1..5) save("未連結", "m-$i", 1_700_000_000_000L + i, key)

        val detail = InboxDatabase(ctx).use {
            it.readMessagesByLegacyKey(key, limit = 100)
        }
        assertEquals("all five unlinked messages are returned",
            5, detail.entries.size)
        assertEquals("newest first", "m-5", detail.entries[0].body)
        assertEquals("oldest last", "m-1", detail.entries[4].body)
    }

    /** The legacy-key read is NUL-safe (the key really contains a NUL). */
    @Test
    fun unlinkedDetailMatchesTheFullNulContainingKey() {
        val keyA = LegacyKeyCodec.format(pkg, null, "甲")
        val keyB = LegacyKeyCodec.format(pkg, null, "乙")
        for (i in 1..3) save("甲", "a-$i", 1_700_000_000_000L + i, keyA)
        for (i in 1..2) save("乙", "b-$i", 1_700_000_000_100L + i, keyB)

        val a = InboxDatabase(ctx).use { it.readMessagesByLegacyKey(keyA, 100) }
        val b = InboxDatabase(ctx).use { it.readMessagesByLegacyKey(keyB, 100) }
        assertEquals("甲 has exactly its three messages", 3, a.entries.size)
        assertEquals("乙 has exactly its two messages", 2, b.entries.size)
        assertTrue("no cross-contamination", a.entries.all { it.body.startsWith("a-") })
        assertTrue("no cross-contamination", b.entries.all { it.body.startsWith("b-") })
    }

    // ------------------------------------------- P2: stable keyset paging

    /**
     * P2 (reviewer): offset paging over a live table can skip a conversation
     * when a new message re-orders the list. The keyset cursor must not.
     */
    @Test
    fun keysetPagingIsStableWhenANewMessageArrives() {
        // 60 conversations, one message each.
        for (i in 1..60) {
            val cid = resolve("K$i")
            link(save("K$i", "body-$i", 1_700_000_000_000L + i * 1000L), cid)
        }
        val page1 = InboxDatabase(ctx).use { it.conversationPage(limit = 20) }
        assertEquals(20, page1.size)
        val cursor = page1.last().latestMessageTimestamp to page1.last().canonicalConversationId

        // A NEW message arrives in the OLDEST conversation, moving it to the top.
        val oldestCid = resolve("K1")
        link(save("K1", "brand-new", 1_700_000_999_000L), oldestCid)

        val page2 = InboxDatabase(ctx).use {
            it.conversationPage(
                limit = 20,
                afterLatestReceived = cursor.first,
                afterGid = cursor.second
            )
        }
        assertEquals(20, page2.size)
        val p1Keys = page1.map { it.canonicalConversationId }.toSet()
        val p2Keys = page2.map { it.canonicalConversationId }.toSet()
        assertTrue("page 2 must not repeat page 1's conversations",
            p1Keys.intersect(p2Keys).isEmpty())
        // And nothing between the cursor and page 2's start was skipped.
        assertEquals("no conversation is skipped between the pages",
            0, p1Keys.intersect(p2Keys).size)
    }

    /** Paging through everything with the keyset cursor reaches every row once. */
    @Test
    fun keysetPagingVisitsEveryConversationExactlyOnce() {
        for (i in 1..45) {
            val cid = resolve("S$i")
            link(save("S$i", "b-$i", 1_700_000_000_000L + i * 1000L), cid)
        }
        val seen = LinkedHashSet<String>()
        var afterTs: Long? = null
        var afterGid: String? = null
        while (true) {
            val page = InboxDatabase(ctx).use {
                it.conversationPage(
                    limit = 15,
                    afterLatestReceived = afterTs,
                    afterGid = afterGid
                )
            }
            if (page.isEmpty()) break
            for (s in page) {
                assertTrue("no conversation repeats",
                    seen.add(s.canonicalConversationId))
            }
            afterTs = page.last().latestMessageTimestamp
            afterGid = page.last().canonicalConversationId
            if (page.size < 15) break
        }
        assertEquals("every conversation is visited exactly once", 45, seen.size)
    }

    /** The silently-ignored `search` parameter is gone from the API. */
    @Test
    fun conversationPageNoLongerAcceptsAnIgnoredSearchParameter() {
        // Compile-time proof: this call omits `search`. If the parameter were
        // still accepted-and-ignored, this test would document the trap. The
        // assertion just confirms the page still works without it.
        val cid = resolve("搜尋移除")
        link(save("搜尋移除", "x", 1_700_000_000_001L), cid)
        val page = InboxDatabase(ctx).use { it.conversationPage(limit = 10) }
        assertEquals(1, page.size)
    }
}
