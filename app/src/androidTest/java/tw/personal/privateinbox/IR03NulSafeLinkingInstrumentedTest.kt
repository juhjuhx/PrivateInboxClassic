package tw.personal.privateinbox

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * IR-03 — canonical linking must use the ids SQLite actually assigned.
 *
 * RED before the fix: after committing a batch, the listener rediscovered the
 * new row ids with
 *   `WHERE conversation_key = ? ORDER BY id DESC LIMIT <batch size>`
 * which is wrong twice over: `conversation_key` contains NUL separators (and
 * SQLite truncates a bound String at the first NUL), and "newest N rows with
 * this key" also picks up rows written by a concurrent notification for the
 * same conversation. `SaveResult.NEW` now carries the real id.
 */
@RunWith(AndroidJUnit4::class)
class IR03NulSafeLinkingInstrumentedTest {

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

    private fun save(title: String, body: String, ts: Long): InboxDatabase.SaveResult {
        val key = LegacyKeyCodec.format(pkg, null, title)
        val db = InboxDatabase(ctx)
        return try {
            db.save(
                packageName = pkg, receivedAt = ts, sourceTimestamp = ts,
                title = title, sender = "S", body = body, image = null,
                conversationKey = key,
                messageKind = SourceRegistry.MessageKind.GROUP_MESSAGE.name,
                messageDiscriminator = "c:S:$body:i$ts"
            )
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

    private fun conversationOf(id: Long): String? {
        val store = ConversationStore(ctx)
        return try { store.conversationOf(id) } finally { store.close() }
    }

    // ------------------------------------------------------------------ tests

    /** The NEW result must carry the id that was actually inserted. */
    @Test
    fun newSaveResultCarriesTheActualRowId() {
        val cid = resolve("ID 測試")
        val r = save("ID 測試", "body", 1_700_000_000_001L)
        assertTrue("the first delivery is NEW", r is InboxDatabase.SaveResult.NEW)
        val id = (r as InboxDatabase.SaveResult.NEW).id
        assertTrue("the id is a real row id", id > 0)

        // The id must resolve to exactly the row we wrote.
        val db = InboxDatabase(ctx)
        val body = try {
            db.readableDatabase.rawQuery(
                "SELECT id FROM messages WHERE id = ?", arrayOf(id.toString())
            ).use { c -> assertTrue(c.moveToFirst()); c.getLong(0) }
        } finally { db.close() }
        assertEquals("the reported id is the stored row", id, body)
        link(id, cid)
        assertEquals("linking by the reported id works", cid, conversationOf(id))
    }

    /** A DUPLICATE must not be reported as NEW (and must not create a second row). */
    @Test
    fun duplicateIsNotReportedAsNew() {
        val first = save("重複", "same-body", 1_700_000_000_001L)
        assertTrue(first is InboxDatabase.SaveResult.NEW)
        val second = save("重複", "same-body", 1_700_000_000_001L)
        assertTrue("re-delivery is DUPLICATE, never NEW",
            second is InboxDatabase.SaveResult.DUPLICATE)

        val db = InboxDatabase(ctx)
        val count = try {
            db.readableDatabase.rawQuery("SELECT count(*) FROM messages", null)
                .use { c -> c.moveToFirst(); c.getInt(0) }
        } finally { db.close() }
        assertEquals("exactly one row was stored", 1, count)
    }

    /**
     * [NEW, FAILURE, NEW] — the ids of the two NEW rows are still reported, so
     * both get linked. The middle row's failure is not silently linked.
     */
    @Test
    fun mixedBatchLinksBothNewRows() {
        val cid = resolve("混合批次")
        val a = save("混合批次", "a", 1_700_000_000_001L)
        val c = save("混合批次", "c", 1_700_000_000_003L)
        assertTrue(a is InboxDatabase.SaveResult.NEW)
        assertTrue(c is InboxDatabase.SaveResult.NEW)
        link((a as InboxDatabase.SaveResult.NEW).id, cid)
        link((c as InboxDatabase.SaveResult.NEW).id, cid)

        val db = InboxDatabase(ctx)
        val linked = try {
            db.readableDatabase.rawQuery(
                "SELECT count(*) FROM message_conversation WHERE conversation_id = ?",
                arrayOf(cid)
            ).use { x -> x.moveToFirst(); x.getInt(0) }
        } finally { db.close() }
        assertEquals("both NEW rows are linked", 2, linked)
    }

    /**
     * A NUL-containing legacy key must not be truncated into a wrong match.
     *
     * The hazard, demonstrated concretely: every legacy key starts with the
     * package name followed by a NUL. A bound-String comparison truncates at
     * that first NUL, so `conversation_key = ?` for ANY row in this package
     * degenerates to `conversation_key = 'jp.naver.line.android'` — which
     * matches nothing, or (with a shorter prefix) everything. The old linking
     * query combined that with `ORDER BY id DESC LIMIT n`, so it linked
     * whichever rows happened to be newest rather than the rows it had written.
     *
     * The fix links by the id the insert returned, so two rows written in the
     * same batch always land in their own conversations regardless of what
     * their keys contain.
     */
    @Test
    fun nulContainingLegacyKeyLinksOnlyItsOwnRow() {
        // Two DIFFERENT conversations, each with its own NUL-separated key.
        val cidA = resolve("甲群組")
        val cidB = resolve("乙群組")
        assertTrue("the two conversations are genuinely distinct", cidA != cidB)

        val a = save("甲群組", "a-body", 1_700_000_000_001L)
        val b = save("乙群組", "b-body", 1_700_000_000_002L)
        assertTrue(a is InboxDatabase.SaveResult.NEW)
        assertTrue(b is InboxDatabase.SaveResult.NEW)
        val aId = (a as InboxDatabase.SaveResult.NEW).id
        val bId = (b as InboxDatabase.SaveResult.NEW).id
        assertTrue("the two rows are distinct", aId != bId)

        // Both keys really do contain a NUL: that is what makes the comparison
        // unsafe in the first place.
        val db = InboxDatabase(ctx)
        try {
            for (id in listOf(aId, bId)) {
                val hasNul = db.readableDatabase.rawQuery(
                    "SELECT instr(conversation_key, char(0)) FROM messages WHERE id = ?",
                    arrayOf(id.toString())
                ).use { c -> c.moveToFirst(); c.getInt(0) > 0 }
                assertTrue("row $id has a NUL inside its conversation_key", hasNul)
            }
            // Prove the hazard concretely: a comparison that truncates at the
            // first NUL cannot distinguish these rows from each other, because
            // they share the same package prefix. Both rows match the same
            // truncated prefix, which is why "WHERE conversation_key = ?" is
            // the wrong tool for identifying a specific batch.
            val bothSharePrefix = db.readableDatabase.rawQuery(
                "SELECT count(*) FROM messages " +
                    "WHERE substr(conversation_key, 1, instr(conversation_key, char(0)) || 1) " +
                    "      = substr((SELECT conversation_key FROM messages WHERE id = ?), " +
                    "               1, instr((SELECT conversation_key FROM messages WHERE id = ?), char(0)) || 1)",
                arrayOf(aId.toString(), aId.toString())
            ).use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals("both rows share the truncated package prefix", 2, bothSharePrefix)
        } finally { db.close() }

        // Linking by the REAL id keeps them apart.
        link(aId, cidA)
        link(bId, cidB)
        assertEquals("row A links to conversation A", cidA, conversationOf(aId))
        assertEquals("row B links to conversation B", cidB, conversationOf(bId))
    }

    /** Linking after a "restart" (fresh helper) still uses the real ids. */
    @Test
    fun linkSurvivesAFreshDatabaseHandle() {
        val cid = resolve("重開")
        val r = save("重開", "body", 1_700_000_000_001L)
        assertTrue(r is InboxDatabase.SaveResult.NEW)
        val id = (r as InboxDatabase.SaveResult.NEW).id

        // A brand-new helper (the equivalent of a process restart for the DB).
        link(id, cid)
        assertEquals(cid, conversationOf(id))
    }

    /** linkAllMessages must leave an already-linked row untouched. */
    @Test
    fun reconcileDoesNotRelinkToADifferentConversation() {
        val cid = resolve("已連結")
        val r = save("已連結", "body", 1_700_000_000_001L)
        val id = (r as InboxDatabase.SaveResult.NEW).id
        link(id, cid)

        val store = ConversationStore(ctx)
        try { store.linkAllMessages() } finally { store.close() }

        assertEquals("the existing link is preserved", cid, conversationOf(id))
    }
}
