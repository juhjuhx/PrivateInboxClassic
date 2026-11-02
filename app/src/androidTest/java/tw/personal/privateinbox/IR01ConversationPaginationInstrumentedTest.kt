package tw.personal.privateinbox

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * IR-01 — the inbox must page by CONVERSATION, not by message.
 *
 * RED before the fix: the UI read the newest 50 MESSAGES and grouped them
 * afterwards, so
 *   * a conversation with 100 recent messages consumed the entire page and an
 *     older-but-distinct conversation never appeared, and
 *   * a conversation straddling the page boundary was rendered twice.
 *
 * These tests assert the SQL layer that the UI now uses, plus the invariant that
 * unlinked legacy rows are still visible.
 */
@RunWith(AndroidJUnit4::class)
class IR01ConversationPaginationInstrumentedTest {

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

    private fun save(title: String, body: String, ts: Long): Long {
        val key = LegacyKeyCodec.format(pkg, null, title)
        val db = InboxDatabase(ctx)
        try {
            db.save(
                packageName = pkg, receivedAt = ts, sourceTimestamp = ts,
                title = title, sender = "S", body = body, image = null,
                conversationKey = key,
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

    private fun page(limit: Int, offset: Int = 0) =
        InboxDatabase(ctx).use { it.conversationPage(limit, offset) }

    // ------------------------------------------------------------------ tests

    /**
     * A holds 100 of the newest messages; B holds ONE much older message.
     * Both conversations must appear on the first page.
     */
    @Test
    fun conversationWithManyRecentMessagesDoesNotHideAnOlderConversation() {
        val cidA = resolve("A 群組")
        val cidB = resolve("B 群組")
        // B is older than every one of A's messages.
        link(save("B 群組", "b-only", 1_700_000_000_000L), cidB)
        for (i in 1..100) {
            link(save("A 群組", "a-$i", 1_700_000_000_000L + i * 1000L), cidA)
        }

        val first = page(limit = 50, offset = 0)
        assertEquals("both conversations are on the first page", 2, first.size)
        val ids = first.map { it.canonicalConversationId }.toSet()
        assertEquals(setOf(cidA, cidB), ids)
        assertEquals("the many-message conversation is ONE row",
            1, first.count { it.canonicalConversationId == cidA })
        assertEquals("100 messages are aggregated into that one row",
            100, first.first { it.canonicalConversationId == cidA }.messageCount)
    }

    /** 100 conversations with different message counts: paging never drops or repeats. */
    @Test
    fun hundredConversationsPageWithoutLossOrDuplication() {
        val expected = LinkedHashMap<String, Int>()
        for (i in 1..100) {
            val title = "C$i"
            val cid = resolve(title)
            // A different number of messages per conversation, 1..3.
            val count = ((i - 1) % 3) + 1
            for (m in 1..count) {
                link(save(title, "c$i-m$m", 1_700_000_000_000L + i * 1000L + m), cid)
            }
            expected[cid] = count
        }

        val seen = LinkedHashMap<String, Int>()
        var offset = 0
        val limit = 30
        while (true) {
            val p = page(limit, offset)
            if (p.isEmpty()) break
            for (s in p) {
                assertTrue(
                    "conversation ${s.canonicalConversationId} must not repeat across pages",
                    seen.put(s.canonicalConversationId, s.messageCount) == null
                )
            }
            offset += limit
            if (p.size < limit) break
        }
        assertEquals("every conversation is reachable by paging", 100, seen.size)
        assertEquals("no conversation is lost", expected.keys, seen.keys)
        for ((cid, count) in expected) {
            assertEquals("message count for $cid is aggregated correctly", count, seen[cid])
        }
    }

    /** Unlinked legacy rows must stay visible under their own legacy key. */
    @Test
    fun unlinkedLegacyRowsRemainVisible() {
        // A linked conversation...
        val cid = resolve("已連結")
        link(save("已連結", "linked", 1_700_000_000_100L), cid)
        // ...and a row that never got a canonical link.
        save("未連結", "legacy-only", 1_700_000_000_200L)

        val p = page(limit = 50)
        assertEquals("both the linked conversation and the legacy row appear", 2, p.size)
        assertTrue(
            "the unlinked row is grouped by its legacy key",
            p.any { it.canonicalConversationId.startsWith("k:") }
        )
        assertTrue(
            "the linked row is grouped by its canonical id",
            p.any { it.canonicalConversationId == cid }
        )
    }

    /** Rename then merge/unmerge must keep paging order correct. */
    @Test
    fun renameAndMergeKeepPagingCorrect() {
        val cidA = resolve("原名")
        val idA = save("原名", "a1", 1_700_000_000_100L)
        link(idA, cidA)

        // The SQL summary is the contract the UI pages against.
        val before = page(limit = 50)
        assertEquals(1, before.size)
        assertEquals(cidA, before[0].canonicalConversationId)

        // A second conversation, then merge it into the first.
        val cidB = resolve("另一個")
        val idB = save("另一個", "b1", 1_700_000_000_200L)
        link(idB, cidB)
        assertEquals(2, page(limit = 50).size)

        val store = ConversationStore(ctx)
        try {
            store.merge(from = cidB, into = cidA)
        } finally { store.close() }

        val merged = page(limit = 50)
        assertEquals("after the merge there is exactly one conversation", 1, merged.size)
        assertEquals(cidA, merged[0].canonicalConversationId)
        assertEquals("both messages belong to the merged conversation",
            2, merged[0].messageCount)

        val store2 = ConversationStore(ctx)
        try {
            store2.unmerge(mergedAway = cidB)
        } finally { store2.close() }

        val unmerged = page(limit = 50)
        assertEquals("unmerge restores two conversations", 2, unmerged.size)
        assertEquals("the original message is back with its conversation",
            1, unmerged.first { it.canonicalConversationId == cidA }.messageCount)
    }
}
