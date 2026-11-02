package tw.personal.privateinbox

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T02 — the canonical conversation read model.
 *
 * The defect this suite pins: the old UI grouped the CURRENT PAGE of messages by
 * legacy key. Two failure modes:
 *   1. a conversation whose rows straddle a page boundary appears TWICE,
 *   2. a conversation whose newest row is older than the page window is MISSING.
 *
 * `conversationPage()` computes the grouping in SQL over the whole table, so it
 * is correct at any page size, and it never decrypts rows just to build the list.
 */
@RunWith(AndroidJUnit4::class)
class ConversationReadModelInstrumentedTest {

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

    private fun save(
        title: String, body: String, ts: Long, shortcut: String? = null
    ): Long {
        // The legacy key must be built with the SAME shortcut the resolver sees,
        // otherwise the row and the alias describe two different keys (which is
        // exactly the inconsistency the debug dump exposed).
        val legacyKey = LegacyKeyCodec.format(pkg, null, title)
        val db = InboxDatabase(ctx)
        try {
            db.save(
                packageName = pkg, receivedAt = ts, sourceTimestamp = ts,
                title = title, sender = "S", body = body, image = null,
                conversationKey = legacyKey,
                messageKind = SourceRegistry.MessageKind.GROUP_MESSAGE.name,
                messageDiscriminator = "c:S:$body:i$ts"
            )
            return db.readableDatabase.rawQuery(
                "SELECT id FROM messages ORDER BY id DESC LIMIT 1", null
            ).use { c -> c.moveToFirst(); c.getLong(0) }
        } finally { db.close() }
    }

    private fun link(id: Long, cid: String) {
        val store = ConversationStore(ctx)
        try { store.linkMessage(id, cid) } finally { store.close() }
    }

    private fun resolve(shortcut: String?, title: String, pkgName: String = pkg): String {
        val store = ConversationStore(ctx)
        return try {
            ConversationIdentityResolver(store).resolve(pkgName, shortcut, title).conversationId
        } finally { store.close() }
    }

    private fun summaries(limit: Int, offset: Int = 0) =
        InboxDatabase(ctx).use { it.conversationPage(limit, offset) }

