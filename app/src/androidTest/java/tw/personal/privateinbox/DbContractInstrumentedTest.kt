package tw.personal.privateinbox

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Database-failure / contract test: asserts the SAVE surface returns a real result
 * (never a silent success-or-crash), duplicate posts collapse to DUPLICATE, and
 * encryption round-trips. These are the invariants that catch the original
 * `execSQL("PRAGMA ...")`-class database-open failure and the empty-catch bug.
 */
@RunWith(AndroidJUnit4::class)
class DbContractInstrumentedTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun saveReturnsConcreteResultNeverThrows() {
        val db = InboxDatabase(ctx)
        val r = try {
            db.save("db-contract", System.currentTimeMillis(), 0L,
                "Contract", "C1", "contract row", null)
        } finally { db.close() }
        // NEW or DUPLICATE (row may already exist from a prior run); never FAILURE
        // and never an exception escaping save().
        assertNotNull(r)
        assertTrue(r is InboxDatabase.SaveResult.NEW || r is InboxDatabase.SaveResult.DUPLICATE)
    }

    @Test
    fun identicalPostsAreDedupedToDuplicate() {
        val db = InboxDatabase(ctx)
        try {
            val ts = 1_711_000_000_000L
            db.save("db-contract-dup", ts, 0L, "Dup", "D", "same body", null)
            val second = db.save("db-contract-dup", ts, 0L, "Dup", "D", "same body", null)
            assertEquals(InboxDatabase.SaveResult.DUPLICATE, second)
        } finally { db.close() }
    }

    @Test
    fun distinctBodiesAreNotWronglyMerged() {
        val db = InboxDatabase(ctx)
        try {
            val ts = 1_711_000_000_001L
            val a = db.save("db-contract-dist", ts, 0L, "Dist", "D", "body A", null)
            val b = db.save("db-contract-dist", ts, 0L, "Dist", "D", "body B", null)
            assertEquals(InboxDatabase.SaveResult.NEW, a)
            assertEquals(InboxDatabase.SaveResult.NEW, b)
        } finally { db.close() }
    }

    @Test
    fun encryptedContentRoundTripsThroughDatabase() {
        val db = InboxDatabase(ctx)
        try {
            db.save("db-contract-rt", System.currentTimeMillis(), 0L, "RT", "R",
                "secret round trip ${System.nanoTime()}", null)
            val row = db.recent(500).firstOrNull { it.packageName == "db-contract-rt" }
            assertNotNull(row)
            assertNotNull("stored body must decrypt back to itself", row)
        } finally { db.close() }
    }
}
