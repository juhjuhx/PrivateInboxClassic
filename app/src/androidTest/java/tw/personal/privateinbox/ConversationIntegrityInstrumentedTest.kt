package tw.personal.privateinbox

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G2.5-C — SQLite integrity, automated.
 *
 * These checks used to be things a human might run once by hand. They now run in
 * the suite so a schema change that breaks referential sanity fails a build
 * instead of being discovered during a support incident.
 *
 * The schema has no declared FOREIGN KEY constraints (the canonical layer is
 * joined by convention), so `foreign_key_check` is expected to be empty; the
 * orphan checks below are the real referential audit.
 */
@RunWith(AndroidJUnit4::class)
class ConversationIntegrityInstrumentedTest {

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

    private fun scalar(sql: String, args: Array<String> = emptyArray()): String {
        val db = InboxDatabase(ctx)
        return try {
            db.readableDatabase.rawQuery(sql, args).use { c ->
                c.moveToFirst(); c.getString(0) ?: ""
            }
        } finally { db.close() }
    }

    private fun count(sql: String, args: Array<String> = emptyArray()): Int =
        scalar(sql, args).toInt()

    private fun buildPopulation(): Triple<String, String, Long> {
        val a = resolve(pkg, "sc-A", "群組A")
        val b = resolve(pkg, "sc-B", "群組B")
        val idA = save("群組A", "Alice", "msg-a", 1_700_000_000_001L, "c:Alice:msg-a:i0")
        val idB = save("群組B", "Bob", "msg-b", 1_700_000_000_002L, "c:Bob:msg-b:i0")
        val store = ConversationStore(ctx)
        try {
            store.linkMessage(idA, a)
            store.linkMessage(idB, b)
        } finally { store.close() }
        return Triple(a, b, idA)
    }

    private fun save(
        title: String, sender: String, body: String, ts: Long, disc: String
    ): Long {
        val legacyKey = LegacyKeyCodec.format(pkg, null, title)
        val db = InboxDatabase(ctx)
        try {
            db.save(
                packageName = pkg, receivedAt = System.currentTimeMillis(),
                sourceTimestamp = ts, title = title, sender = sender, body = body,
                image = null, conversationKey = legacyKey,
                messageKind = SourceRegistry.MessageKind.GROUP_MESSAGE.name,
                messageDiscriminator = disc
            )
            return db.readableDatabase.rawQuery(
                "SELECT id FROM messages ORDER BY id DESC LIMIT 1", null
            ).use { c -> c.moveToFirst(); c.getLong(0) }
        } finally { db.close() }
    }

