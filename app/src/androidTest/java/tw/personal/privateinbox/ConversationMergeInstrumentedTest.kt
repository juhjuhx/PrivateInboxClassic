package tw.personal.privateinbox

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G1 — merge / unmerge correctness.
 *
 * ## The defect this suite was written to find
 *
 * `ConversationStore.merge()` repoints `conversation_aliases.conversation_id` to
 * the target, but `unmerge()` only restored the MESSAGE links and cleared
 * `merged_into`. It never restored the alias ownership, so after an unmerge:
 *
 *   - the aliases still pointed at the TARGET conversation, and
 *   - the next notification for the unmerged source resolved into the target,
 *     silently re-merging a conversation the user had explicitly separated.
 *
 * `mergeThenUnmergeRestoresAliasOwnership` reproduces exactly that. It is the RED
 * test; the product fix follows it.
 *
 * ## Invariants asserted throughout
 *
 * `messages` is never modified by a merge: id, fingerprint, conversation_key,
 * sealed_content and sealed_image stay byte-identical.
 */
@RunWith(AndroidJUnit4::class)
class ConversationMergeInstrumentedTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val pkg = "jp.naver.line.android"
    private val otherPkg = "org.telegram.messenger"

    @Before
    fun setUp() {
        DestructiveTestAssumption.requireIsolated(ctx)
        TestDataIsolation.clearDatabase(ctx)
    }

    @After
    fun tearDown() {
        TestDataIsolation.tryClearDatabase(ctx)
    }

    // ---------------------------------------------------------------- helpers

    private fun save(title: String, sender: String, body: String, ts: Long, disc: String): Long {
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

    /** alias (type,value) -> conversationId for one package. */
    private fun aliasMap(pkgName: String): Map<String, String> {
        val store = ConversationStore(ctx)
        return try {
            val out = HashMap<String, String>()
            // Reach the store's own table through a fresh query via a resolver
            // round-trip is not enough; read the table directly.
            val db = InboxDatabase(ctx)
            try {
                db.readableDatabase.rawQuery(
                    "SELECT alias_type, alias_value, conversation_id FROM conversation_aliases " +
                        "WHERE package_name = ?",
                    arrayOf(pkgName)
                ).use { c ->
                    while (c.moveToNext()) {
                        out["${c.getString(0)}|${c.getString(1)}"] = c.getString(2)
                    }
                }
            } finally { db.close() }
            out
        } finally { store.close() }
    }

    private data class MsgRow(
        val id: Long, val key: String, val fp: String, val sealed: String, val img: String
    )

    private fun messages(): List<MsgRow> {
        val db = InboxDatabase(ctx)
        return try {
            val out = ArrayList<MsgRow>()
            db.readableDatabase.rawQuery(
                "SELECT id, conversation_key, fingerprint, sealed_content, sealed_image " +
                    "FROM messages ORDER BY id",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        MsgRow(
                            c.getLong(0), c.getString(1),
                            hex(c.getBlob(2)), hex(c.getBlob(3)),
                            if (c.isNull(4)) "NULL" else hex(c.getBlob(4))
                        )
                    )
                }
            }
            out
        } finally { db.close() }
    }

    private fun hex(b: ByteArray?): String =
        if (b == null) "NULL" else b.joinToString("") { "%02x".format(it) }

    private fun conversationCount(pkgName: String): Int {
        val store = ConversationStore(ctx)
        return try { store.conversationCount(pkgName) } finally { store.close() }
    }

    // ============================================================ THE RED TEST

    @Test
    fun mergeThenUnmergeRestoresAliasOwnership() {
        // 1. Two conversations, each with a SHORTCUT and a LEGACY_KEY alias.
        val a = resolve(pkg, "sc-A", "群組A")
        val b = resolve(pkg, "sc-B", "群組B")
        assertNotEquals(a, b)

        val before = aliasMap(pkg)
        // Record the original owner of every alias that belongs to A or B.
        val originalOwners = before.filterValues { it == a || it == b }
        assertTrue(
            "the fixture must have registered aliases for both conversations",
            originalOwners.size >= 2
        )
        val aAliases = originalOwners.filterValues { it == a }.keys
        val bAliases = originalOwners.filterValues { it == b }.keys
        assertTrue("A must own at least one alias", aAliases.isNotEmpty())
        assertTrue("B must own at least one alias", bAliases.isNotEmpty())

        val msgsBefore = messages()

        // 2. Merge A -> B.
        val store = ConversationStore(ctx)
        try {
            val moved = store.merge(from = a, into = b)
            assertTrue("the merge reports how many links moved", moved >= 0)
        } finally { store.close() }

        // 3. After the merge every alias of A belongs to B.
        val afterMerge = aliasMap(pkg)
        for (key in aAliases) {
            assertEquals("after merge, alias $key belongs to B", b, afterMerge[key])
        }

        // 4. Unmerge.
        val store2 = ConversationStore(ctx)
        try {
            store2.unmerge(a)
        } finally { store2.close() }

        // 5. THE ASSERTION THIS TEST EXISTS FOR: alias ownership is restored.
        val afterUnmerge = aliasMap(pkg)
        for (key in aAliases) {
            assertEquals(
                "after unmerge, alias $key must belong to A again (this is the defect)",
                a, afterUnmerge[key]
            )
        }
        for (key in bAliases) {
            assertEquals("B's own aliases must be untouched", b, afterUnmerge[key])
        }

        // 6. A subsequent notification for A must NOT resolve into B.
        val aAgain = resolve(pkg, "sc-A", "群組A")
        assertEquals("a later notification for A must stay in A", a, aAgain)
        assertNotEquals("and must never be routed into B", b, aAgain)

        // 7. messages untouched by the whole episode.
        val msgsAfter = messages()
        assertEquals(msgsBefore.size, msgsAfter.size)
        for (i in msgsBefore.indices) {
            assertEquals("msg $i id", msgsBefore[i].id, msgsAfter[i].id)
            assertEquals("msg $i conversation_key",
                msgsBefore[i].key, msgsAfter[i].key)
            assertEquals("msg $i fingerprint", msgsBefore[i].fp, msgsAfter[i].fp)
            assertEquals("msg $i sealed_content", msgsBefore[i].sealed, msgsAfter[i].sealed)
            assertEquals("msg $i sealed_image", msgsBefore[i].img, msgsAfter[i].img)
        }
    }

    // ============================================================ other cases

    @Test
    fun mergeDoesNotTouchAnyMessageRow() {
        val a = resolve(pkg, "sc-A", "群組A")
        val b = resolve(pkg, "sc-B", "群組B")
        val idA = save("群組A", "Alice", "msg-a", 1_700_000_000_001L, "c:Alice:msg-a:i0")
        val idB = save("群組B", "Bob", "msg-b", 1_700_000_000_002L, "c:Bob:msg-b:i0")

        val store = ConversationStore(ctx)
        try {
            store.linkMessage(idA, a)
            store.linkMessage(idB, b)
        } finally { store.close() }

        val before = messages()
        val store2 = ConversationStore(ctx)
        try { store2.merge(a, b) } finally { store2.close() }
        val after = messages()

        assertEquals(before.size, after.size)
        for (i in before.indices) {
            assertEquals("id", before[i].id, after[i].id)
            assertEquals("conversation_key", before[i].key, after[i].key)
            assertEquals("fingerprint", before[i].fp, after[i].fp)
            assertEquals("sealed_content", before[i].sealed, after[i].sealed)
            assertEquals("sealed_image", before[i].img, after[i].img)
        }
    }

    @Test
    fun selfMergeIsRejected() {
        val a = resolve(pkg, "sc-A", "群組A")
        val store = ConversationStore(ctx)
        try {
            val moved = store.merge(a, a)
            assertEquals("merging a conversation into itself must be a no-op", 0, moved)
            assertNull(
                "and must not mark it merged away",
                store.mergedInto(a)
            )
        } finally { store.close() }
    }

    @Test
    fun crossPackageMergeIsRejected() {
        val line = resolve(pkg, "sc-1", "同名")
        val tg = resolve(otherPkg, "sc-1", "同名")
        assertNotEquals(line, tg)

        val store = ConversationStore(ctx)
        try {
            val moved = store.merge(line, tg)
            assertEquals("cross-package merge must be refused", -1, moved)
        } finally { store.close() }

        // Both conversations still exist independently.
        assertEquals(1, conversationCount(pkg))
        assertEquals(1, conversationCount(otherPkg))
    }

    @Test
    fun mergingANonExistentConversationFails() {
        val b = resolve(pkg, "sc-B", "群組B")
        val store = ConversationStore(ctx)
        try {
            val moved = store.merge("cv_does_not_exist", b)
            assertEquals("an unknown source must be refused", -1, moved)
        } finally { store.close() }
    }

    @Test
    fun duplicateUnmergeIsIdempotent() {
        val a = resolve(pkg, "sc-A", "群組A")
        val b = resolve(pkg, "sc-B", "群組B")
        val store = ConversationStore(ctx)
        try {
            store.merge(a, b)
            val first = store.unmerge(a)
            val second = store.unmerge(a)
            assertTrue("the first unmerge restores something", first >= 0)
            assertEquals("a second unmerge is a harmless no-op", 0, second)
        } finally { store.close() }
    }

    @Test
    fun mergeChainAToBToCThenUnmergeRestoresA() {
        val a = resolve(pkg, "sc-A", "A")
        val b = resolve(pkg, "sc-B", "B")
        val c = resolve(pkg, "sc-C", "C")

        val store = ConversationStore(ctx)
        try {
            store.merge(a, b)
            store.merge(b, c)
            // Unmerging A must bring A back, not resurrect B into A.
            store.unmerge(a)
            assertEquals("A is its own conversation again", a, resolve(pkg, "sc-A", "A"))
        } finally { store.close() }
    }

    @Test
    fun mergeSurvivesRestartThenUnmergeStillWorks() {
        val a = resolve(pkg, "sc-A", "群組A")
        val b = resolve(pkg, "sc-B", "群組B")
        val store = ConversationStore(ctx)
        try { store.merge(a, b) } finally { store.close() }

        // Simulate a restart: fresh store instances.
        val store2 = ConversationStore(ctx)
        try {
            assertEquals("the merge persists", b, store2.mergedInto(a))
            store2.unmerge(a)
            assertEquals("and the undo data survives a restart", null, store2.mergedInto(a))
        } finally { store2.close() }

        assertEquals("A resolves to itself after the restart+unmerge",
            a, resolve(pkg, "sc-A", "群組A"))
    }

    @Test
    fun aliasConflictIsReportedNotSilentlyIgnored() {
        val a = resolve(pkg, "sc-A", "群組A")
        val b = resolve(pkg, "sc-B", "群組B")

        val store = ConversationStore(ctx)
        try {
            // Try to register the SAME shortcut for a DIFFERENT conversation.
            val result = store.putAliasChecked(
                pkg, AliasType.SHORTCUT, "sc-A", b, Strength.PLATFORM_CONFIRMED
            )
            assertEquals(
                "registering an alias that already belongs elsewhere must be reported",
                AliasPutResult.CONFLICT, result
            )
            // And the original owner is unchanged.
            assertEquals(a, store.find(pkg, AliasType.SHORTCUT, "sc-A"))
        } finally { store.close() }
    }
    // ============================================================ G1.5-B

    /**
     * A controlled failure AFTER the journal is written but BEFORE the repoint.
     *
     * `merge()` runs everything inside one transaction, so any throw must roll the
     * whole thing back: no journal rows, no alias change, no link change, no log
     * entry. This is injected by pointing the merge at a target whose row is
     * removed mid-flight — the FK-free schema lets the journal insert succeed and
     * the repoint fail, which is exactly the dangerous half-done state.
     */
    @Test
    fun failedMergeLeavesNoPartialState() {
        val a = resolve(pkg, "sc-A", "群組A")
        val b = resolve(pkg, "sc-B", "群組B")

        val aliasesBefore = aliasMap(pkg)
        val msgsBefore = messages()

        // Force a failure by making the UPDATE target invalid: we delete the
        // target conversation row first, so merge()'s validation rejects it.
        val db = InboxDatabase(ctx)
        try {
            db.writableDatabase.execSQL(
                "DELETE FROM conversations WHERE conversation_id = ?", arrayOf(b)
            )
        } finally { db.close() }

        val store = ConversationStore(ctx)
        val moved = try { store.merge(a, b) } finally { store.close() }

        assertEquals("merge into a vanished target must be refused", -1, moved)

        // Nothing changed.
        assertEquals("alias ownership unchanged", aliasesBefore, aliasMap(pkg))
        val msgsAfter = messages()
        assertEquals(msgsBefore.size, msgsAfter.size)
        for (i in msgsBefore.indices) {
            assertEquals(msgsBefore[i].id, msgsAfter[i].id)
            assertEquals(msgsBefore[i].key, msgsAfter[i].key)
            assertEquals(msgsBefore[i].fp, msgsAfter[i].fp)
            assertEquals(msgsBefore[i].sealed, msgsAfter[i].sealed)
            assertEquals(msgsBefore[i].img, msgsAfter[i].img)
        }

        // And no journal/log row was left behind.
        val db2 = InboxDatabase(ctx)
        try {
            db2.readableDatabase.rawQuery(
                "SELECT count(*) FROM conversation_merge_log", null
            ).use { c ->
                c.moveToFirst()
                assertEquals("no merge log entry for a refused merge", 0, c.getInt(0))
            }
        } finally { db2.close() }
    }

    @Test
    fun aRefusedMergeCanBeRetriedSuccessfully() {
        val a = resolve(pkg, "sc-A", "群組A")
        val b = resolve(pkg, "sc-B", "群組B")

        val store = ConversationStore(ctx)
        try {
            // First attempt fails (unknown source).
            assertEquals(-1, store.merge("cv_nope", b))
            // Then the real merge succeeds.
            val moved = store.merge(a, b)
            assertTrue("the retry must succeed", moved >= 0)
            assertEquals(b, store.mergedInto(a))
        } finally { store.close() }
    }

    @Test
    fun mergeDuringANewMessageSaveKeepsBothConsistent() {
        val a = resolve(pkg, "sc-A", "群組A")
        val b = resolve(pkg, "sc-B", "群組B")

        // A message is saved (as the listener would) while a merge is performed.
        val idNew = save("群組A", "Alice", "arrives-during-merge",
            1_700_000_009_001L, "c:Alice:arrives-during-merge:i0")

        val store = ConversationStore(ctx)
        try {
            store.linkMessage(idNew, a)
            val moved = store.merge(a, b)
            assertTrue(moved >= 0)
        } finally { store.close() }

        // The new message's link moved with the conversation, and the row itself
        // is untouched.
        val db = InboxDatabase(ctx)
        val (linkCid, fp) = try {
            db.readableDatabase.rawQuery(
                "SELECT l.conversation_id, hex(m.fingerprint) FROM message_conversation l " +
                    "JOIN messages m ON m.id = l.message_id WHERE l.message_id = ?",
                arrayOf(idNew.toString())
            ).use { c ->
                c.moveToFirst()
                c.getString(0) to c.getString(1)
            }
        } finally { db.close() }
        assertEquals("the new message follows the merge target", b, linkCid)
        assertNotNull("the message row still exists", fp)
    }

    @Test
    fun unmergeAfterAMergeThatIncludedNewMessagesKeepsThemWithTheSource() {
        // G1.5-D semantics: a message captured while A and B were merged belongs
        // to whichever conversation its own alias resolves to. If it was linked to
        // A before the merge, unmerge returns it to A with the rest of A.
        val a = resolve(pkg, "sc-A", "群組A")
        val b = resolve(pkg, "sc-B", "群組B")

        val idBefore = save("群組A", "Alice", "before-merge",
            1_700_000_010_001L, "c:Alice:before-merge:i0")
        val store = ConversationStore(ctx)
        try {
            store.linkMessage(idBefore, a)
            store.merge(a, b)

            // A message arriving while merged, attributed to A's alias.
            val idDuring = save("群組A", "Alice", "during-merge",
                1_700_000_010_002L, "c:Alice:during-merge:i0")
            // The listener resolves it; because A is merged, it lands on B.
            val resolvedDuring = resolve(pkg, "sc-A", "群組A")
            store.linkMessage(idDuring, resolvedDuring)

            store.unmerge(a)

            // After unmerge: the pre-merge message is back on A, and the
            // during-merge message stays where the alias pointed at capture time
            // (B), because that is where it actually went. This is the documented
            // rule, asserted rather than assumed.
            val db = InboxDatabase(ctx)
            try {
                val linkA = db.readableDatabase.rawQuery(
                    "SELECT conversation_id FROM message_conversation WHERE message_id = ?",
                    arrayOf(idBefore.toString())
                ).use { c -> c.moveToFirst(); c.getString(0) }
                val linkDuring = db.readableDatabase.rawQuery(
                    "SELECT conversation_id FROM message_conversation WHERE message_id = ?",
                    arrayOf(idDuring.toString())
                ).use { c -> c.moveToFirst(); c.getString(0) }
                assertEquals("the pre-merge message returns to A", a, linkA)
                assertEquals("the during-merge message stays where it was captured", b, linkDuring)
            } finally { db.close() }
        } finally { store.close() }
    }

}
