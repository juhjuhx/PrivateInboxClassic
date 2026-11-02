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
 * IR-04 — one canonical conversation must produce exactly ONE summary row, even
 * when it owns several SHORTCUT aliases.
 *
 * RED before the fix: `conversationPage()` joined `conversation_aliases` on
 * (package, 'SHORTCUT', conversation_id). A conversation with two shortcut
 * aliases therefore produced TWO rows for the same conversation — inflating the
 * page, repeating conversations, and breaking the count.
 *
 * The join is now pinned to a single deterministic alias row. DISTINCT is
 * deliberately NOT used: it would hide a bad aggregation instead of fixing the
 * one-to-many relation.
 */
@RunWith(AndroidJUnit4::class)
class IR04SummaryCardinalityInstrumentedTest {

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

    private fun save(title: String, body: String, ts: Long, shortcut: String? = null): Long {
        val key = LegacyKeyCodec.format(pkg, shortcut, title)
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

    private fun link(id: Long, cid: String) {
        val store = ConversationStore(ctx)
        try { store.linkMessage(id, cid) } finally { store.close() }
    }

    /** Give one conversation a SECOND SHORTCUT alias (a re-issued shortcut). */
    private fun addShortcutAlias(cid: String, shortcut: String) {
        val db = InboxDatabase(ctx)
        try {
            db.writableDatabase.execSQL(
                "INSERT OR REPLACE INTO conversation_aliases " +
                    "(package_name, alias_type, alias_value, conversation_id, strength, created_at) " +
                    "VALUES (?, 'SHORTCUT', ?, ?, 'STRONG', ?)",
                arrayOf(pkg, shortcut, cid, System.currentTimeMillis().toString())
            )
        } finally { db.close() }
    }

    private fun page(limit: Int = 50, offset: Int = 0) =
        InboxDatabase(ctx).use { it.conversationPage(limit, offset) }

    private fun countAll() = InboxDatabase(ctx).use { it.conversationCountAll(pkg) }

    // ------------------------------------------------------------------ tests

    /** The core defect: two SHORTCUT aliases must still yield one row. */
    @Test
    fun twoShortcutAliasesProduceExactlyOneSummaryRow() {
        val cid = ConversationIdentityResolver(ConversationStore(ctx))
            .resolve(pkg, "sc-original", "多別名").conversationId
        val id = save("多別名", "msg-1", 1_700_000_000_001L, "sc-original")
        link(id, cid)
        addShortcutAlias(cid, "sc-reissued")

        val p = page()
        assertEquals("exactly ONE summary row for the conversation", 1, p.size)
        assertEquals(cid, p[0].canonicalConversationId)
        assertEquals("the message count is not multiplied by the alias count",
            1, p[0].messageCount)
        assertEquals("conversationCountAll agrees", 1, countAll())
    }

    /** Several conversations, each with two shortcuts: the page size is exact. */
    @Test
    fun pageSizeIsNotInflatedByAliases() {
        val cids = ArrayList<String>()
        for (i in 1..10) {
            val cid = ConversationIdentityResolver(ConversationStore(ctx))
                .resolve(pkg, "sc-$i-a", "群組$i").conversationId
            val id = save("群組$i", "body-$i", 1_700_000_000_000L + i, "sc-$i-a")
            link(id, cid)
            addShortcutAlias(cid, "sc-$i-b")
            cids.add(cid)
        }
        val p = page(limit = 50)
        assertEquals("10 conversations, 10 rows — not 20", 10, p.size)
        assertEquals("no duplicate canonical ids", 10,
            p.map { it.canonicalConversationId }.toSet().size)
        assertEquals(10, countAll())
    }

    /** The latest message id/timestamp must correspond to the newest message. */
    @Test
    fun latestMessageIdAndTimestampMatchTheNewestMessage() {
        val cid = ConversationIdentityResolver(ConversationStore(ctx))
            .resolve(pkg, "sc-latest", "最新").conversationId
        val oldest = save("最新", "old", 1_700_000_000_001L, "sc-latest")
        val newest = save("最新", "new", 1_700_000_000_900L, "sc-latest")
        link(oldest, cid); link(newest, cid)
        addShortcutAlias(cid, "sc-latest-2")

        val p = page()
        assertEquals(1, p.size)
        assertEquals("latest id is the newest message", newest, p[0].latestMessageId)
        assertEquals("latest timestamp is the newest message",
            1_700_000_000_900L, p[0].latestMessageTimestamp)
    }

    /** Paging with aliases present must not drop or repeat conversations. */
    @Test
    fun paginationWithAliasesIsStable() {
        for (i in 1..40) {
            val cid = ConversationIdentityResolver(ConversationStore(ctx))
                .resolve(pkg, "sc-p$i", "P$i").conversationId
            val id = save("P$i", "body-$i", 1_700_000_000_000L + i, "sc-p$i")
            link(id, cid)
            addShortcutAlias(cid, "sc-p$i-2")
        }
        val seen = LinkedHashSet<String>()
        var offset = 0
        val limit = 15
        while (true) {
            val p = page(limit, offset)
            if (p.isEmpty()) break
            for (s in p) {
                assertTrue("no repeats across pages",
                    seen.add(s.canonicalConversationId))
            }
            offset += limit
            if (p.size < limit) break
        }
        assertEquals("all 40 conversations are reachable exactly once", 40, seen.size)
    }

    /** Merge/unmerge must keep the cardinality at one row per conversation. */
    @Test
    fun mergeAndUnmergeKeepCardinality() {
        val cidA = ConversationIdentityResolver(ConversationStore(ctx))
            .resolve(pkg, "sc-ma", "MA").conversationId
        val cidB = ConversationIdentityResolver(ConversationStore(ctx))
            .resolve(pkg, "sc-mb", "MB").conversationId
        link(save("MA", "a", 1_700_000_000_001L, "sc-ma"), cidA)
        link(save("MB", "b", 1_700_000_000_002L, "sc-mb"), cidB)
        addShortcutAlias(cidA, "sc-ma-2")

        assertEquals("two conversations before merge", 2, page().size)

        val store = ConversationStore(ctx)
        try { store.merge(from = cidB, into = cidA) } finally { store.close() }

        val merged = page()
        assertEquals("one conversation after merge", 1, merged.size)
        assertEquals(cidA, merged[0].canonicalConversationId)
        assertEquals("both messages counted once", 2, merged[0].messageCount)

        val store2 = ConversationStore(ctx)
        try { store2.unmerge(mergedAway = cidB) } finally { store2.close() }
        assertEquals("two conversations after unmerge", 2, page().size)
    }
}
