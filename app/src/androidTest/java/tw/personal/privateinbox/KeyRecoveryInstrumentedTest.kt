package tw.personal.privateinbox

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
 * The Keystore recovery flow.
 *
 * The rules under test:
 *
 *  1. A key that is only TEMPORARILY unavailable must not offer to delete
 *     anything.
 *  2. Undecryptable data must NEVER be deleted automatically.
 *  3. Deleting requires an explicit, informed confirmation.
 *  4. A new key must not silently overwrite data that cannot be decrypted.
 */
@RunWith(AndroidJUnit4::class)
class KeyRecoveryInstrumentedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: InboxDatabase

    @Before
    fun setUp() {
        // This test destroys the database, so it only runs against the
        // ISOLATED variant. On the production package it is SKIPPED
        // with a reason, and nothing is deleted.
        db = InboxDatabase(ctx)
        DestructiveTestAssumption.requireIsolated(ctx)
        TestDataIsolation.clearDatabase(ctx)
    }

    @After
    fun tearDown() {
        // The cleanup is a no-op outside an isolated package: the
        // tryClearDatabase form returns false instead of throwing, and the
        // handle is only closed when it was actually opened.
        TestDataIsolation.tryClearDatabase(ctx)
        if (this::db.isInitialized) db.close()
    }

    // ------------------------------------------------------- the descriptions

    @Test
    fun anAvailableKeyOffersNothing() {
        val d = KeyRecovery.describe(
            CryptoStore.KeyState.KEY_AVAILABLE, unreadableRows = 0, totalRows = 10
        )
        assertEquals(KeyRecovery.Action.NONE, d.action)
        assertTrue(
            "the message must say the key is fine",
            d.message.contains("正常") || d.message.contains("可以解密")
        )
    }

    @Test
    fun aTemporarilyUnavailableKeyOffersNothing() {
        val d = KeyRecovery.describe(
            CryptoStore.KeyState.KEY_TEMPORARILY_UNAVAILABLE, unreadableRows = 0, totalRows = 10
        )
        // A locked device is NOT a loss. Offering to delete here would destroy
        // data that is perfectly recoverable.
        assertEquals(
            "a temporarily unavailable key must not offer to delete anything",
            KeyRecovery.Action.NONE, d.action
        )
        assertTrue(
            "the message must say the data is not damaged",
            d.message.contains("沒有損壞") || d.message.contains("解鎖")
        )
    }

    @Test
    fun aDecryptFailureOffersNothingWhenNoRowIsAffected() {
        val d = KeyRecovery.describe(
            CryptoStore.KeyState.DECRYPT_FAILED, unreadableRows = 0, totalRows = 10
        )
        assertEquals(KeyRecovery.Action.NONE, d.action)
    }

    @Test
    fun anInvalidatedKeyOffersTheClearingChoice() {
        val d = KeyRecovery.describe(
            CryptoStore.KeyState.KEY_INVALIDATED, unreadableRows = 5, totalRows = 10
        )
        assertEquals(KeyRecovery.Action.ASK_ABOUT_CLEARING, d.action)
        assertTrue("the count must be shown", d.message.contains("5"))
        assertTrue(
            "the message must say the key cannot be recovered",
            d.message.contains("無法復原")
        )
    }

    @Test
    fun aRecoveryRequiredKeyOffersTheClearingChoice() {
        val d = KeyRecovery.describe(
            CryptoStore.KeyState.RECOVERY_REQUIRED, unreadableRows = 12, totalRows = 12
        )
        assertEquals(KeyRecovery.Action.ASK_ABOUT_CLEARING, d.action)
        assertTrue("the count must be shown", d.message.contains("12"))
    }

    // -------------------------------------------------- the data is protected

    @Test
    fun unreadableRowsAreNotDeletedByReading() {
        // A row that cannot be decrypted must survive every read.
        db.save(
            "jp.naver.line.android", 1_700_000_000_000L, 1_700_000_000_000L,
            "Group", "Alice", "readable message",
            null, "conv-1", "DIRECT_MESSAGE", "idx:0"
        )
        // Corrupt the sealed content so the row cannot be decrypted.
        corruptOneRow()
        val before = db.recentWithState(100)
        // Reading repeatedly must never delete anything.
        repeat(5) { db.recentWithState(100) }
        val after = db.recentWithState(100)
        assertEquals(
            "reading must not delete unreadable rows",
            before.unreadableRows, after.unreadableRows
        )
        assertTrue(
            "the unreadable row must still be counted",
            after.unreadableRows >= 1
        )
    }

    @Test
    fun purgingRemovesOnlyTheUndecryptableRows() {
        db.save(
            "jp.naver.line.android", 1_700_000_000_000L, 1_700_000_000_000L,
            "Group", "Alice", "readable one",
            null, "conv-1", "DIRECT_MESSAGE", "idx:0"
        )
        db.save(
            "jp.naver.line.android", 1_700_000_000_001L, 1_700_000_000_001L,
            "Group", "Bob", "readable two",
            null, "conv-1", "DIRECT_MESSAGE", "idx:1"
        )
        assertEquals("both rows must be stored", 2, db.recent(10).size)

        corruptOneRow()
        val state = db.recentWithState(10)
        assertTrue("one row must be undecryptable", state.unreadableRows >= 1)

        // The purge is the ONLY thing that removes data, and it is only
        // reached after an explicit confirmation.
        val removed = TestDataIsolation.purgeUndecryptableRows(ctx)
        assertTrue("the purge must report what it removed", removed >= 1)

        val after = db.recentWithState(10)
        assertEquals(
            "the readable row must survive the purge",
            0, after.unreadableRows
        )
        assertTrue(
            "at least one readable row must remain",
            after.entries.isNotEmpty()
        )
    }

    @Test
    fun aPurgeOnHealthyDataRemovesNothing() {
        db.save(
            "jp.naver.line.android", 1_700_000_000_000L, 1_700_000_000_000L,
            "Group", "Alice", "readable",
            null, "conv-1", "DIRECT_MESSAGE", "idx:0"
        )
        val removed = TestDataIsolation.purgeUndecryptableRows(ctx)
        assertEquals("a healthy database must not lose anything", 0, removed)
        assertEquals("the row must still be there", 1, db.recent(10).size)
    }

    // ---------------------------------------------------------- the diagnosis

    @Test
    fun theDiagnosisReportsTheKeyState() {
        val diagnosis = KeyRecovery.diagnose(ctx)
        assertTrue(
            "the diagnosis must name the provider",
            diagnosis.contains("provider=AndroidKeyStore")
        )
        assertTrue(
            "the diagnosis must report whether the key is usable",
            diagnosis.contains("usableNow=")
        )
        assertTrue(
            "the diagnosis must report the lock state",
            diagnosis.contains("deviceLocked=")
        )
    }

    @Test
    fun theDiagnosisDoesNotLeakKeyMaterial() {
        val diagnosis = KeyRecovery.diagnose(ctx)
        assertFalse(
            "the diagnosis must not contain key material",
            diagnosis.contains("PRIVATE KEY") || diagnosis.length > 2000
        )
    }

    /**
     * Corrupt one row so it can no longer be decrypted.
     *
     * The sealed content is overwritten with bytes that are not a valid
     * GCM ciphertext, which is exactly what a corrupted or tampered row
     * looks like.
     */
    private fun corruptOneRow() {
        runCatching {
            val sql = "UPDATE messages SET sealed_content = x'00' LIMIT 1"
            // LIMIT is not supported by SQLite's UPDATE, so the first row is
            // selected by id instead.
            val writable = db.writableDatabase
            writable.execSQL(
                "UPDATE messages SET sealed_content = x'00' WHERE id = " +
                    "(SELECT MIN(id) FROM messages)"
            )
        }
    }
}
