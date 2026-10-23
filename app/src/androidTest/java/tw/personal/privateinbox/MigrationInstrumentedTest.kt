package tw.personal.privateinbox

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P0-A: the migration must never add the same column twice.
 *
 * The v0.4.4-era onUpgrade() had `ALTER TABLE messages ADD COLUMN source_ts`
 * in BOTH the `oldVersion < 2` and the `oldVersion < 3` step. A database
 * created at v1 (before source_ts existed) then upgraded to 5 hit the
 * <2 step, got source_ts, hit the <3 step, and crashed with
 * "duplicate column name: source_ts" — killing the open path and, with it,
 * all capture until the app was reinstalled.
 *
 * These tests open REAL SQLite databases at each historical version and run
 * the migration. RED before the fix, GREEN after. No emulator-hostile
 * shortcuts: this runs the same SQLiteOpenHelper path as production.
 */
@RunWith(AndroidJUnit4::class)
class MigrationInstrumentedTest {

    /** The historical v1 schema: the columns the first release shipped with. */
    private fun createV1(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            package_name TEXT NOT NULL,
            received_at INTEGER NOT NULL,
            fingerprint BLOB NOT NULL UNIQUE,
            sealed_content BLOB NOT NULL
        )""".trimIndent())
        db.execSQL("CREATE INDEX received_idx ON messages(received_at DESC)")
    }

    /** The historical v2 schema: sealed_image was added, source_ts did not exist yet. */
    private fun createV2(db: SQLiteDatabase) {
        createV1(db)
        db.execSQL("ALTER TABLE messages ADD COLUMN sealed_image BLOB")
    }

    /** The historical v3 schema: source_ts existed exactly once. */
    private fun createV3(db: SQLiteDatabase) {
        createV2(db)
        db.execSQL("ALTER TABLE messages ADD COLUMN source_ts INTEGER NOT NULL DEFAULT 0")
    }

    /** The historical v4 schema: conversation identity columns existed. */
    private fun createV4(db: SQLiteDatabase) {
        createV3(db)
        db.execSQL("ALTER TABLE messages ADD COLUMN conversation_key TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE messages ADD COLUMN message_kind TEXT NOT NULL DEFAULT 'UNKNOWN'")
    }

    /** Seed one row so the migration must PRESERVE data, not just pass on an empty table. */
    private fun seedRow(db: SQLiteDatabase, pkg: String, body: ByteArray, fp: ByteArray) {
        db.execSQL(
            "INSERT INTO messages (package_name, received_at, fingerprint, sealed_content) " +
                "VALUES (?, ?, ?, ?)",
            arrayOf(pkg, System.currentTimeMillis().toString(), fp, body)
        )
    }

    private fun upgradedFrom(version: Int, seed: Boolean = true): Pair<Int, Int> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "mig-test-v$version-${System.nanoTime()}.db"
        val path = ctx.getDatabasePath(name)
        path.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(path, null)
        when (version) {
            1 -> createV1(raw)
            2 -> createV2(raw)
            3 -> createV3(raw)
            4 -> createV4(raw)
        }
        raw.version = version
        if (seed) {
            seedRow(
                raw, "tw.personal.privateinbox",
                "migration body $version".toByteArray(), fp(version)
            )
        }
        val rowsBefore = raw.rawQuery("SELECT COUNT(*) FROM messages", null)
            .use { it.moveToFirst(); it.getInt(0) }
        raw.close()

        // Run the PRODUCTION migration by setting the version and letting
        // onUpgrade drive. We invoke the helper's onUpgrade directly on the
        // raw handle so the test isolates the migration step.
        val helper = InboxDatabase(ctx)
        val db = SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE)
        helper.onUpgrade(db, version, 5)
        db.version = 5
        val columns = db.rawQuery("PRAGMA table_info(messages)", null).use { c ->
            val names = mutableListOf<String>()
            while (c.moveToNext()) names.add(c.getString(1))
            names
        }
        val rowsAfter = db.rawQuery("SELECT COUNT(*) FROM messages", null)
            .use { it.moveToFirst(); it.getInt(0) }
        db.close()
        helper.close()
        return rowsBefore to rowsAfter
    }

    private fun fp(seed: Int): ByteArray =
        ("fingerprint-$seed").toByteArray(Charsets.UTF_8).let { it.copyOf(32) }

    private fun columnNames(version: Int): Set<String> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "mig-cols-v$version-${System.nanoTime()}.db"
        val path = ctx.getDatabasePath(name)
        path.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(path, null)
        when (version) {
            1 -> createV1(raw)
            2 -> createV2(raw)
            3 -> createV3(raw)
            4 -> createV4(raw)
        }
        raw.version = version
        raw.close()
        val helper = InboxDatabase(ctx)
        val db = SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE)
        helper.onUpgrade(db, version, 5)
        val columns = db.rawQuery("PRAGMA table_info(messages)", null).use { c ->
            val names = mutableListOf<String>()
            while (c.moveToNext()) names.add(c.getString(1))
            names
        }
        db.close()
        helper.close()
        return columns.toSet()
    }

    @Test
    fun migrateFromV1_addsEachColumnExactlyOnce() {
        val cols = columnNames(1)
        assertEquals(1, cols.count { it == "source_ts" })
        assertEquals(1, cols.count { it == "sealed_image" })
        assertEquals(1, cols.count { it == "conversation_key" })
        assertEquals(1, cols.count { it == "message_kind" })
    }

    @Test
    fun migrateFromV2_addsSourceTsExactlyOnce() {
        val cols = columnNames(2)
        assertEquals(1, cols.count { it == "source_ts" })
        assertEquals(1, cols.count { it == "sealed_image" })
    }

    @Test
    fun migrateFromV3_keepsSingleSourceTs() {
        val cols = columnNames(3)
        assertEquals(1, cols.count { it == "source_ts" })
    }

    @Test
    fun migrateFromV4_keepsAllColumns() {
        val cols = columnNames(4)
        assertEquals(1, cols.count { it == "source_ts" })
        assertEquals(1, cols.count { it == "conversation_key" })
    }

    @Test
    fun migration_preservesRowCountFromEveryVersion() {
        for (v in 1..4) {
            val (before, after) = upgradedFrom(v)
            assertTrue("v$v: rows must survive migration", before == 1 && after == 1)
        }
    }

    @Test
    fun migration_v1RowBodySurvivesByteForByte() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "mig-body-${System.nanoTime()}.db"
        val path = ctx.getDatabasePath(name)
        path.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(path, null)
        createV1(raw)
        raw.version = 1
        val body = "cipher-bytes-保持-🔒".toByteArray(Charsets.UTF_8)
        seedRow(raw, "jp.naver.line.android", body, fp(99))
        raw.close()

        val helper = InboxDatabase(ctx)
        val db = SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE)
        helper.onUpgrade(db, 1, 5)
        val stored = db.rawQuery(
            "SELECT sealed_content FROM messages WHERE package_name='jp.naver.line.android'", null
        ).use { c ->
            c.moveToFirst()
            c.getBlob(0)
        }
        db.close()
        helper.close()
        assertTrue(body.contentEquals(stored))
    }
}
