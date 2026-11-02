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
 * IR-02 — `readConversationMessages()` must FAIL CLOSED.
 *
 * RED before the fix: when a canonical id had no LEGACY_KEY alias, the method
 * built an EMPTY `WHERE` clause and then read (and decrypted) the ENTIRE
 * messages table. Any unknown id therefore disclosed every stored message.
 *
 * Every test below is a NEGATIVE test: it asserts that nothing is returned.
 */
@RunWith(AndroidJUnit4::class)
class IR02FailClosedReadInstrumentedTest {

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

    private fun save(title: String, body: String, ts: Long, pkgName: String = pkg): Long {
        val key = LegacyKeyCodec.format(pkgName, null, title)
        val db = InboxDatabase(ctx)
        try {
            db.save(
                packageName = pkgName, receivedAt = ts, sourceTimestamp = ts,
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

    private fun resolve(title: String, pkgName: String = pkg): String {
        val store = ConversationStore(ctx)
        return try {
            ConversationIdentityResolver(store).resolve(pkgName, null, title).conversationId
        } finally { store.close() }
    }

    private fun link(id: Long, cid: String) {
        val store = ConversationStore(ctx)
        try { store.linkMessage(id, cid) } finally { store.close() }
    }

    private fun read(cid: String) =
        InboxDatabase(ctx).use { it.readConversationMessages(cid, 500) }

    // ------------------------------------------------------------------ tests

    /** An id that was never seen must return NOTHING, not the whole table. */
    @Test
    fun unknownCanonicalIdReturnsEmptyNotTheWholeTable() {
        // Populate the table so a full-table read would be obvious.
        val cid = resolve("真實對話")
        for (i in 1..5) link(save("真實對話", "msg-$i", 1_700_000_000_000L + i), cid)
        assertEquals("precondition: the real conversation has 5 messages",
            5, read(cid).entries.size)

        val result = read("cv_this-id-does-not-exist")
        assertEquals("an unknown canonical id must return ZERO rows", 0, result.entries.size)
        assertTrue("and it must not report the table as readable content",
            result.entries.isEmpty())
    }

    /** After deleting every alias, the link still scopes the read correctly. */
    @Test
    fun deletedAliasesDoNotWidenTheQuery() {
        val cidA = resolve("A")
        val cidB = resolve("B")
        val aId = save("A", "a-1", 1_700_000_000_001L)
        val bId = save("B", "b-1", 1_700_000_000_002L)
        link(aId, cidA); link(bId, cidB)

        // Remove the legacy aliases for A, keeping only its message link.
        val db = InboxDatabase(ctx)
        try {
            db.writableDatabase.execSQL(
                "DELETE FROM conversation_aliases WHERE conversation_id = ?", arrayOf(cidA)
            )
        } finally { db.close() }

        val a = read(cidA)
        assertEquals("the linked message is still reachable", 1, a.entries.size)
        assertEquals("a-1", a.entries[0].body)
        assertTrue("B's message must never leak into A's read",
            a.entries.none { it.body == "b-1" })
    }

    /** A partial migration (some rows linked, some not) must not widen the read. */
    @Test
    fun partiallyMigratedConversationReadsOnlyLinkedRows() {
        val cid = resolve("部分遷移")
        // Two rows linked, one row present in the table but NOT linked.
        val linked1 = save("部分遷移", "linked-1", 1_700_000_000_001L)
        val linked2 = save("部分遷移", "linked-2", 1_700_000_000_002L)
        link(linked1, cid); link(linked2, cid)
        save("完全不同的對話", "unlinked-other", 1_700_000_000_003L)

        val result = read(cid)
        assertTrue("only the linked rows are returned",
            result.entries.all { it.body.startsWith("linked-") })
        assertEquals("exactly the two linked rows", 2, result.entries.size)
    }

    /** Merge/unmerge must not let a read escape the conversation. */
    @Test
    fun mergeAndUnmergeNeverWidenARead() {
        val cidA = resolve("M-A")
        val cidB = resolve("M-B")
        val aId = save("M-A", "a-body", 1_700_000_000_001L)
        val bId = save("M-B", "b-body", 1_700_000_000_002L)
        link(aId, cidA); link(bId, cidB)

        val store = ConversationStore(ctx)
        try { store.merge(from = cidB, into = cidA) } finally { store.close() }

        val merged = read(cidA)
        assertEquals("after merge the target holds both messages", 2, merged.entries.size)
        // cidB no longer owns anything; reading it must not fall back to a scan.
        val gone = read(cidB)
        assertEquals("the merged-away id reads nothing", 0, gone.entries.size)

        val store2 = ConversationStore(ctx)
        try { store2.unmerge(mergedAway = cidB) } finally { store2.close() }

        val a = read(cidA)
        assertEquals("after unmerge A holds only its own message", 1, a.entries.size)
        assertEquals("a-body", a.entries[0].body)
        val b = read(cidB)
        assertEquals("after unmerge B holds only its own message", 1, b.entries.size)
        assertEquals("b-body", b.entries[0].body)
    }

    /** A conversation in another package must not leak across packages. */
    @Test
    fun crossPackageReadStaysScoped() {
        val tg = "org.telegram.messenger"
        val lineCid = resolve("同名")
        val tgCid = resolve("同名", tg)
        val lineId = save("同名", "line-body", 1_700_000_000_001L)
        val tgId = save("同名", "tg-body", 1_700_000_000_002L, tg)
        link(lineId, lineCid); link(tgId, tgCid)

        val line = read(lineCid)
        assertEquals(1, line.entries.size)
        assertEquals("line-body", line.entries[0].body)
        val telegram = read(tgCid)
        assertEquals(1, telegram.entries.size)
        assertEquals("tg-body", telegram.entries[0].body)
    }

    /** An empty database must never produce rows for any id. */
    @Test
    fun emptyDatabaseReturnsEmptyForAnyId() {
        assertEquals(0, read("cv_anything").entries.size)
        assertEquals(0, read("").entries.size)
        assertEquals(0, read("k:jp.naver.line.android\u0000title").entries.size)
    }
}
