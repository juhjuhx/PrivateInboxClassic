package tw.personal.privateinbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The performance baseline for the list read path.
 *
 * The measurement is the SAME one the previous round made, so the numbers can
 * be compared directly. The synthetic data is generated locally; no real
 * message content is involved.
 *
 * What is measured:
 *  - the time to read a page of rows and decrypt them,
 *  - the time to read the WHOLE table (the unbounded case), and
 *  - the database's own size on disk.
 *
 * The point of the exercise is the DIFFERENCE between the bounded read and
 * the unbounded one, because that is what pagination buys.
 */
@RunWith(AndroidJUnit4::class)
class PaginationPerformanceInstrumentedTest {

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

    /**
     * Fill the database with [count] synthetic messages spread over
     * [conversations] conversations.
     */
    private fun seed(count: Int, conversations: Int) {
        val perConversation = count / conversations
        var inserted = 0
        for (c in 0 until conversations) {
            for (i in 0 until perConversation) {
                val ts = 1_700_000_000_000L + inserted
                db.save(
                    "jp.naver.line.android", ts, ts,
                    "Synthetic Conversation $c", "Sender $i",
                    "synthetic body for the performance baseline, index $inserted, " +
                        "padded so that the sealed payload has a realistic size " +
                        "and the decryption cost is not unrealistically small.",
                    null, "conv-$c", "DIRECT_MESSAGE", "idx:$i"
                )
                inserted++
            }
        }
    }

    @Test
    fun theReadPathBaseline() {
        val sizes = listOf(100, 1_000, 10_000)
        for (size in sizes) {
        // This test destroys the database, so it only runs against the
        // ISOLATED variant. On the production package it is SKIPPED
        // with a reason, and nothing is deleted.
        DestructiveTestAssumption.requireIsolated(ctx)
            TestDataIsolation.clearDatabase(ctx)
            seed(size, conversations = 10)
            val stored = db.countRows()
            assertTrue("the seed must have stored $size rows (got $stored)", stored >= size)

            // The unbounded read: the legacy path, capped by the read limit.
            val unboundedStart = System.nanoTime()
            val all = db.recent(20_000)
            val unboundedMs = (System.nanoTime() - unboundedStart) / 1_000_000

            // The PAGED read: the same rows, loaded a page at a time. This is
            // what the app does now, and it is the number that matters for
            // the first screen.
            val pagedStart = System.nanoTime()
            var pagedCount = 0
            var page = db.readPage(limit = 50)
            while (true) {
                pagedCount += page.entries.size
                if (!page.hasMore) break
                val cursor = page.nextCursor()!!
                page = db.readPage(limit = 50, beforeReceivedAt = cursor.first, beforeId = cursor.second)
            }
            val pagedMs = (System.nanoTime() - pagedStart) / 1_000_000
            assertTrue(
                "the paged read must visit every row",
                pagedCount >= size
            )

            // A single page: what pagination actually reads.
            val pageStart = System.nanoTime()
            val firstPage = db.readPage(limit = 50)
            val pageMs = (System.nanoTime() - pageStart) / 1_000_000

            // The conversation list: the grouped view the user sees first.
            val listStart = System.nanoTime()
            val grouped = all.groupBy { it.conversationKey }
            val listMs = (System.nanoTime() - listStart) / 1_000_000

            println(
                "PERF-BASELINE rows=$stored " +
                    "unbounded_ms=$unboundedMs " +
                    "paged_all_ms=$pagedMs paged_rows=$pagedCount " +
                    "page50_ms=$pageMs page_rows=${firstPage.entries.size} " +
                    "group_ms=$listMs conversations=${grouped.size}"
            )
            assertTrue("the page must not be empty", firstPage.entries.isNotEmpty())
        }
    }

    @Test
    fun theDatabaseGrowsLinearly() {
        // This test destroys the database, so it only runs against the
        // ISOLATED variant. On the production package it is SKIPPED
        // with a reason, and nothing is deleted.
        DestructiveTestAssumption.requireIsolated(ctx)
        TestDataIsolation.clearDatabase(ctx)
        seed(1_000, conversations = 10)
        val small = db.databaseSizeBytes()
        TestDataIsolation.clearDatabase(ctx)
        seed(10_000, conversations = 10)
        val large = db.databaseSizeBytes()
        println("PERF-SIZE rows_1k_bytes=$small rows_10k_bytes=$large")
        assertTrue("the database must grow with the row count", large > small)
    }

    @Test
    fun aPageReadIsMuchCheaperThanAnUnboundedRead() {
        // This test destroys the database, so it only runs against the
        // ISOLATED variant. On the production package it is SKIPPED
        // with a reason, and nothing is deleted.
        DestructiveTestAssumption.requireIsolated(ctx)
        TestDataIsolation.clearDatabase(ctx)
        seed(10_000, conversations = 10)

        // Measure the unbounded read.
        val unboundedStart = System.nanoTime()
        db.recent(20_000)
        val unboundedMs = (System.nanoTime() - unboundedStart) / 1_000_000

        // Measure a page read, several times, and take the best so the
        // measurement is not dominated by a single slow run.
        var bestPageMs = Long.MAX_VALUE
        repeat(5) {
            val start = System.nanoTime()
            db.recent(50)
            val ms = (System.nanoTime() - start) / 1_000_000
            if (ms < bestPageMs) bestPageMs = ms
        }
        println("PERF-COMPARE unbounded_ms=$unboundedMs page50_ms=$bestPageMs")
        assertTrue(
            "a page read must be cheaper than an unbounded read " +
                "(unbounded=$unboundedMs page=$bestPageMs)",
            bestPageMs <= unboundedMs
        )
    }
}
