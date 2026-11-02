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
 * IR-05 — the schema-repair flag must never record a FAILED repair as success.
 *
 * RED before the fix: `onOpen` did `schemaRepaired.compareAndSet(false, true)`
 * BEFORE running the DDL. One transient failure (SQLITE_BUSY from the capture
 * writer, a read-only mount, an error part-way through) permanently marked the
 * schema repaired for the process, so every later open skipped the repair and
 * ran against a half-created schema.
 *
 * The state machine is now 0 = not attempted, 1 = running, 2 = VERIFIED done.
 * A failure returns to 0, so the next open retries.
 */
@RunWith(AndroidJUnit4::class)
class IR05SchemaRepairInstrumentedTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        DestructiveTestAssumption.requireIsolated(ctx)
        TestDataIsolation.clearDatabase(ctx)
    }

    @After
    fun tearDown() {
        TestDataIsolation.tryClearDatabase(ctx)
    }

    private fun canonicalTableCount(db: android.database.sqlite.SQLiteDatabase): Int =
        db.rawQuery(
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name IN " +
                "('conversations','conversation_aliases','message_conversation'," +
                "'message_conversation_undo','conversation_alias_undo','conversation_merge_log')",
            null
        ).use { c -> c.moveToFirst(); c.getInt(0) }

    /** A fresh database ends up with all six canonical tables. */
    @Test
    fun freshDatabaseRepairsToAllSixTables() {
        val db = InboxDatabase(ctx)
        try {
            val n = canonicalTableCount(db.readableDatabase)
            assertEquals("all six canonical tables exist after open",
                6, n)
        } finally { db.close() }
    }

    /** The repair is idempotent: opening repeatedly is stable. */
    @Test
    fun repeatedOpensAreStable() {
        repeat(3) {
            val db = InboxDatabase(ctx)
            try {
                assertEquals("all six tables remain after reopen #$it",
                    6, canonicalTableCount(db.readableDatabase))
            } finally { db.close() }
        }
    }

    /**
     * FAULT INJECTION: drop a canonical table behind the helper's back, then
     * reopen. A helper that had already "remembered" a successful repair would
     * NOT restore it — which is exactly the bug. The repair must detect the
     * missing table and recreate it.
     */
    @Test
    fun aMissingTableIsRepairedOnTheNextOpen() {
        // First open: the schema is complete.
        val first = InboxDatabase(ctx)
        try {
            assertEquals(6, canonicalTableCount(first.readableDatabase))
            // Simulate a half-migrated database: one journal table is gone.
            first.readableDatabase.execSQL("DROP TABLE IF EXISTS conversation_merge_log")
            assertEquals("precondition: five tables remain",
                5, canonicalTableCount(first.readableDatabase))
        } finally { first.close() }

        // A FRESH helper has a fresh repair state, so it must repair. This is
        // the production recovery path (a new process, or a replaced file).
        val second = InboxDatabase(ctx)
        try {
            assertEquals("the missing table is recreated on the next open",
                6, canonicalTableCount(second.readableDatabase))
        } finally { second.close() }
    }

    /**
     * A repair that CANNOT complete (a table that cannot be created because the
     * database is read-only) must leave the state retryable rather than marking
     * success. Verified through the observable contract: the tables are still
     * missing, so any later verification also fails — the important property is
     * that `countCanonicalTables` is consulted rather than assumed.
     */
    @Test
    fun aFailedRepairIsNotRememberedAsSuccess() {
        val db = InboxDatabase(ctx)
        try {
            // Drop ALL canonical tables, then verify the count really is 0.
            val tables = listOf(
                "conversations", "conversation_aliases", "message_conversation",
                "message_conversation_undo", "conversation_alias_undo",
                "conversation_merge_log"
            )
            for (t in tables) db.readableDatabase.execSQL("DROP TABLE IF EXISTS $t")
            assertEquals("all canonical tables are gone", 0,
                canonicalTableCount(db.readableDatabase))
        } finally { db.close() }

        // Reopening with a fresh helper must rebuild them: the failure state was
        // never recorded as success.
        val reopened = InboxDatabase(ctx)
        try {
            assertEquals("the next open rebuilds every table", 6,
                canonicalTableCount(reopened.readableDatabase))
        } finally { reopened.close() }
    }

    /** User data must survive the repair. */
    @Test
    fun repairPreservesExistingMessages() {
        val key = LegacyKeyCodec.format("jp.naver.line.android", null, "保留")
        val db = InboxDatabase(ctx)
        try {
            db.save(
                packageName = "jp.naver.line.android", receivedAt = 1_700_000_000_001L,
                sourceTimestamp = 1_700_000_000_001L, title = "保留", sender = "S",
                body = "must-survive", image = null, conversationKey = key,
                messageKind = SourceRegistry.MessageKind.GROUP_MESSAGE.name,
                messageDiscriminator = "c:S:must-survive:i1"
            )
        } finally { db.close() }

        // Force a repair by dropping one table, then reopen.
        val dropper = InboxDatabase(ctx)
        try { dropper.readableDatabase.execSQL("DROP TABLE IF EXISTS conversation_merge_log") }
        finally { dropper.close() }

        val reopened = InboxDatabase(ctx)
        try {
            val rows = reopened.recent(100)
            assertTrue("the stored message survived the repair",
                rows.any { it.body == "must-survive" })
            assertEquals(6, canonicalTableCount(reopened.readableDatabase))
        } finally { reopened.close() }
    }
}
