package tw.personal.privateinbox

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
 * G2 — alias provenance: the same shortcut id means different things depending
 * on WHERE the evidence came from.
 *
 * A shortcut the PLATFORM just handed us in a notification is a fact
 * (PLATFORM_CONFIRMED). The same string parsed out of a stored legacy key during
 * a back-fill is an inference about a format (LEGACY_INFERRED). Collapsing those
 * two into one "STRONG" hid exactly the distinction the brief requires.
 *
 * Also asserted: the stored column keeps its historical values so nothing is
 * renamed in place, and `isAmbiguous` still reads both.
 */
@RunWith(AndroidJUnit4::class)
class AliasProvenanceInstrumentedTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val pkg = "jp.naver.line.android"
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

    /** A genuine v5 fixture so the back-fill path runs (inferred evidence). */
    private data class FixtureRowNoImage(
        val pkg: String, val receivedAt: Long, val sourceTs: Long,
        val conversationKey: String, val kind: String,
        val fingerprint: ByteArray, val sealed: ByteArray
    )

    private fun buildV5Fixture() {
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
            // Row 1: a legacy key that CARRIES a shortcut (inferred evidence).
            db.execSQL(
                "INSERT INTO messages (package_name, received_at, source_ts, " +
                    "conversation_key, message_kind, fingerprint, sealed_content) " +
                    "VALUES (?,?,?,?,?,?,?)",
                arrayOf(
                    pkg, 1_700_000_000_001L, 1_700_000_000_000L,
                    LegacyKeyCodec.format(pkg, "sc-backfilled", "家族群組"),
                    "GROUP_MESSAGE", byteArrayOf(1, 2, 3), "sealed".toByteArray()
                )
            )
            // Row 2: a TITLE-ONLY legacy key (no shortcut at all).
            db.execSQL(
                "INSERT INTO messages (package_name, received_at, source_ts, " +
                    "conversation_key, message_kind, fingerprint, sealed_content) " +
                    "VALUES (?,?,?,?,?,?,?)",
                arrayOf(
                    pkg, 1_700_000_000_002L, 0L,
                    LegacyKeyCodec.format(pkg, null, "沒有識別的群組"),
                    "GROUP_MESSAGE", byteArrayOf(4, 5, 6), "sealed2".toByteArray()
                )
            )
            db.version = 5
        } finally { db.close() }
    }

    private fun strengthRows(): List<Triple<String, String, String>> {
        val db = SQLiteDatabase.openDatabase(
            ctx.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY
        )
        val out = ArrayList<Triple<String, String, String>>()
        try {
            db.rawQuery(
                "SELECT alias_type, alias_value, strength FROM conversation_aliases",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(Triple(c.getString(0), c.getString(1), c.getString(2)))
                }
            }
        } finally { db.close() }
        return out
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun backfillRecordsParsedShortcutsAsLegacyInferred() {
        buildV5Fixture()
        val open = InboxDatabase(ctx)
        open.writableDatabase   // runs onUpgrade -> backfill
        open.close()

        val rows = strengthRows()
        val shortcut = rows.firstOrNull { it.first == "SHORTCUT" }
        assertNotNull("the back-fill registered a SHORTCUT alias", shortcut)
        assertEquals(
            "a shortcut parsed from a stored key is LEGACY_INFERRED, not " +
                "PLATFORM_CONFIRMED — the format alone does not prove the platform " +
                "ever asserted it",
            Strength.LEGACY_INFERRED.storedValue, shortcut!!.third
        )
        // The fixture's legacy key CARRIES a shortcut, so the back-fill treats
        // the whole row as inferred-from-a-real-shortcut: LEGACY_INFERRED, not
        // the weaker title-only form. The title-only form is asserted in the
        // next test via a key with no shortcut.
        val shortcutCarrying = rows.firstOrNull {
            it.first == "LEGACY_KEY" && it.second.contains("家族群組")
        }
        assertEquals(
            "the legacy-key alias of a shortcut-carrying key is LEGACY_INFERRED",
            Strength.LEGACY_INFERRED.storedValue, shortcutCarrying!!.third
        )
        val titleOnly = rows.firstOrNull {
            it.first == "LEGACY_KEY" && it.second.contains("沒有識別")
        }
        assertNotNull("the title-only key is back-filled too", titleOnly)
        assertEquals(
            "a title-only key is the weakest form",
            Strength.TITLE_ONLY.storedValue, titleOnly!!.third
        )

        val store = ConversationStore(ctx)
        try {
            val cid = store.find(pkg, AliasType.SHORTCUT, "sc-backfilled")!!
            assertTrue(
                "an inferred-only conversation must still report ambiguous",
                store.isAmbiguous(cid)
            )
        } finally { store.close() }
    }

    @Test
    fun aLivePlatformShortcutIsRecordedAsPlatformConfirmed() {
        buildV5Fixture()
        val open = InboxDatabase(ctx)
        open.writableDatabase
        open.close()

        // The listener resolves a LIVE notification whose shortcut the platform
        // supplied. That is PLATFORM_CONFIRMED evidence.
        val store = ConversationStore(ctx)
        val cid = try {
            ConversationIdentityResolver(store)
                .resolve(pkg, "sc-live", "群組B").conversationId
        } finally { store.close() }

        val live = strengthRows().firstOrNull {
            it.first == "SHORTCUT" && it.second == "sc-live"
        }
        assertNotNull("the live shortcut is registered", live)
        assertEquals(
            "a platform-supplied shortcut is PLATFORM_CONFIRMED",
            Strength.PLATFORM_CONFIRMED.storedValue, live!!.third
        )
        val legacy = strengthRows().firstOrNull {
            it.first == "LEGACY_KEY" && it.second.contains("群組B")
        }
        assertEquals(
            "the legacy key it rode on is confirmed too — the platform gave us " +
                "that notification",
            Strength.PLATFORM_CONFIRMED.storedValue, legacy!!.third
        )

        val store2 = ConversationStore(ctx)
        try {
            assertFalse(
                "a platform-confirmed conversation is not ambiguous",
                store2.isAmbiguous(cid)
            )
        } finally { store2.close() }
    }

    @Test
    fun weakerEvidenceNeverOverwritesStrongerOwnership() {
        buildV5Fixture()
        val open = InboxDatabase(ctx)
        open.writableDatabase
        open.close()

        // The back-fill inferred "sc-backfilled" from a stored key.
        val store = ConversationStore(ctx)
        try {
            val cidInferred = store.find(pkg, AliasType.SHORTCUT, "sc-backfilled")!!

            // A later resolution for the SAME shortcut from the platform would
            // legitimately confirm it — that is allowed to happen.
            val live = ConversationIdentityResolver(store)
                .resolve(pkg, "sc-backfilled", "家族群組")
            assertEquals(cidInferred, live.conversationId)
            assertEquals(Strength.PLATFORM_CONFIRMED, live.strength)

            // But a WEAKER claim for the same alias must NOT change its owner.
            val result = store.putAliasChecked(
                pkg, AliasType.SHORTCUT, "sc-backfilled",
                "cv_some_other_conversation", Strength.TITLE_ONLY
            )
            assertEquals(
                "a weaker claim on an owned alias is a CONFLICT, not a silent take-over",
                AliasPutResult.CONFLICT, result
            )
            assertEquals(
                "ownership unchanged",
                cidInferred, store.find(pkg, AliasType.SHORTCUT, "sc-backfilled")
            )
        } finally { store.close() }
    }

    @Test
    fun manualConfirmationIsStrongerThanInference() {
        buildV5Fixture()
        val open = InboxDatabase(ctx)
        open.writableDatabase
        open.close()

        val store = ConversationStore(ctx)
        try {
            val a = ConversationIdentityResolver(store)
                .resolve(pkg, "sc-manual-a", "A").conversationId
            val b = ConversationIdentityResolver(store)
                .resolve(pkg, "sc-manual-b", "B").conversationId

            // The user explicitly merges: a MANUAL alias on B pointing at A is a
            // user decision, which is certain.
            val result = store.putAliasChecked(
                pkg, AliasType.MANUAL, "manual-merge-b-into-a", a, Strength.MANUAL_CONFIRMED
            )
            assertEquals(AliasPutResult.INSERTED, result)
            assertEquals(a, store.find(pkg, AliasType.MANUAL, "manual-merge-b-into-a"))
            // A manual assertion is certain evidence, so the target conversation's
            // ambiguity is resolved (it holds at least one certain alias).
            assertFalse(
                "the user-confirmed target is not ambiguous",
                store.isAmbiguous(a)
            )
            assertTrue(
                "B still has only platform evidence for itself — it is not ambiguous, " +
                    "but the point here is that the manual row does not leak onto B",
                store.aliasesOf(b).none {
                    it.first == "MANUAL"
                }
            )
        } finally { store.close() }
    }

    @Test
    fun historicalRowsStillParse() {
        // A database written by the two-value build stores "STRONG"/"WEAK".
        // fromStored must map both, or existing installs would lose their
        // certainty status.
        assertEquals(Strength.PLATFORM_CONFIRMED, Strength.fromStored("STRONG"))
        assertEquals(Strength.TITLE_ONLY, Strength.fromStored("WEAK"))
        assertEquals(Strength.PLATFORM_CONFIRMED, Strength.fromStored("PLATFORM_CONFIRMED"))
        assertEquals(Strength.MANUAL_CONFIRMED, Strength.fromStored("MANUAL_CONFIRMED"))
        assertEquals(Strength.LEGACY_INFERRED, Strength.fromStored("LEGACY_INFERRED"))
        assertEquals(Strength.TITLE_ONLY, Strength.fromStored("TITLE_ONLY"))
        assertEquals(Strength.AMBIGUOUS, Strength.fromStored("AMBIGUOUS"))
        assertEquals("an unknown value is treated as uncertain",
            Strength.AMBIGUOUS, Strength.fromStored("garbage"))
        assertEquals(Strength.AMBIGUOUS, Strength.fromStored(null))
        // And the round-trip back to the stored column keeps the old values.
        assertEquals("STRONG", Strength.PLATFORM_CONFIRMED.storedValue)
        assertEquals("WEAK", Strength.TITLE_ONLY.storedValue)
        assertEquals("LEGACY_INFERRED", Strength.LEGACY_INFERRED.storedValue)
    }
    // ============================================================ G2.5-B

    @Test
    fun provenanceSurvivesRestart() {
        buildV5Fixture()
        val open = InboxDatabase(ctx)
        open.writableDatabase   // backfill runs (LEGACY_INFERRED)
        open.close()

        val store = ConversationStore(ctx)
        try {
            ConversationIdentityResolver(store).resolve(pkg, "sc-live", "群組B")
        } finally { store.close() }

        // Restart: fresh store over the same DB.
        val store2 = ConversationStore(ctx)
        try {
            assertEquals(
                "the back-filled shortcut keeps LEGACY_INFERRED across a restart",
                Strength.LEGACY_INFERRED,
                store2.provenanceOf(pkg, AliasType.SHORTCUT, "sc-backfilled")
            )
            assertEquals(
                "the live platform shortcut keeps PLATFORM_CONFIRMED",
                Strength.PLATFORM_CONFIRMED,
                store2.provenanceOf(pkg, AliasType.SHORTCUT, "sc-live")
            )
            assertEquals(
                "the title-only alias is AMBIGUOUS provenance",
                Strength.AMBIGUOUS,
                store2.provenanceOf(pkg, AliasType.LEGACY_KEY,
                    LegacyKeyCodec.format(pkg, null, "沒有識別的群組"))
            )
        } finally { store2.close() }
    }

    @Test
    fun preProvenanceRowsFallBackToAmbiguous() {
        // Simulate a database written BEFORE the provenance column existed:
        // rows have strength STRONG/WEAK and a NULL provenance.
        buildV5Fixture()
        val open = InboxDatabase(ctx)
        open.writableDatabase
        open.close()

        val db = InboxDatabase(ctx)
        try {
            // Overwrite provenance to NULL, simulating an older row.
            db.writableDatabase.execSQL(
                "UPDATE conversation_aliases SET provenance = NULL"
            )
        } finally { db.close() }

        val store = ConversationStore(ctx)
        try {
            assertEquals(
                "a historical STRONG row must NOT be reinterpreted as " +
                    "PLATFORM_CONFIRMED — null provenance falls back to AMBIGUOUS",
                Strength.AMBIGUOUS,
                store.provenanceOf(pkg, AliasType.SHORTCUT, "sc-backfilled")
            )
        } finally { store.close() }
    }

    @Test
    fun provenanceColumnIsAddedIdempotently() {
        buildV5Fixture()
        // Open three times; the ALTER must not throw on the second and third.
        repeat(3) {
            val db = InboxDatabase(ctx)
            db.writableDatabase
            db.close()
        }
        val raw = SQLiteDatabase.openDatabase(
            ctx.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY
        )
        try {
            val cols = ArrayList<String>()
            raw.rawQuery("PRAGMA table_info(conversation_aliases)", null).use { c ->
                val nameIdx = c.getColumnIndex("name")
                while (c.moveToNext()) cols.add(c.getString(nameIdx))
            }
            assertTrue(
                "the provenance column must exist after repeated opens",
                cols.contains("provenance")
            )
        } finally { raw.close() }
    }

}
