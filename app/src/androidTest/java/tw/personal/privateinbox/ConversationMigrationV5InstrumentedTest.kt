package tw.personal.privateinbox

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A REAL v5 → v6 upgrade, not a re-open of a v6 database.
 *
 * The previous `acceptance4_migrationPreservesRowCountAndCiphertext` created the
 * database with the CURRENT helper, so it ran `onCreate` (v6) and then reopened —
 * `onUpgrade` never executed. It could not have detected a broken migration.
 *
 * This test builds a genuine v5 database by hand:
 *   - the exact v5 schema (no conversation tables),
 *   - `PRAGMA user_version = 5`,
 *   - synthetic rows with fingerprints and sealed blobs,
 * then opens it with the v6 helper so `onUpgrade(5, 6)` actually runs.
 *
 * ## What is deliberately NOT claimed
 *
 * The sealed blobs are synthetic bytes, not real AES-GCM output, because the
 * Keystore key that produced them lives on a device and cannot be moved into a
 * fixture. This test therefore proves the BLOBS ARE PRESERVED BYTE FOR BYTE —
 * it does NOT claim "the fixture ciphertext decrypts". Decryption of real rows
 * is covered separately by `acceptance4b`, which writes through the production
 * path on the device itself.
 */
@RunWith(AndroidJUnit4::class)
class ConversationMigrationV5InstrumentedTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val dbName = "inbox.db"

    @Before
    fun setUp() {
        DestructiveTestAssumption.requireIsolated(ctx)
        deleteDb()
    }

    @After
    fun tearDown() {
        runCatching { deleteDb() }
    }

    private fun deleteDb() {
        val f = ctx.getDatabasePath(dbName)
        listOf(f, File(f.path + "-wal"), File(f.path + "-shm"), File(f.path + "-journal"))
            .forEach { runCatching { it.delete() } }
    }

    /** Build a genuine v5 database: the historical schema, user_version = 5. */
    private fun buildV5Fixture(rows: List<FixtureRow>): File {
        val f = ctx.getDatabasePath(dbName)
        f.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(f, null)
        try {
            // The exact v5 schema (v0.4.5): no conversation tables exist yet.
            db.execSQL(
                """CREATE TABLE messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    package_name TEXT NOT NULL,
                    received_at INTEGER NOT NULL,
                    source_ts INTEGER NOT NULL DEFAULT 0,
                    conversation_key TEXT NOT NULL DEFAULT '',
                    message_kind TEXT NOT NULL DEFAULT 'UNKNOWN',
                    fingerprint BLOB NOT NULL UNIQUE,
                    sealed_content BLOB NOT NULL,
                    sealed_image BLOB
                )""".trimIndent()
            )
            db.execSQL("CREATE INDEX received_idx ON messages(received_at DESC)")
            db.execSQL("CREATE INDEX conversation_idx ON messages(conversation_key)")
            db.execSQL(
                "CREATE INDEX package_received_idx " +
                    "ON messages(package_name, received_at DESC, id DESC)"
            )
            db.execSQL(
                "CREATE INDEX conversation_received_idx " +
                    "ON messages(conversation_key, received_at DESC, id DESC)"
            )
            db.execSQL(
                "CREATE INDEX kind_received_idx " +
                    "ON messages(message_kind, received_at DESC, id DESC)"
            )
            for (r in rows) {
                db.execSQL(
                    "INSERT INTO messages " +
                        "(package_name, received_at, source_ts, conversation_key, " +
                        " message_kind, fingerprint, sealed_content, sealed_image) " +
                        "VALUES (?,?,?,?,?,?,?,?)",
                    arrayOf(
                        r.pkg, r.receivedAt, r.sourceTs, r.conversationKey,
                        r.kind, r.fingerprint, r.sealed, r.sealedImage
                    )
                )
            }
            // This is the line that makes it a REAL v5 fixture.
            db.version = 5
        } finally {
            db.close()
        }
        return f
    }

    private data class FixtureRow(
        val pkg: String,
        val receivedAt: Long,
        val sourceTs: Long,
        val conversationKey: String,
        val kind: String,
        val fingerprint: ByteArray,
        val sealed: ByteArray,
        val sealedImage: ByteArray? = null
    )

    private fun fixtureRows(): List<FixtureRow> {
        val line = "jp.naver.line.android"
        val tg = "org.telegram.messenger"
        return listOf(
            // A LINE conversation WITH a shortcut in the middle slot, title A.
            FixtureRow(
                line, 1_700_000_000_001L, 1_700_000_000_000L,
                LegacyKeyCodec.format(line, "sc-line-1", "家族群組"),
                "GROUP_MESSAGE",
                byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
                "sealed-one".toByteArray(),
                // A NON-NULL image blob: proves the migration preserves it too.
                byteArrayOf(-1, -2, -3, 0, 127, -128)
            ),
            // The SAME shortcut after a rename: a different legacy key, but the
            // migration must map both to one canonical conversation.
            FixtureRow(
                line, 1_700_000_000_002L, 1_700_000_000_001L,
                LegacyKeyCodec.format(line, "sc-line-1", "家族群組（新）"),
                "GROUP_MESSAGE",
                byteArrayOf(11, 12, 13, 14),
                "sealed-two".toByteArray()
            ),
            // A title-only row: no shortcut, must stay ambiguous.
            FixtureRow(
                line, 1_700_000_000_003L, 0L,
                LegacyKeyCodec.format(line, null, "沒有識別"),
                "GROUP_MESSAGE",
                byteArrayOf(21, 22, 23),
                "sealed-three".toByteArray()
            ),
            // A different package: must never merge with LINE.
            FixtureRow(
                tg, 1_700_000_000_004L, 1_700_000_000_003L,
                LegacyKeyCodec.format(tg, "sc-line-1", "家族群組"),
                "PRIVATE_MESSAGE",
                byteArrayOf(31, 32),
                "sealed-four".toByteArray()
            )
        )
    }

    /** Snapshot rows straight from SQLite, without the app's model layer. */
    /**
     * A full-column snapshot of `messages`, read straight from SQLite.
     *
     * Every column that exists in the historical schema is captured, including
     * the nullable `sealed_image`, so a migration that silently drops or
     * rewrites ANY field is detected. NULL and non-NULL blobs are distinguished
     * (a null blob stays null; a non-null blob is compared byte for byte).
     */
    private data class FullRow(
        val id: Long,
        val packageName: String,
        val receivedAt: Long,
        val sourceTs: Long,
        val conversationKey: String,
        val messageKind: String,
        val fingerprintHex: String,
        val sealedContentHex: String,
        val sealedImageHex: String   // "NULL" when the column is null
    )

    private fun rawSnapshot(): List<FullRow> {
        val f = ctx.getDatabasePath(dbName)
        val db = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
        val out = ArrayList<FullRow>()
        try {
            db.rawQuery(
                "SELECT id, package_name, received_at, source_ts, conversation_key, " +
                    "message_kind, fingerprint, sealed_content, sealed_image " +
                    "FROM messages ORDER BY id",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        FullRow(
                            id = c.getLong(0),
                            packageName = c.getString(1),
                            receivedAt = c.getLong(2),
                            sourceTs = c.getLong(3),
                            conversationKey = c.getString(4),
                            messageKind = c.getString(5),
                            fingerprintHex = hex(c.getBlob(6)),
                            sealedContentHex = hex(c.getBlob(7)),
                            sealedImageHex = if (c.isNull(8)) "NULL" else hex(c.getBlob(8))
                        )
                    )
                }
            }
        } finally { db.close() }
        return out
    }

    private fun hex(b: ByteArray?): String =
        if (b == null) "NULL" else b.joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------ tests

    @Test
    fun realV5ToV6_upgradePreservesEveryHistoricalField() {
        val rows = fixtureRows()
        buildV5Fixture(rows)

        // Prove the fixture really is v5 before opening it with v6 code.
        val pre = SQLiteDatabase.openDatabase(
            ctx.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY
        )
        try {
            assertEquals("fixture must be version 5", 5, pre.version)
            pre.rawQuery(
                "SELECT count(*) FROM sqlite_master WHERE type='table' " +
                    "AND name IN ('conversations','conversation_aliases','message_conversation')",
                null
            ).use { c ->
                c.moveToFirst()
                assertEquals("a v5 fixture has no conversation tables", 0, c.getInt(0))
            }
        } finally { pre.close() }

        val beforeRows = rawSnapshot()
        assertEquals(4, beforeRows.size)

        // Opening with the v6 helper runs onUpgrade(5, 6) for real.
        val db = InboxDatabase(ctx)
        db.writableDatabase   // force the open
        db.close()

        // 1. The version moved to 6.
        val post = SQLiteDatabase.openDatabase(
            ctx.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY
        )
        try {
            assertEquals("after upgrade the database is version 6", 6, post.version)

            // 2. The three new tables exist.
            val names = HashSet<String>()
            post.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                while (c.moveToNext()) names.add(c.getString(0))
            }
            assertTrue("conversations table created", names.contains("conversations"))
            assertTrue("aliases table created", names.contains("conversation_aliases"))
            assertTrue("message links table created", names.contains("message_conversation"))
        } finally { post.close() }

        // 3. Every historical row is byte-identical.
        val afterRows = rawSnapshot()
        assertEquals("row count unchanged by the upgrade", beforeRows.size, afterRows.size)
        for (i in beforeRows.indices) {
            val b = beforeRows[i]
            val a = afterRows[i]
            assertEquals("row $i id", b.id, a.id)
            assertEquals("row $i package_name", b.packageName, a.packageName)
            assertEquals("row $i received_at", b.receivedAt, a.receivedAt)
            assertEquals("row $i source_ts", b.sourceTs, a.sourceTs)
            assertEquals("row $i conversation_key", b.conversationKey, a.conversationKey)
            assertEquals("row $i message_kind", b.messageKind, a.messageKind)
            assertEquals("row $i fingerprint", b.fingerprintHex, a.fingerprintHex)
            assertEquals("row $i sealed_content", b.sealedContentHex, a.sealedContentHex)
            assertEquals("row $i sealed_image", b.sealedImageHex, a.sealedImageHex)
        }
        // Prove the fixture actually exercised both NULL and non-NULL images.
        assertTrue("at least one row must carry a non-null sealed_image",
            beforeRows.any { it.sealedImageHex != "NULL" })
        assertTrue("at least one row must carry a null sealed_image",
            beforeRows.any { it.sealedImageHex == "NULL" })
    }

    @Test
    fun realV5ToV6_aliasesAreBackfilledFromTheRealKeys() {
        buildV5Fixture(fixtureRows())

        val db = InboxDatabase(ctx)
        db.writableDatabase
        db.close()

        val store = ConversationStore(ctx)
        try {
            // Two LINE rows share the shortcut sc-line-1 under two titles: the
            // back-fill must map BOTH to ONE canonical conversation (the rename
            // fix), and the title-only row must be its own.
            val alias = store.find(
                "jp.naver.line.android", AliasType.SHORTCUT, "sc-line-1"
            )
            assertNotNull("a SHORTCUT alias must be back-filled from the legacy key", alias)

            val cid = alias!!
            val aliases = store.aliasesOf(cid)
            val legacyAliases = aliases.filter { it.first == AliasType.LEGACY_KEY.name }
            assertEquals(
                "both renamed legacy keys must point at the same canonical conversation",
                2, legacyAliases.size
            )

            // The two LINE conversations are: the shortcut one, and the
            // title-only one. Not three, and not one.
            assertEquals(
                "LINE must have exactly 2 canonical conversations",
                2, store.conversationCount("jp.naver.line.android")
            )
            // Telegram is separate.
            assertEquals(
                "Telegram must not be merged into LINE",
                1, store.conversationCount("org.telegram.messenger")
            )
        } finally { store.close() }
    }

    @Test
    fun realV5ToV6_titleOnlyRowIsAmbiguous() {
        buildV5Fixture(fixtureRows())
        val db = InboxDatabase(ctx)
        db.writableDatabase
        db.close()

        val store = ConversationStore(ctx)
        try {
            val weakKey = LegacyKeyCodec.format(
                "jp.naver.line.android", null, "沒有識別"
            )
            val cid = store.find("jp.naver.line.android", AliasType.LEGACY_KEY, weakKey)
            assertNotNull("the title-only key must still be aliased", cid)
            assertTrue(
                "a title-only conversation must be reported ambiguous, not certain",
                store.isAmbiguous(cid!!)
            )
        } finally { store.close() }
    }

    @Test
    fun realV5ToV6_reopeningDoesNotDuplicateTheBackfill() {
        buildV5Fixture(fixtureRows())

        val db1 = InboxDatabase(ctx)
        db1.writableDatabase
        db1.close()

        val store1 = ConversationStore(ctx)
        val firstCount = store1.conversationCount("jp.naver.line.android") +
            store1.conversationCount("org.telegram.messenger")
        val firstAliases = store1.aliasesOf(
            store1.find("jp.naver.line.android", AliasType.SHORTCUT, "sc-line-1")!!
        ).size
        store1.close()

        // Reopen twice more; onUpgrade must not re-run, and even a manual
        // back-fill must be a no-op.
        repeat(2) {
            val d = InboxDatabase(ctx)
            d.writableDatabase
            d.close()
        }

        val store2 = ConversationStore(ctx)
        try {
            val secondCount = store2.conversationCount("jp.naver.line.android") +
                store2.conversationCount("org.telegram.messenger")
            assertEquals("conversation count must not grow on reopen", firstCount, secondCount)
            val secondAliases = store2.aliasesOf(
                store2.find("jp.naver.line.android", AliasType.SHORTCUT, "sc-line-1")!!
            ).size
            assertEquals("alias rows must not be duplicated", firstAliases, secondAliases)
        } finally { store2.close() }
    }

    @Test
    fun realV5ToV6_interruptedMigrationRecoversWithoutDuplicates() {
        buildV5Fixture(fixtureRows())

        // Simulate an interruption AFTER the DDL but BEFORE the back-fill
        // committed: create the tables by hand and leave them empty, with the
        // version still 5 so the upgrade is attempted again.
        val raw = SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath(dbName), null)
        try {
            ConversationStore.createSchema(raw)   // tables exist, no aliases
            raw.version = 5                       // force onUpgrade to run again
        } finally { raw.close() }

        // Now open with v6: onUpgrade(5,6) runs on a database whose tables
        // already exist. Every DDL is IF NOT EXISTS, so this must not throw.
        val db = InboxDatabase(ctx)
        db.writableDatabase
        db.close()

        val store = ConversationStore(ctx)
        try {
            val alias = store.find("jp.naver.line.android", AliasType.SHORTCUT, "sc-line-1")
            assertNotNull("the interrupted migration must still back-fill on retry", alias)
            assertEquals(
                "no duplicate conversations after a retried migration",
                2, store.conversationCount("jp.naver.line.android")
            )
        } finally { store.close() }
    }
    // ---------------------------------------------------------------- G0.3

    /**
     * A CONTROLLED exception thrown from inside the migration transaction.
     *
     * `SQLiteOpenHelper.onUpgrade` runs inside a transaction, so a throw must
     * roll the whole upgrade back: no partial schema, no `user_version = 6`, no
     * half-written aliases. The existing "tables already exist" test covers
     * idempotency of a re-run; it does NOT prove transactional rollback, which
     * is what this test adds.
     */
    @Test
    fun realV5ToV6_throwingInsideTheMigrationRollsEverythingBack() {
        buildV5Fixture(fixtureRows())
        val beforeRows = rawSnapshot()

        // Open with a helper whose upgrade step throws after creating the tables.
        val failing = object : SQLiteOpenHelper(
            ctx, dbName, null, 6
        ) {
            override fun onCreate(db: SQLiteDatabase) {
                throw AssertionError("onCreate must not run for an existing v5 database")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                // Do the real DDL first, then fail — this is the dangerous
                // half-done state a crash would produce.
                ConversationStore.createSchema(db)
                throw IllegalStateException("injected migration failure")
            }
        }

        var threw = false
        try {
            failing.writableDatabase
        } catch (e: IllegalStateException) {
            threw = true
        } finally {
            failing.close()
        }
        assertTrue("the injected failure must propagate", threw)

        // 1. The version must still be 5: the transaction rolled back.
        val check = SQLiteDatabase.openDatabase(
            ctx.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY
        )
        try {
            assertEquals(
                "a failed onUpgrade must leave user_version at 5 (rolled back)",
                5, check.version
            )
            // 2. The half-created tables must be gone.
            val names = HashSet<String>()
            check.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                while (c.moveToNext()) names.add(c.getString(0))
            }
            assertFalse(
                "the rolled-back migration must not leave conversation tables behind",
                names.contains("conversations") || names.contains("conversation_aliases")
            )
        } finally { check.close() }

        // 3. Every row is untouched.
        val afterFailure = rawSnapshot()
        assertEquals("row count after a rolled-back migration", beforeRows.size, afterFailure.size)
        for (i in beforeRows.indices) {
            assertEquals("row $i id", beforeRows[i].id, afterFailure[i].id)
            assertEquals("row $i fingerprint",
                beforeRows[i].fingerprintHex, afterFailure[i].fingerprintHex)
            assertEquals("row $i sealed_content",
                beforeRows[i].sealedContentHex, afterFailure[i].sealedContentHex)
            assertEquals("row $i sealed_image",
                beforeRows[i].sealedImageHex, afterFailure[i].sealedImageHex)
        }

        // 4. A normal open now completes the migration, and the back-fill is not
        //    duplicated by the earlier failed attempt.
        val ok = InboxDatabase(ctx)
        ok.writableDatabase
        ok.close()

        val store = ConversationStore(ctx)
        try {
            assertNotNull(
                "the retried migration back-fills the alias",
                store.find("jp.naver.line.android", AliasType.SHORTCUT, "sc-line-1")
            )
            assertEquals(
                "no duplicate conversations from the failed-then-retried migration",
                2, store.conversationCount("jp.naver.line.android")
            )
        } finally { store.close() }

        val finalRows = rawSnapshot()
        assertEquals("row count unchanged by the whole episode",
            beforeRows.size, finalRows.size)
        for (i in beforeRows.indices) {
            assertEquals("row $i fingerprint after recovery",
                beforeRows[i].fingerprintHex, finalRows[i].fingerprintHex)
        }
    }

    // ---------------------------------------------------------------- G0.4

    /**
     * Same-device proof that a stored fingerprint BLOB is untouched by migration.
     *
     * The HMAC key lives in this device's Keystore, so cross-device comparison is
     * meaningless. What IS meaningful: compute a real fingerprint on THIS device,
     * write it into a v5 fixture, migrate, and compare the stored bytes. That is
     * exactly what this does — no fixture-side HMAC key is ever hard-coded.
     */
    @Test
    fun realV5ToV6_storedFingerprintBlobIsUnchangedByMigration() {
        // Compute a REAL fingerprint on this device (Keystore-backed).
        val pkg = "jp.naver.line.android"
        val legacyKey = LegacyKeyCodec.format(pkg, "sc-line-1", "家族群組")
        val realFingerprint = CryptoStore.fingerprint(
            pkg, 1_700_000_000_000L, legacyKey, "Alice\u0000hello", "c:Alice:hello:i0"
        )

        // Put it into a genuine v5 fixture.
        buildV5Fixture(
            listOf(
                FixtureRow(
                    pkg, 1_700_000_000_001L, 1_700_000_000_000L, legacyKey,
                    "GROUP_MESSAGE", realFingerprint, "sealed-real-fp".toByteArray(),
                    byteArrayOf(9, 8, 7)
                )
            )
        )

        val before = rawSnapshot().single()
        assertEquals(
            "the fixture must contain the device-computed fingerprint",
            hex(realFingerprint), before.fingerprintHex
        )

        // Migrate.
        val db = InboxDatabase(ctx)
        db.writableDatabase
        db.close()

        val after = rawSnapshot().single()
        assertEquals(
            "the stored fingerprint BLOB must survive the migration byte for byte",
            before.fingerprintHex, after.fingerprintHex
        )
        assertEquals("sealed_content unchanged", before.sealedContentHex, after.sealedContentHex)
        assertEquals("sealed_image unchanged", before.sealedImageHex, after.sealedImageHex)

        // And the SAME message re-delivered still dedups against it.
        val db2 = InboxDatabase(ctx)
        val result = try {
            db2.save(
                packageName = pkg, receivedAt = 1_700_000_000_002L,
                sourceTimestamp = 1_700_000_000_000L, title = "家族群組",
                sender = "Alice", body = "hello", image = null,
                conversationKey = legacyKey,
                messageKind = SourceRegistry.MessageKind.GROUP_MESSAGE.name,
                messageDiscriminator = "c:Alice:hello:i0"
            )
        } finally { db2.close() }
        assertTrue("a message stored before the migration must be recognised as DUPLICATE after it",
            result
         is InboxDatabase.SaveResult.DUPLICATE)
    }

}