    private inline fun <T> InboxDatabase.use(block: (InboxDatabase) -> T): T {
        try { return block(this) } finally { close() }
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun oneConversationWithHundredMessagesIsOneSummary() {
        val cid = resolve(null, "大群組")
        for (i in 1..100) {
            val id = save("大群組", "msg-$i", 1_700_000_000_000L + i)
            link(id, cid)
        }
        val page = summaries(50)
        assertEquals(
            "1 conversation / 100 messages = exactly 1 summary row",
            1, page.size
        )
        assertEquals("the summary carries the real message count", 100, page[0].messageCount)
        assertEquals(
            "the newest message timestamp is the summary's timestamp",
            1_700_000_000_100L, page[0].latestMessageTimestamp
        )
    }

    @Test
    fun hundredConversationsOfOneMessageEach() {
        for (i in 1..100) {
            val cid = resolve("sc-c$i", "群組$i")
            val id = save("群組$i", "body-$i", 1_700_000_000_000L + i)
            link(id, cid)
        }
        val first = summaries(50, 0)
        val second = summaries(50, 50)
        assertEquals(50, first.size)
        assertEquals(50, second.size)
        // No conversation may appear on both pages.
        val ids = first.map { it.canonicalConversationId }.toSet()
        val secondIds = second.map { it.canonicalConversationId }.toSet()
        assertTrue("page 1 and page 2 must be disjoint", ids.intersect(secondIds).isEmpty())
        assertEquals(100, InboxDatabase(ctx).use { it.conversationCountAll(pkg) })
    }

    @Test
    fun renameDoesNotProduceTwoBoxes() {
        // 60 messages: 30 under the old title, 30 under the new one, all sharing
        // a shortcut. A page size of 50 would put BOTH legacy keys on the same
        // page, which is exactly where the old grouping duplicated the box.
        val cid = resolve(null, "舊名稱")
        for (i in 1..30) {
            val id = save("舊名稱", "old-$i", 1_700_000_000_000L + i)
            link(id, cid)
        }
        for (i in 1..30) {
            val id = save("新名稱", "new-$i", 1_700_000_001_000L + i)
            link(id, cid)
        }
        val page = summaries(50)
        assertEquals(
            "one canonical conversation across a rename = one box",
            1, page.size
        )
        assertEquals(60, page[0].messageCount)
    }

    @Test
    fun aConversationOlderThanTheMessagePageWindowIsStillListed() {
        // RED for the old defect: a conversation whose newest row is much older
        // than 50 newer messages of ANOTHER conversation was invisible to the
        // page-then-group UI.
        val oldCid = resolve(null, "老聊天室")
        val oldId = save("老聊天室", "old body", 1_700_000_000_000L)
        link(oldId, oldCid)

        val newCid = resolve(null, "新聊天室")
        for (i in 1..60) {
            val id = save("新聊天室", "new-$i", 1_700_001_000_000L + i)
            link(id, newCid)
        }

        val page = summaries(50)
        assertEquals(
            "both conversations must be listed, even though only 50 of the 61 " +
                "messages fit a message page",
            2, page.size
        )
        // The OLD conversation must be present despite having the older rows.
        assertTrue(
            "the old conversation must not vanish",
            page.any { it.canonicalConversationId == oldCid }
        )
        assertEquals(1, page.first { it.canonicalConversationId == oldCid }.messageCount)
        assertEquals(60, page.first { it.canonicalConversationId == newCid }.messageCount)
    }

    @Test
    fun unlinkedHistoricalRowsStayVisible() {
        // A legacy message with NO canonical link must not disappear.
        save("歷史訊息", "old row", 1_700_000_000_000L)
        val page = summaries(50)
        assertEquals(1, page.size)
        assertEquals(
            "unlinked rows group under their own legacy key",
            "k:" + LegacyKeyCodec.format(pkg, null, "歷史訊息"),
            page[0].canonicalConversationId
        )
        assertEquals("AMBIGUOUS", page[0].ambiguityState)
    }

    @Test
    fun identicalTimestampsDoNotShuffleAcrossPages() {
        // 6 conversations, all with the SAME latest timestamp. The stable sort
        // (timestamp DESC, id ASC) must keep page boundaries deterministic.
        for (i in 1..6) {
            val cid = resolve(null, "T$i")
            val id = save("T$i", "body", 1_700_000_000_000L)
            link(id, cid)
        }
        val p1 = summaries(3, 0).map { it.canonicalConversationId }
        val p2 = summaries(3, 3).map { it.canonicalConversationId }
        assertEquals(3, p1.size)
        assertEquals(3, p2.size)
        assertTrue("pages must be disjoint", p1.intersect(p2).isEmpty())
        assertEquals("and stable across a re-query",
            p1, summaries(3, 0).map { it.canonicalConversationId })
    }

    @Test
    fun emptyDatabaseYieldsEmptyPage() {
        assertTrue(summaries(50).isEmpty())
        assertEquals(0, InboxDatabase(ctx).use { it.conversationCountAll(pkg) })
    }

    @Test
    fun mergedConversationsAppearOnceAndUnmergeRestoresBoth() {
        val a = resolve(null, "M-A")
        val b = resolve(null, "M-B")
        val idA = save("M-A", "body-a", 1_700_000_000_001L)
        val idB = save("M-B", "body-b", 1_700_000_000_002L)
        link(idA, a); link(idB, b)

        val store = ConversationStore(ctx)
        try { store.merge(a, b) } finally { store.close() }
        assertEquals("after merge, one box", 1, summaries(50).size)
        assertEquals(2, summaries(50)[0].messageCount)

        val store2 = ConversationStore(ctx)
        try { store2.unmerge(a) } finally { store2.close() }
        val page = summaries(50)
        assertEquals("after unmerge, two boxes again", 2, page.size)
        assertNotEquals(page[0].canonicalConversationId, page[1].canonicalConversationId)
    }

    @Test
    fun searchAndSourceFiltersKeepWorkingOnTheReadModel() {
        val tg = "org.telegram.messenger"
        val cidA = resolve(null, "LINE 聊天")
        val idA = save("LINE 聊天", "line body", 1_700_000_000_001L)
        link(idA, cidA)

        val db2 = InboxDatabase(ctx)
        val cidB: String
        try {
            val legacyTg = LegacyKeyCodec.format(tg, "sc-fb", "Telegram 聊天")
            db2.save(
                packageName = tg, receivedAt = 1_700_000_000_002L, sourceTimestamp = 1_700_000_000_002L,
                title = "Telegram 聊天", sender = "S", body = "tg body", image = null,
                conversationKey = legacyTg,
                messageKind = SourceRegistry.MessageKind.GROUP_MESSAGE.name,
                messageDiscriminator = "c:S:tg body:i1"
            )
            cidB = ConversationIdentityResolver(ConversationStore(ctx))
                .resolve(tg, "sc-fb", "Telegram 聊天").conversationId
        } finally { db2.close() }

        // Source filter: only LINE.
        val linePage = InboxDatabase(ctx).use { it.conversationPage(50, packageName = pkg) }
        assertEquals(1, linePage.size)
        assertEquals(cidA, linePage[0].canonicalConversationId)
        // Unfiltered: both.
        assertEquals(2, InboxDatabase(ctx).use { it.conversationCountAll(null) })
    }

    @Test
    fun conversationMessagesAreReadableThroughTheCanonicalId() {
        val cid = resolve(null, "可讀聊天")
        val first = save("可讀聊天", "first", 1_700_000_000_001L)
        val second = save("可讀聊天", "second", 1_700_000_000_002L)
        link(first, cid); link(second, cid)

        val result = InboxDatabase(ctx).use { it.readConversationMessages(cid, 50) }
        assertEquals(2, result.entries.size)
        assertEquals("newest first", "second", result.entries[0].body)
        assertEquals("decrypted through the production path", "first", result.entries[1].body)
        assertTrue(result.entries.all { it.conversationKey.isNotEmpty() })
    }

    @Test
    fun unicodeAndVeryLongTitlesSurviveTheReadModel() {
        val longTitle = "很長的標題".repeat(50) + " 👍🏽 日本語"
        val cid = resolve(null, longTitle)
        val id = save(longTitle, "unicode body", 1_700_000_000_001L)
        link(id, cid)
        val page = summaries(50)
        assertEquals(1, page.size)
        val result = InboxDatabase(ctx).use { it.readConversationMessages(cid, 50) }
        assertEquals(1, result.entries.size)
        assertEquals(longTitle, result.entries[0].title)
    }
}
