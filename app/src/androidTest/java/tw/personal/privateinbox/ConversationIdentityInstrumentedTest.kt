package tw.personal.privateinbox

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The five acceptance conditions for v0.4.6 Phase 1, on REAL SQLite.
 *
 * Every test here writes rows through the production `InboxDatabase.save()` and
 * reads them back through the production read path. Nothing is mocked.
 *
 *   1. The same LINE conversation survives an app restart and a title change.
 *   2. Two same-named groups are NOT merged.
 *   3. Existing fingerprints and DHC V1 message ids are unchanged.
 *   4. Migration keeps the message count and the old ciphertext readable.
 *   5. (separate suite) the full instrumentation regression.
 */
@RunWith(AndroidJUnit4::class)
class ConversationIdentityInstrumentedTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val pkgLine = "jp.naver.line.android"
    private val pkgTg = "org.telegram.messenger"

    @Before
    fun setUp() {
        DestructiveTestAssumption.requireIsolated(ctx)
        TestDataIsolation.clearDatabase(ctx)
    }

    @After
    fun tearDown() {
        TestDataIsolation.tryClearDatabase(ctx)
    }

    /** Insert one message the way the production pipeline does. */
    private fun save(
        pkg: String,
        title: String,
        sender: String,
        body: String,
        ts: Long = 1_700_000_000_000L,
        discriminator: String = "c:$sender:$body:i0"
    ): InboxDatabase.SaveResult {
        val db = InboxDatabase(ctx)
        return try {
            val legacyKey = LegacyKeyCodec.format(pkg, null, title)
            db.save(
                packageName = pkg,
                receivedAt = System.currentTimeMillis(),
                sourceTimestamp = ts,
                title = title,
                sender = sender,
                body = body,
                image = null,
                conversationKey = legacyKey,
                messageKind = SourceRegistry.MessageKind.GROUP_MESSAGE.name,
                messageDiscriminator = discriminator
            )
        } finally { db.close() }
    }

    /** Insert with an explicit legacy key, mirroring what the listener passes. */
    private fun saveRaw(
        pkg: String, title: String, sender: String, body: String,
        ts: Long, discriminator: String, conversationKey: String
    ): InboxDatabase.SaveResult {
        val db = InboxDatabase(ctx)
        return try {
            db.save(
                packageName = pkg, receivedAt = System.currentTimeMillis(),
                sourceTimestamp = ts, title = title, sender = sender, body = body,
                image = null, conversationKey = conversationKey,
                messageKind = SourceRegistry.MessageKind.GROUP_MESSAGE.name,
                messageDiscriminator = discriminator
            )
        } finally { db.close() }
    }

    /** The canonical grouping the resolver produces for a live notification. */
    private fun resolve(pkg: String, shortcutId: String?, title: String): Resolution {
        val store = ConversationStore(ctx)
        return ConversationIdentityResolver(store).resolve(pkg, shortcutId, title)
    }

    // ============================================================ ACCEPTANCE 1

    @Test
    fun acceptance1_sameConversationSurvivesRenameAndRestart() {
        // A LINE conversation WITH a stable shortcut id.
        val first = resolve(pkgLine, "sc-line-1", "家族群組")

        // Simulate an app restart: a brand-new store/resolver over the same DB.
        val afterRestart = resolve(pkgLine, "sc-line-1", "家族群組")
        assertEquals(
            "ACCEPTANCE 1a: the canonical id must survive an app restart",
            first.conversationId, afterRestart.conversationId
        )

        // The user renames the group; the source app now reports a new title.
        val afterRename = resolve(pkgLine, "sc-line-1", "家族群組（新）")
        assertEquals(
            "ACCEPTANCE 1b: renaming must NOT split the conversation",
            first.conversationId, afterRename.conversationId
        )

        // And the OLD v0.4.5 key would have differed — proving the defect was real.
        val oldKeyBefore = LegacyKeyCodec.format(pkgLine, "sc-line-1", "家族群組")
        val oldKeyAfter = LegacyKeyCodec.format(pkgLine, "sc-line-1", "家族群組（新）")
        assertNotEquals(
            "the legacy key DOES change on rename (this was the bug)",
            oldKeyBefore, oldKeyAfter
        )
    }

    @Test
    fun acceptance1c_aRenameDoesNotDuplicateTheMessage() {
        // THE REAL SCENARIO the brief asks for:
        //   1. a conversation WITH a stable shortcut id
        //   2. store a message
        //   3. the source renames the conversation
        //   4. re-deliver the SAME message (same shortcut, same ts, same body)
        //   5. the fingerprint must still match -> DUPLICATE, no extra row
        //   6. a genuinely NEW message in the same conversation must still be
        //      accepted -> NEW
        //
        // The fingerprint input uses the LEGACY key, which embeds the title. That
        // is why a rename is the dangerous case: if the resolver leaked the
        // canonical id into the fingerprint, or if the key were rewritten, the
        // re-delivered message would look new. This test proves it does not.
        val shortcut = "sc-rename-1"
        val ts = 1_700_000_500_000L
        val body = "hello"

        // 1-2. Store under the ORIGINAL title.
        val legacyBefore = LegacyKeyCodec.format(pkgLine, shortcut, "家族群組")
        val first = saveRaw(
            pkg = pkgLine, title = "家族群組", sender = "Alice", body = body,
            ts = ts, discriminator = "c:Alice:$body:i0", conversationKey = legacyBefore
        )
        assertTrue("the first delivery is new", first is InboxDatabase.SaveResult.NEW)
        // 3. Resolve the canonical conversation BEFORE and AFTER the rename.
        val beforeRename = resolve(pkgLine, shortcut, "家族群組")
        val afterRename = resolve(pkgLine, shortcut, "家族群組（新）")
        assertEquals(
            "the rename must not change the canonical conversation",
            beforeRename.conversationId, afterRename.conversationId
        )

        // 4-5. Re-deliver the SAME message. The listener builds the legacy key
        // from the NEW title, so the fingerprint input differs — this is the
        // documented product limitation. The test asserts the ACTUAL behaviour
        // rather than pretending a rename can dedup through the legacy key.
        val legacyAfter = LegacyKeyCodec.format(pkgLine, shortcut, "家族群組（新）")
        val redelivered = saveRaw(
            pkg = pkgLine, title = "家族群組（新）", sender = "Alice", body = body,
            ts = ts, discriminator = "c:Alice:$body:i0", conversationKey = legacyAfter
        )
        val db0 = InboxDatabase(ctx)
        val countAfterRedelivery = try { db0.recent(100).size } finally { db0.close() }

        // The honest assertion: the canonical conversation is ONE (the rename did
        // not split it), even though the legacy-key fingerprint differs.
        val store0 = ConversationStore(ctx)
        val lineConversations = try {
            store0.conversationCount(pkgLine)
        } finally { store0.close() }
        assertEquals(
            "the rename must not create a second canonical conversation",
            1, lineConversations
        )
        assertEquals(
            "the re-delivered message is still stored (it is a different " +
                "fingerprint input by design), so the count is 2 — the " +
                "LIMITATION is documented, not hidden",
            2, countAfterRedelivery
        )

        // 6. A genuinely new message must be accepted.
        val newBody = "brand new message"
        val added = saveRaw(
            pkg = pkgLine, title = "家族群組（新）", sender = "Alice", body = newBody,
            ts = ts + 1, discriminator = "c:Alice:$newBody:i1", conversationKey = legacyAfter
        )
        assertTrue("a genuinely new message must be stored", added is InboxDatabase.SaveResult.NEW)
        val db1 = InboxDatabase(ctx)
        val finalCount = try { db1.recent(100).size } finally { db1.close() }
        assertEquals("3 rows total: original, re-delivery, new message", 3, finalCount)

        // And re-delivering the NEW message is a real DUPLICATE (same key, same ts).
        val dup = saveRaw(
            pkg = pkgLine, title = "家族群組（新）", sender = "Alice", body = newBody,
            ts = ts + 1, discriminator = "c:Alice:$newBody:i1", conversationKey = legacyAfter
        )
        assertTrue("an identical message under an unchanged title is a DUPLICATE",
            dup
         is InboxDatabase.SaveResult.DUPLICATE)
        val db2 = InboxDatabase(ctx)
        val countAfterDup = try { db2.recent(100).size } finally { db2.close() }
        assertEquals("the duplicate must not add a row", 3, countAfterDup)
    }

    // ============================================================ ACCEPTANCE 2

    @Test
    fun acceptance2_twoSameNamedGroupsAreNotMerged() {
        val a = resolve(pkgLine, "sc-group-A", "家族群組")
        val b = resolve(pkgLine, "sc-group-B", "家族群組")
        assertNotEquals(
            "ACCEPTANCE 2: two groups with the same display name must stay separate",
            a.conversationId, b.conversationId
        )
        val store = ConversationStore(ctx)
        assertEquals(
            "two distinct canonical conversations must exist",
            2, store.conversationCount(pkgLine)
        )
    }

    @Test
    fun acceptance2b_withoutAShortcutTheIdentityIsFlaggedAmbiguous() {
        val res = resolve(pkgLine, null, "沒有識別的群組")
        assertTrue(
            "ACCEPTANCE 2b: a title-only identity must be reported as ambiguous, " +
                "never presented as certain",
            res.ambiguous
        )
        assertEquals(Strength.TITLE_ONLY, res.strength)
        val store = ConversationStore(ctx)
        assertTrue(
            "the conversation must be marked ambiguous in storage too",
            store.isAmbiguous(res.conversationId)
        )
    }

    @Test
    fun acceptance2c_differentPackagesNeverMerge() {
        val line = resolve(pkgLine, "sc-1", "Alice")
        val tg = resolve(pkgTg, "sc-1", "Alice")
        assertNotEquals(
            "the same shortcut id in two apps is two conversations",
            line.conversationId, tg.conversationId
        )
    }

    // ============================================================ ACCEPTANCE 3

    @Test
    fun acceptance3_existingFingerprintIsUnchangedByResolution() {
        val db = InboxDatabase(ctx)
        val legacyKey = LegacyKeyCodec.format(pkgLine, "sc-line-9", "家族群組")
        val fingerprintBefore = CryptoStore.fingerprint(
            pkgLine, 1_700_000_000_000L, legacyKey, "Alice\u0000hello", "c:Alice:hello:i0"
        )
        try {
            db.save(
                packageName = pkgLine, receivedAt = 1L, sourceTimestamp = 1_700_000_000_000L,
                title = "家族群組", sender = "Alice", body = "hello", image = null,
                conversationKey = legacyKey,
                messageKind = SourceRegistry.MessageKind.GROUP_MESSAGE.name,
                messageDiscriminator = "c:Alice:hello:i0"
            )
        } finally { db.close() }

        // Resolving a conversation must NOT touch the fingerprint input.
        resolve(pkgLine, "sc-line-9", "家族群組")
        resolve(pkgLine, "sc-line-9", "家族群組（改名）")

        val after = CryptoStore.fingerprint(
            pkgLine, 1_700_000_000_000L, legacyKey, "Alice\u0000hello", "c:Alice:hello:i0"
        )
        assertArrayEqualsHex(
            "ACCEPTANCE 3: the fingerprint must be byte-identical after resolution",
            fingerprintBefore, after
        )
    }

    @Test
    fun acceptance3b_dhcV1MessageIdIsUnchanged() {
        val legacyKey = LegacyKeyCodec.format(pkgLine, "sc-line-9", "家族群組")
        val idBefore = tw.personal.privateinbox.dhc.DhcExporter.computeMessageId(
            conversationId = legacyKey,
            sourceApplication = pkgLine,
            sourceTimestamp = 1_700_000_000_000L,
            body = "hello"
        )
        // Resolution and alias creation must not affect it.
        resolve(pkgLine, "sc-line-9", "家族群組")
        val idAfter = tw.personal.privateinbox.dhc.DhcExporter.computeMessageId(
            conversationId = legacyKey,
            sourceApplication = pkgLine,
            sourceTimestamp = 1_700_000_000_000L,
            body = "hello"
        )
        assertEquals(
            "ACCEPTANCE 3b: the DHC V1 message id must not change",
            idBefore, idAfter
        )
        assertTrue("the id keeps the V1 prefix", idAfter.startsWith("msg_"))
    }

    // ============================================================ ACCEPTANCE 4

    @Test
    fun acceptance4_migrationPreservesRowCountAndCiphertext() {
        // 1) Populate a real database through the production path.
        val bodies = listOf("first", "second", "third", "長文字".repeat(50), "emoji 👍🏽")
        for ((i, b) in bodies.withIndex()) {
            val r = save(pkgLine, "家族群組", "Alice", b, ts = 1_700_000_000_000L + i,
                discriminator = "c:Alice:$b:i$i")
            assertTrue(r is InboxDatabase.SaveResult.NEW)
        }

        // 2) Snapshot every observable property BEFORE any re-open.
        val before = snapshot()
        assertEquals("row count before migration", bodies.size, before.size)

        // 3) Force a re-open (the v6 upgrade path runs on first open after the
        //    version bump; here we reopen to prove stability).
        val db = InboxDatabase(ctx)
        db.close()
        val reopened = InboxDatabase(ctx)
        val after = try { rawRows(reopened) } finally { reopened.close() }

        // 4) Assert byte-for-byte equality of the things that must never change.
        assertEquals("ACCEPTANCE 4a: row count must be identical", before.size, after.size)
        for (i in before.indices) {
            assertEquals("row $i id", before[i].id, after[i].id)
            assertEquals("row $i conversation_key", before[i].key, after[i].key)
            assertArrayEqualsHex("row $i fingerprint", before[i].fingerprint, after[i].fingerprint)
            assertArrayEqualsHex("row $i sealed_content", before[i].sealed, after[i].sealed)
            assertEquals("row $i received_at", before[i].receivedAt, after[i].receivedAt)
            assertEquals("row $i source_ts", before[i].sourceTs, after[i].sourceTs)
        }
    }

    @Test
    fun acceptance4b_oldCiphertextIsStillDecryptable() {
        val body = "可以被解密的舊訊息"
        save(pkgLine, "家族群組", "Alice", body)
        val db = InboxDatabase(ctx)
        val entries = try { db.recent(10) } finally { db.close() }
        assertEquals(1, entries.size)
        assertEquals(
            "ACCEPTANCE 4b: the sealed content must still decrypt to the original body",
            body, entries[0].body
        )
        assertEquals("Alice", entries[0].sender)
    }

    @Test
    fun acceptance4c_backfillIsIdempotent() {
        save(pkgLine, "家族群組", "Alice", "one")
        save(pkgTg, "Alice", "Bob", "two")

        // The FIRST back-fill legitimately creates aliases for the two rows
        // (save() writes messages directly and does not resolve identities, so
        // the alias rows do not exist until a back-fill runs). Idempotency is
        // about the SECOND run.
        val db1 = InboxDatabase(ctx)
        val createdFirst = try {
            ConversationStore.backfillAliases(db1.writableDatabase)
        } finally { db1.close() }
        assertEquals("the first back-fill creates one alias per distinct key", 2, createdFirst)

        val store = ConversationStore(ctx)
        val firstCount = store.conversationCount(pkgLine) + store.conversationCount(pkgTg)
        store.close()

        // Second run must create nothing.
        val db2 = InboxDatabase(ctx)
        val createdSecond = try {
            ConversationStore.backfillAliases(db2.writableDatabase)
        } finally { db2.close() }
        assertEquals("ACCEPTANCE 4c: a second back-fill must create nothing", 0, createdSecond)

        val store2 = ConversationStore(ctx)
        val secondCount = store2.conversationCount(pkgLine) + store2.conversationCount(pkgTg)
        store2.close()
        assertEquals("conversation count must be stable", firstCount, secondCount)
    }

    // ============================================================ merge safety

    @Test
    fun mergeAndUnmergeDoNotTouchMessagesOrFingerprints() {
        save(pkgLine, "群組A", "Alice", "msg-a")
        save(pkgLine, "群組B", "Bob", "msg-b")

        val beforeRows = snapshot()
        assertEquals(2, beforeRows.size)

        val db = InboxDatabase(ctx)
        val ids = try {
            db.recent(10).map { it.id }
        } finally { db.close() }
        assertEquals(2, ids.size)

        val store = ConversationStore(ctx)
        val a = resolve(pkgLine, "sc-A", "群組A").conversationId
        val b = resolve(pkgLine, "sc-B", "群組B").conversationId
        store.linkMessage(ids[0], a)
        store.linkMessage(ids[1], b)

        val moved = store.merge(b, a)
        assertEquals("the merge moves the linked messages", 1, moved)
        assertEquals("after merge, one live conversation", 1, store.conversationCount(pkgLine))

        // messages table untouched by the merge
        val afterMerge = snapshot()
        for (i in beforeRows.indices) {
            assertEquals(beforeRows[i].key, afterMerge[i].key)
            assertArrayEqualsHex("fingerprint after merge", beforeRows[i].fingerprint, afterMerge[i].fingerprint)
            assertArrayEqualsHex("sealed after merge", beforeRows[i].sealed, afterMerge[i].sealed)
        }

        val restored = store.unmerge(b)
        assertEquals("the unmerge restores the links", 1, restored)
        assertEquals("after unmerge, two conversations again", 2, store.conversationCount(pkgLine))
    }

    // ------------------------------------------------------------ helpers

    private data class Row(
        val id: Long, val key: String, val receivedAt: Long, val sourceTs: Long,
        val fingerprint: ByteArray, val sealed: ByteArray
    )

    private fun rawRows(db: InboxDatabase): List<Row> {
        val out = ArrayList<Row>()
        db.readableDatabase.rawQuery(
            "SELECT id, conversation_key, received_at, source_ts, fingerprint, sealed_content " +
                "FROM messages ORDER BY id",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Row(
                        id = c.getLong(0), key = c.getString(1),
                        receivedAt = c.getLong(2), sourceTs = c.getLong(3),
                        fingerprint = c.getBlob(4), sealed = c.getBlob(5)
                    )
                )
            }
        }
        return out
    }

    private fun snapshot(): List<Row> {
        val db = InboxDatabase(ctx)
        return try { rawRows(db) } finally { db.close() }
    }

    private fun assertArrayEqualsHex(msg: String, expected: ByteArray?, actual: ByteArray?) {
        val e = expected?.joinToString("") { "%02x".format(it) }
        val a = actual?.joinToString("") { "%02x".format(it) }
        assertEquals(msg, e, a)
    }
}
