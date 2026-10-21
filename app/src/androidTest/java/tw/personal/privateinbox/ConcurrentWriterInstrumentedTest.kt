package tw.personal.privateinbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The concurrent-writer contract.
 *
 * The listener writes from ONE thread, but the UI opens its own database
 * handle on a two-thread pool. That means two writers can exist at the same
 * time, on different handles. This test proves that the second writer does not
 * fail and does not corrupt the data.
 *
 * It also records the journal mode, because the single-writer guarantee comes
 * from it.
 */
@RunWith(AndroidJUnit4::class)
class ConcurrentWriterInstrumentedTest {

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

    @Test
    fun theJournalModeIsRecorded() {
        val mode = db.journalMode()
        println("CONCURRENT journal_mode=$mode")
        assertTrue(
            "the journal mode must be readable",
            mode.isNotEmpty()
        )
    }

    @Test
    fun twoWritersDoNotFailAndDoNotLoseRows() {
        val threads = 2
        val perThread = 150
        val pool = Executors.newFixedThreadPool(threads)
        val failures = AtomicInteger(0)
        val written = AtomicInteger(0)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        for (t in 0 until threads) {
            pool.execute {
                val handle = InboxDatabase(ctx)
                try {
                    start.await()
                    for (i in 0 until perThread) {
                        val ts = 1_700_000_000_000L + t * 100_000L + i
                        val result = handle.save(
                            "jp.naver.line.android", ts, ts,
                            "Concurrent $t", "Sender $i", "body $t-$i",
                            null, "conv-$t", "DIRECT_MESSAGE", "idx:$i"
                        )
                        if (result is InboxDatabase.SaveResult.NEW) {
                            written.incrementAndGet()
                        } else if (result is InboxDatabase.SaveResult.FAILURE) {
                            failures.incrementAndGet()
                        }
                    }
                } finally {
                    handle.close()
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue("the writers must finish", done.await(120, TimeUnit.SECONDS))
        pool.shutdown()

        println(
            "CONCURRENT threads=$threads per_thread=$perThread " +
                "written=$written failures=${failures.get()} " +
                "rows=${db.countRows()}"
        )

        assertEquals(
            "no write may fail because of the concurrent writer",
            0, failures.get()
        )
        assertEquals(
            "every row must be present exactly once",
            threads * perThread, db.countRows()
        )
    }

    @Test
    fun aReaderDoesNotBlockAWriter() {
        val handle = InboxDatabase(ctx)
        val pool = Executors.newSingleThreadExecutor()
        val failures = AtomicInteger(0)
        val written = AtomicInteger(0)
        val start = CountDownLatch(1)
        val done = CountDownLatch(1)

        // A writer on its own handle.
        pool.execute {
            val writer = InboxDatabase(ctx)
            try {
                start.await()
                for (i in 0 until 200) {
                    val ts = 1_700_000_000_000L + i
                    val result = writer.save(
                        "jp.naver.line.android", ts, ts,
                        "Mixed", "Sender", "body $i",
                        null, "conv-mixed", "DIRECT_MESSAGE", "idx:$i"
                    )
                    if (result is InboxDatabase.SaveResult.NEW) written.incrementAndGet()
                    if (result is InboxDatabase.SaveResult.FAILURE) failures.incrementAndGet()
                }
            } finally {
                writer.close()
                done.countDown()
            }
        }
        start.countDown()
        // A reader on another handle, at the same time.
        val readerThread = Thread {
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline && done.count > 0) {
                runCatching { handle.recent(50) }
            }
        }
        readerThread.start()

        assertTrue("the writer must finish", done.await(60, TimeUnit.SECONDS))
        readerThread.join(5_000)
        handle.close()
        pool.shutdown()

        println("CONCURRENT-MIXED written=${written.get()} failures=${failures.get()}")
        assertEquals("no write may fail", 0, failures.get())
        assertEquals("every row must be present", 200, written.get())
    }
}
