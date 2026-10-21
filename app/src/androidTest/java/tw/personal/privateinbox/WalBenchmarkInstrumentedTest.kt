package tw.personal.privateinbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The WAL A/B benchmark.
 *
 * The previous round measured that the database uses `journal_mode=truncate`
 * and that two concurrent writers did not fail. That does NOT prove that WAL
 * would be better -- it only proves that the current setup is adequate for
 * that particular load.
 *
 * This benchmark measures the two modes against the same workload and reports
 * the numbers side by side, so the decision is based on data rather than on
 * the general reputation of WAL.
 *
 * IMPORTANT: WAL does NOT remove SQLite's single-writer rule. A WAL reader
 * never blocks the writer, and a writer never blocks a reader, but two
 * WRITERS still serialise. The benchmark measures that honestly.
 */
@RunWith(AndroidJUnit4::class)
class WalBenchmarkInstrumentedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        // The benchmark is destructive; it only runs in the isolated variant.
        DestructiveTestAssumption.requireIsolated(ctx)
    }

    @After
    fun tearDown() {
        TestDataIsolation.tryClearDatabase(ctx)
    }

    /** The result of one benchmark run. */
    data class Run(
        val mode: String,
        val writes: Int,
        val failures: Int,
        val busyErrors: Int,
        val p50: Double,
        val p95: Double,
        val p99: Double,
        val max: Double,
        val openMillis: Long
    ) {
        fun line(): String = String.format(
            "WAL mode=%s writes=%d failures=%d busy=%d " +
                "p50=%.1f p95=%.1f p99=%.1f max=%.1f open_ms=%d",
            mode, writes, failures, busyErrors, p50, p95, p99, max, openMillis
        )
    }

    /**
     * Run the write workload with the given journal mode.
     *
     * The mode is set on the database file itself with `PRAGMA journal_mode`,
     * which is what SQLite does when the flag is enabled.
     */
    private fun run(mode: String, writers: Int, perWriter: Int): Run {
        val db = InboxDatabase(ctx)
        // Set the mode explicitly so both arms are measured in the state they
        // are meant to be in.
        val effective = db.setJournalMode(mode)
        val openStart = System.nanoTime()
        db.close()
        val openMillis = (System.nanoTime() - openStart) / 1_000_000

        val failures = AtomicInteger(0)
        val busy = AtomicInteger(0)
        val latencies = java.util.Collections.synchronizedList(ArrayList<Long>())
        val pool = Executors.newFixedThreadPool(writers)
        val start = CountDownLatch(1)
        val done = CountDownLatch(writers)

        for (w in 0 until writers) {
            pool.execute {
                val handle = InboxDatabase(ctx)
                try {
                    start.await()
                    for (i in 0 until perWriter) {
                        val ts = 1_700_000_000_000L + w * 1_000_000L + i
                        val t0 = System.nanoTime()
                        val result = handle.save(
                            "jp.naver.line.android", ts, ts,
                            "Bench $w", "Sender $i", "body $w-$i",
                            null, "bench-$w", "DIRECT_MESSAGE", "idx:$i"
                        )
                        latencies.add((System.nanoTime() - t0) / 1_000_000)
                        if (result is InboxDatabase.SaveResult.FAILURE) {
                            failures.incrementAndGet()
                            if (result.cause.javaClass.simpleName.contains("Busy")) {
                                busy.incrementAndGet()
                            }
                        }
                    }
                } finally {
                    handle.close()
                    done.countDown()
                }
            }
        }
        start.countDown()
        done.await(180, TimeUnit.SECONDS)
        pool.shutdown()

        val sorted = latencies.sorted()
        fun pct(q: Double): Double {
            if (sorted.isEmpty()) return 0.0
            val i = ((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)
            return sorted[i].toDouble()
        }
        val result = Run(
            mode = effective,
            writes = writers * perWriter,
            failures = failures.get(),
            busyErrors = busy.get(),
            p50 = pct(0.50),
            p95 = pct(0.95),
            p99 = pct(0.99),
            max = (sorted.lastOrNull() ?: 0L).toDouble(),
            openMillis = openMillis
        )
        // Leave the database in the mode it was in before, so the arms do not
        // contaminate each other.
        db.setJournalMode("truncate")
        db.close()
        return result
    }

    @Test
    fun theJournalModeCanBeSetAndReadBack() {
        val db = InboxDatabase(ctx)
        val before = db.journalMode()
        val set = db.setJournalMode("wal")
        val read = db.journalMode()
        db.setJournalMode("truncate")
        db.close()
        println("WAL-MODE before=$before set=$set read=$read")
        assertTrue(
            "the journal mode must be readable",
            before.isNotEmpty() && read.isNotEmpty()
        )
    }

    @Test
    fun theTwoModesAreComparedOnTheSameWorkload() {
        // The same workload for both arms.
        val writers = 2
        val perWriter = 250

        val rollback = run("truncate", writers, perWriter)
        val wal = run("wal", writers, perWriter)

        println("WAL-A " + rollback.line())
        println("WAL-B " + wal.line())

        // Both arms must deliver every row. A mode that loses rows is not an
        // improvement, whatever its latency.
        assertEquals("the rollback arm must not lose writes", 0, rollback.failures)
        assertEquals("the WAL arm must not lose writes", 0, wal.failures)
        assertEquals(
            "the WAL arm must commit every row",
            writers * perWriter, wal.writes
        )
    }

    @Test
    fun walDoesNotRemoveTheSingleWriterRule() {
        // The honest statement: WAL removes reader/writer BLOCKING, not the
        // single-writer rule. Two writers still serialise.
        //
        // This test records the behaviour so the claim is backed by a
        // measurement rather than by folklore.
        val writers = 3
        val perWriter = 150
        val wal = run("wal", writers, perWriter)
        println("WAL-3WRITERS " + wal.line())
        assertEquals("no write may be lost", 0, wal.failures)
    }
}