    private fun resolve(pkgName: String, shortcut: String?, title: String): String {
        val store = ConversationStore(ctx)
        return try {
            ConversationIdentityResolver(store).resolve(pkgName, shortcut, title).conversationId
        } finally { store.close() }
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun integrityCheckIsClean() {
        buildPopulation()
        val result = scalar("PRAGMA integrity_check")
        assertEquals("PRAGMA integrity_check must say ok", "ok", result)
    }

    @Test
    fun foreignKeyCheckIsClean() {
        buildPopulation()
        // No declared FKs: the check must return zero rows.
        val db = InboxDatabase(ctx)
        val rows = try {
            var n = 0
            db.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { c ->
                while (c.moveToNext()) n++
            }
            n
        } finally { db.close() }
        assertEquals("foreign_key_check must report no violations", 0, rows)
    }

    @Test
    fun noOrphanMessageLinks() {
        buildPopulation()
        val orphans = count(
            "SELECT count(*) FROM message_conversation l " +
                "WHERE l.message_id NOT IN (SELECT id FROM messages) " +
                "   OR l.conversation_id NOT IN " +
                "      (SELECT conversation_id FROM conversations)"
        )
        assertEquals("no message link may reference a missing row", 0, orphans)
    }

    @Test
    fun noAliasesPointingAtMissingConversations() {
        buildPopulation()
        val orphans = count(
            "SELECT count(*) FROM conversation_aliases a " +
                "WHERE a.conversation_id NOT IN " +
                "      (SELECT conversation_id FROM conversations)"
        )
        assertEquals("no alias may reference a missing conversation", 0, orphans)
    }

    @Test
    fun mergeJournalIsConsistentWithTheMergeLog() {
        val (a, b, _) = buildPopulation()
        val store = ConversationStore(ctx)
        try {
            store.merge(a, b)
        } finally { store.close() }

        // Every merge log entry must have at least one undo row, and every undo
        // row must belong to a logged batch.
        val logWithoutUndo = count(
            "SELECT count(*) FROM conversation_merge_log m WHERE m.undone = 0 " +
                "AND NOT EXISTS (SELECT 1 FROM conversation_alias_undo u " +
                "                WHERE u.batch = m.batch)"
        )
        assertEquals("a live merge must have journaled its aliases", 0, logWithoutUndo)
        val undoWithoutLog = count(
            "SELECT count(*) FROM conversation_alias_undo u WHERE NOT EXISTS " +
                "(SELECT 1 FROM conversation_merge_log m WHERE m.batch = u.batch)"
        )
        assertEquals("no undo row may lack a merge log entry", 0, undoWithoutLog)
    }

    @Test
    fun clearRemovesEverythingIncludingTheCanonicalLayer() {
        buildPopulation()
        TestDataIsolation.clearDatabase(ctx)
        assertEquals("messages cleared", 0, count("SELECT count(*) FROM messages"))
        assertEquals("aliases cleared", 0, count("SELECT count(*) FROM conversation_aliases"))
        assertEquals("conversations cleared", 0, count("SELECT count(*) FROM conversations"))
        assertEquals("links cleared", 0, count("SELECT count(*) FROM message_conversation"))
        assertEquals("merge log cleared", 0, count("SELECT count(*) FROM conversation_merge_log"))
        assertEquals("alias undo cleared", 0, count("SELECT count(*) FROM conversation_alias_undo"))
        assertEquals("link undo cleared", 0, count("SELECT count(*) FROM message_conversation_undo"))
    }

    @Test
    fun repeatedOpensKeepIntegrityClean() {
        buildPopulation()
        repeat(3) {
            val db = InboxDatabase(ctx)
            db.writableDatabase
            db.close()
        }
        assertEquals("ok", scalar("PRAGMA integrity_check"))
        assertEquals(
            "no duplicate aliases after repeated opens",
            0, count(
                "SELECT count(*) FROM (SELECT package_name, alias_type, alias_value, " +
                    "count(*) c FROM conversation_aliases GROUP BY 1,2,3 HAVING c > 1)"
            )
        )
    }

    @Test
    fun concurrentWriterDoesNotBreakIntegrity() {
        val a = resolve(pkg, "sc-A", "群組A")
        // Two threads writing at once: one through save(), one linking.
        val errors = java.util.Collections.synchronizedList(ArrayList<Throwable>())
        val t1 = Thread {
            try {
                repeat(20) { i ->
                    save("群組A", "Alice", "burst-$i",
                        1_700_000_000_100L + i, "c:Alice:burst-$i:i$i")
                }
            } catch (t: Throwable) { errors.add(t) }
        }
        // T01-C fix: link REAL ids. The old test used 1000L+i, which are ids of
        // messages that do not exist, so the "concurrent linking" proved nothing
        // about the actual referential behaviour — and any orphan audit would
        // have caught it. Insert 20 real messages first and link their ids.
        val realIds = ArrayList<Long>()
        repeat(20) { i ->
            realIds.add(
                save("群組A", "Alice", "concurrent-$i",
                    1_700_000_000_200L + i, "c:Alice:concurrent-$i:i$i")
            )
        }
        val t2 = Thread {
            try {
                val store = ConversationStore(ctx)
                try {
                    for (id in realIds) store.linkMessage(id, a)
                } finally { store.close() }
            } catch (t: Throwable) { errors.add(t) }
        }
        val beforeCount = count("SELECT count(*) FROM messages")
        t1.start(); t2.start(); t1.join(); t2.join()
        assertEquals("no exceptions during concurrent access: $errors", 0, errors.size)
        assertEquals("ok", scalar("PRAGMA integrity_check"))
        assertEquals(
            "the 20 concurrent messages are all stored (relative to the pre-existing rows)",
            beforeCount + 20, count("SELECT count(*) FROM messages"))
        assertEquals(
            "every one of the 20 real messages is linked (no orphans)",
            20, count(
                "SELECT count(*) FROM message_conversation l " +
                    "WHERE l.conversation_id = '" + a.replace("'", "''") + "' " +
                    "AND l.message_id IN (SELECT id FROM messages)"
            )
        )
        assertEquals(
            "no orphan links remain",
            0, count(
                "SELECT count(*) FROM message_conversation l " +
                    "WHERE l.message_id NOT IN (SELECT id FROM messages)"
            )
        )
    }

    private fun assertFalse(msg: String, b: Boolean) = org.junit.Assert.assertFalse(msg, b)
}
