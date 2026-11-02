package tw.personal.privateinbox

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * G1.5-A — an EXISTING v6 database must gain the merge-journal tables.
 *
 * ## The defect this suite was written to find
 *
 * G1 added three tables to `ConversationStore.createSchema()`:
 * `message_conversation_undo`, `conversation_alias_undo`, `conversation_merge_log`.
 *
 * `createSchema()` is called from `onCreate` and from `onUpgrade(oldVersion < 6)`.
 * A database that was created by the EARLIER v0.4.6 build already reports
 * `user_version = 6`, so:
 *
 *   - `onCreate` does not run (the file exists),
 *   - `onUpgrade` does not run (the version did not change),
 *   - and there is no `onOpen` hook.
 *
 * Result: the database is missing the journal tables, and the first
 * merge/unmerge throws `no such table`. Every fresh install would be fine, so
 * only an upgrade test catches it — which is exactly why this fixture is built by
 * hand instead of with the current `onCreate`.
 */
@RunWith(AndroidJUnit4::class)
class ExistingV6CompatibilityInstrumentedTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val dbName = "inbox.db"
    private val pkg = "jp.naver.line.android"

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

    /**
     * Build the PRE-G1 v6 layout by hand: the three canonical identity tables
     * exist, the three merge-journal tables do NOT, and `user_version = 6`.
     *
     * This is deliberately NOT produced with the current `createSchema()`, which
     * would create the journal tables too and hide the defect.
     */
    private fun buildPreG1V6Fixture() {
        val f = ctx.getDatabasePath(dbName)
        f.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(f, null)
        try {
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

            // The three canonical tables AS G1 FOUND THEM (no journal tables).
            db.execSQL(
                """CREATE TABLE conversations (
                    conversation_id TEXT PRIMARY KEY,
                    package_name    TEXT NOT NULL,
                    is_group        INTEGER NOT NULL DEFAULT 0,
                    display_title   TEXT NOT NULL DEFAULT '',
                    created_at      INTEGER NOT NULL,
                    merged_into     TEXT
                )""".trimIndent()
            )
            db.execSQL(
                "CREATE INDEX conversations_pkg_idx ON conversations(package_name)"
            )
            db.execSQL(
                """CREATE TABLE conversation_aliases (
                    package_name    TEXT NOT NULL,
                    alias_type      TEXT NOT NULL,
                    alias_value     TEXT NOT NULL,
                    conversation_id TEXT NOT NULL,
                    strength        TEXT NOT NULL,
                    created_at      INTEGER NOT NULL,
                    PRIMARY KEY (package_name, alias_type, alias_value)
                )""".trimIndent()
            )
            db.execSQL(
                "CREATE INDEX conversation_aliases_cid_idx " +
                    "ON conversation_aliases(conversation_id)"
            )
            db.execSQL(
                """CREATE TABLE message_conversation (
                    message_id      INTEGER PRIMARY KEY,
                    conversation_id TEXT NOT NULL
                )""".trimIndent()
            )
            db.execSQL(
                "CREATE INDEX message_conversation_cid_idx " +
                    "ON message_conversation(conversation_id)"
            )

            // Existing user data: one message + its canonical conversation.
            db.execSQL(
                "INSERT INTO messages (package_name, received_at, source_ts, " +
                    "conversation_key, message_kind, fingerprint, sealed_content) " +
                    "VALUES (?,?,?,?,?,?,?)",
                arrayOf(
                    pkg, 1_700_000_000_001L, 1_700_000_000_000L,
                    LegacyKeyCodec.format(pkg, "sc-A", "群組A"),
                    "GROUP_MESSAGE", byteArrayOf(1, 2, 3), "sealed-a".toByteArray()
                )
            )
            db.execSQL(
                "INSERT INTO conversations (conversation_id, package_name, " +
                    "is_group, display_title, created_at) VALUES (?,?,?,?,?)",
                arrayOf("cv_existing_a", pkg, 0, "群組A", 1_700_000_000_000L)
            )
            db.execSQL(
                "INSERT INTO conversation_aliases (package_name, alias_type, " +
                    "alias_value, conversation_id, strength, created_at) " +
                    "VALUES (?,?,?,?,?,?)",
                arrayOf(pkg, "SHORTCUT", "sc-A", "cv_existing_a", "STRONG",
                    1_700_000_000_000L)
            )
            db.execSQL(
                "INSERT INTO message_conversation (message_id, conversation_id) " +
                    "VALUES (?,?)",
                arrayOf(1L, "cv_existing_a")
            )

            db.version = 6
        } finally { db.close() }
    }

    private fun tableNames(): Set<String> {
        val db = SQLiteDatabase.openDatabase(
            ctx.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY
        )
        val out = HashSet<String>()
        try {
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                while (c.moveToNext()) out.add(c.getString(0))
            }
        } finally { db.close() }
        return out
    }

    private fun version(): Int {
        val db = SQLiteDatabase.openDatabase(
            ctx.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY
        )
        return try { db.version } finally { db.close() }
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun existingV6FixtureReallyLacksTheJournalTables() {
        buildPreG1V6Fixture()
        val tables = tableNames()
        assertEquals("fixture is v6", 6, version())
        assertTrue("canonical table present", tables.contains("conversations"))
        assertTrue("alias table present", tables.contains("conversation_aliases"))
        assertTrue("link table present", tables.contains("message_conversation"))
        assertTrue(
            "the fixture must NOT already have the journal tables, " +
                "otherwise it cannot reproduce the defect",
            !tables.contains("message_conversation_undo") &&
                !tables.contains("conversation_alias_undo") &&
                !tables.contains("conversation_merge_log")
        )
    }

    @Test
    fun openingAnExistingV6DatabaseAddsTheJournalTables() {
        buildPreG1V6Fixture()

        // Opening through the production helper must repair the schema.
        val db = InboxDatabase(ctx)
        db.writableDatabase
        db.close()

        val tables = tableNames()
        assertTrue(
            "opening an existing v6 DB must create message_conversation_undo",
            tables.contains("message_conversation_undo")
        )
        assertTrue(
            "opening an existing v6 DB must create conversation_alias_undo",
            tables.contains("conversation_alias_undo")
        )
        assertTrue(
            "opening an existing v6 DB must create conversation_merge_log",
            tables.contains("conversation_merge_log")
        )
    }

    @Test
    fun existingV6DataSurvivesTheSchemaRepair() {
        buildPreG1V6Fixture()
        val db = InboxDatabase(ctx)
        db.writableDatabase
        db.close()

        val raw = SQLiteDatabase.openDatabase(
            ctx.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY
        )
        try {
            raw.rawQuery("SELECT count(*) FROM messages", null).use { c ->
                c.moveToFirst()
                assertEquals("the existing message must survive", 1, c.getInt(0))
            }
            raw.rawQuery(
                "SELECT conversation_id FROM message_conversation WHERE message_id = 1", null
            ).use { c ->
                c.moveToFirst()
                assertEquals("the existing canonical link must survive",
                    "cv_existing_a", c.getString(0))
            }
            raw.rawQuery(
                "SELECT conversation_id FROM conversation_aliases " +
                    "WHERE package_name=? AND alias_type='SHORTCUT' AND alias_value='sc-A'",
                arrayOf(pkg)
            ).use { c ->
                c.moveToFirst()
                assertEquals("the existing alias owner must not change",
                    "cv_existing_a", c.getString(0))
            }
        } finally { raw.close() }
    }

    @Test
    fun mergeAndUnmergeWorkOnARepairedExistingV6Database() {
        buildPreG1V6Fixture()

        // Repair by opening, then exercise the journal on real data.
        val open = InboxDatabase(ctx)
        open.writableDatabase
        open.close()

        val store = ConversationStore(ctx)
        try {
            // The pre-existing conversation A and a brand new one B.
            val a = "cv_existing_a"
            val b = ConversationIdentityResolver(store)
                .resolve(pkg, "sc-B", "群組B").conversationId

            val moved = store.merge(a, b)
            assertTrue("merge on a repaired DB must succeed (>= 0), got $moved", moved >= 0)
            assertEquals("A is merged into B", b, store.mergedInto(a))

            val restored = store.unmerge(a)
            assertTrue("unmerge must succeed", restored >= 0)
            assertEquals("A stands alone again", null, store.mergedInto(a))
            assertEquals(
                "and A's alias resolves back to A",
                a, store.find(pkg, AliasType.SHORTCUT, "sc-A")
            )
        } finally { store.close() }
    }

    @Test
    fun reopeningDoesNotDuplicateTheRepair() {
        buildPreG1V6Fixture()

        val countsAfterFirst = mutableListOf<Int>()
        // Snapshot the alias count after the first open (which legitimately
        // back-fills the LEGACY_KEY alias for the fixture message), then assert
        // the three subsequent opens add NOTHING.
        fun aliasCount(): Int {
            val raw = SQLiteDatabase.openDatabase(
                ctx.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY
            )
            return try {
                raw.rawQuery("SELECT count(*) FROM conversation_aliases", null)
                    .use { c -> c.moveToFirst(); c.getInt(0) }
            } finally { raw.close() }
        }
        repeat(3) {
            val db = InboxDatabase(ctx)
            db.writableDatabase
            db.close()
            countsAfterFirst.add(aliasCount())
        }
        val raw = SQLiteDatabase.openDatabase(
            ctx.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY
        )
        try {
            raw.rawQuery("SELECT count(*) FROM messages", null).use { c ->
                c.moveToFirst(); assertEquals(1, c.getInt(0))
            }
            raw.rawQuery("SELECT count(*) FROM conversations", null).use { c ->
                c.moveToFirst()
                assertEquals("no duplicate conversations", 1, c.getInt(0))
            }
        } finally { raw.close() }
        assertEquals(
            "repeated opens must not add aliases (the first open's back-fill is " +
                "the only one)",
            countsAfterFirst.toSet().size, 1
        )
    }

    @Test
    fun theVersionIsNotSecretlyBumped() {
        buildPreG1V6Fixture()
        val db = InboxDatabase(ctx)
        db.writableDatabase
        db.close()
        // If the schema repair is done in onOpen (idempotent DDL), the version
        // stays at the helper's declared version; it must never be forged.
        assertEquals(
            "the database version must equal the helper's declared version",
            6, version()
        )
    }
}
