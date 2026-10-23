package tw.personal.privateinbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.system.measureNanoTime

/**
 * Performance baseline, measured on a real device.
 *
 * The data is entirely SYNTHETIC. No real chat content is used, and nothing
 * is written to Logcat.
 *
 * The numbers this produces are the "BEFORE" column of the performance
 * documentation. Optimisations must be measured against the same procedure
 * so the comparison is meaningful.
 */
@RunWith(AndroidJUnit4::class)
class PerformanceBaselineInstrumentedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: InboxDatabase

    /** A synthetic body, clearly not a real message. */
    private fun syntheticBody(i: Int) = "synthetic test payload #$i — 測試資料"

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

    private fun seed(count: Int) {
        val base = 1_700_000_000_000L
        for (i in 0 until count) {
            db.save("org.telegram.messenger", base + i, base + i,
                "Contact $i", "Sender $i", syntheticBody(i), null)
        }
    }

    private fun dbFileSize(): Long {
        val f = ctx.getDatabasePath("inbox.db")
        var total = 0L
        listOf(f, File(f.parentFile, f.name + "-wal"), File(f.parentFile, f.name + "-shm"))
            .forEach { if (it.exists()) total += it.length() }
        return total
    }

    @Test
    fun measureWriteThroughput() {
        val counts = listOf(100, 1000)
        val results = counts.map { n ->
        // This test destroys the database, so it only runs against the
        // ISOLATED variant. On the production package it is SKIPPED
        // with a reason, and nothing is deleted.
        DestructiveTestAssumption.requireIsolated(ctx)
            TestDataIsolation.clearDatabase(ctx)
            val elapsed = measureNanoTime { seed(n) }
            n to (elapsed / 1_000_000.0)
        }
        results.forEach { (n, ms) ->
            println("PERF write $n rows: ${"%.1f".format(ms)} ms " +
                "(${"%.1f".format(ms * 1000 / n)} us/row)")
        }
        assertTrue("the write benchmark must produce results", results.isNotEmpty())
    }

    @Test
    fun measureReadAndDecrypt() {
        val counts = listOf(100, 1000, 10000)
        for (n in counts) {
        // This test destroys the database, so it only runs against the
        // ISOLATED variant. On the production package it is SKIPPED
        // with a reason, and nothing is deleted.
        DestructiveTestAssumption.requireIsolated(ctx)
            TestDataIsolation.clearDatabase(ctx)
            seed(n)
            // Warm the page cache so the measurement reflects decryption cost
            // rather than cold disk IO.
            db.recent(10)
            val samples = ArrayList<Double>()
            // The wall-clock budget of the measurement.
            //
            // Reading and decrypting 10,000 rows takes seconds per sample, and
            // five samples of it can exceed the instrumentation timeout on a
            // slow machine. A benchmark that FAILS because it is slow is not
            // reporting anything, so the sample count is reduced for the large
            // scale instead of letting the test time out.
            //
            // The number of samples taken is printed, so a reduced sample
            // count is visible rather than implied.
            val budgetMillis = 120_000L
            val started = System.currentTimeMillis()
            while (samples.size < 5) {
                val elapsed = measureNanoTime { db.recent(n) }
                samples += elapsed / 1_000_000.0
                // Stop early if the remaining budget cannot fit another sample.
                val used = System.currentTimeMillis() - started
                if (samples.size >= 1 && used + (elapsed / 1_000_000.0) > budgetMillis) break
            }
            samples.sort()
            val p50 = samples[samples.size / 2]
            val p95 = samples[(samples.size * 95 / 100).coerceAtMost(samples.size - 1)]
            println("PERF read+decrypt $n rows: p50=${"%.1f".format(p50)} ms " +
                "p95=${"%.1f".format(p95)} ms " +
                "(${"%.2f".format(p50 * 1000 / n)} us/row) " +
                "samples=${samples.size} " +
                "dbSize=${dbFileSize() / 1024} KiB")
        }
        assertTrue(true)
    }

    @Test
    fun measureQueryLatencyByIndex() {
        seed(1000)
        val samples = ArrayList<Double>()
        repeat(20) {
            val elapsed = measureNanoTime {
                db.recent(50)
            }
            samples += elapsed / 1_000_000.0
        }
        samples.sort()
        val p50 = samples[samples.size / 2]
        val p95 = samples[(samples.size * 95 / 100).coerceAtMost(samples.size - 1)]
        println("PERF indexed recent(50) on 1000 rows: p50=${"%.1f".format(p50)} ms " +
            "p95=${"%.1f".format(p95)} ms")
        assertTrue(true)
    }

    @Test
    fun measureAesGcmDecryptCost() {
        val payloads = ArrayList<Pair<String, ByteArray>>()
        val base = 1_700_000_000_000L
        for (i in 0 until 200) {
            val body = syntheticBody(i).toByteArray(Charsets.UTF_8)
            payloads += "org.telegram.messenger" to CryptoStore.seal(
                "org.telegram.messenger",
                body
            )
        }
        // Warm up: the first call fetches the key handle from the keystore, and
        // the JIT needs to compile the crypto path. Both are one-off costs that
        // would otherwise dominate a small sample.
        repeat(20) { payloads.forEach { (pkg, blob) -> CryptoStore.open(pkg, blob) } }

        // Several rounds, so the reported number is a median rather than a
        // single noisy sample on a shared emulator.
        val rounds = ArrayList<Double>()
        repeat(5) {
            val elapsed = measureNanoTime {
                payloads.forEach { (pkg, blob) -> CryptoStore.open(pkg, blob) }
            }
            rounds += elapsed / 1_000_000.0
        }
        rounds.sort()
        val p50 = rounds[rounds.size / 2]
        val p95 = rounds[(rounds.size * 95 / 100).coerceAtMost(rounds.size - 1)]
        println("PERF aes-gcm decrypt: p50=${"%.1f".format(p50)} ms " +
            "p95=${"%.1f".format(p95)} ms for ${payloads.size} payloads " +
            "(${"%.1f".format(p50 * 1000 / payloads.size)} us/op)")
        assertTrue(true)
    }

    @Test
    fun measureDatabaseGrowth() {
        // This test destroys the database, so it only runs against the
        // ISOLATED variant. On the production package it is SKIPPED
        // with a reason, and nothing is deleted.
        DestructiveTestAssumption.requireIsolated(ctx)
        TestDataIsolation.clearDatabase(ctx)
        val before = dbFileSize()
        seed(1000)
        val after = dbFileSize()
        println("PERF db growth for 1000 rows: ${(after - before) / 1024} KiB " +
            "(${"%.1f".format((after - before) / 1020.0)} bytes/row)")
        assertTrue(true)
    }

    @Test
    fun measureMemoryFootprint() {
        seed(1000)
        val runtime = Runtime.getRuntime()
        val before = runtime.totalMemory() - runtime.freeMemory()
        val entries = db.recent(1000)
        val after = runtime.totalMemory() - runtime.freeMemory()
        println("PERF memory while holding ${entries.size} decrypted rows: " +
            "${(after - before) / 1024} KiB")
        assertTrue(entries.isNotEmpty())
    }

    @Test
    fun measureActivityStartLatency() {
        // Cold start: the activity is created for the first time in this
        // process, so the whole class-loading and view-building path runs.
        val cold = measureNanoTime {
            androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
                .use { scenario ->
                    scenario.onActivity { }
                }
        }
        // Warm start: the activity is created again, with the class loader
        // and the Keystore handle already warm.
        val warm = measureNanoTime {
            androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
                .use { scenario ->
                    scenario.onActivity { }
                }
        }
        println("PERF activity start: cold=${cold / 1_000_000.0} ms " +
            "warm=${warm / 1_000_000.0} ms")
        assertTrue(true)
    }
}
