import io

path = 'app/src/androidTest/java/tw/personal/privateinbox/PerformanceBaselineInstrumentedTest.kt'
s = io.open(path, encoding='utf-8').read()

old = """    @Test
    fun measureReadAndDecrypt() {
        val counts = listOf(100, 1000, 10000)
        for (n in counts) {
        // This test destroys the database, so it only runs against the
        // ISOLATED variant. On the production package it is SKIPPED
        // with a reason, and nothing was deleted.
        DestructiveTestAssumption.requireIsolated(ctx)
            TestDataIsolation.clearDatabase(ctx)
            seed(n)
            // Warm the page cache so the measurement reflects decryption cost
            // rather than cold disk IO.
            db.recent(10)
            val samples = ArrayList<Double>()
            repeat(5) {
                val elapsed = measureNanoTime { db.recent(n) }
                samples += elapsed / 1_000_000.0
            }
            samples.sort()
            val p50 = samples[samples.size / 2]
            val p95 = samples[(samples.size * 95 / 100).coerceAtMost(samples.size - 1)]
            println("PERF read+decrypt $n rows: p50=${"%.1f".format(p50)} ms " +
                "p95=${"%.1f".format(p95)} ms " +
                "(${"%.2f".format(p50 * 1000 / n)} us/row) " +
                "dbSize=${dbFileSize() / 1024} KiB")
        }
        assertTrue(true)
    }"""

new = """    @Test
    fun measureReadAndDecrypt() {
        val counts = listOf(100, 1000, 10000)
        // The wall-clock budget of the measurement.
        //
        // Reading and decrypting 10,000 rows takes seconds per sample, and
        // five samples of it can exceed the instrumentation timeout on a
        // slow machine. A benchmark that FAILS because it is slow is not
        // reporting anything, so the sample count is reduced for the large
        // scale instead of letting the test time out.
        //
        // The number of samples taken is printed, so a reduced sample count
        // is visible rather than implied.
        val budgetMillis = 120_000L
        for (n in counts) {
        // This test destroys the database, so it only runs against the
        // ISOLATED variant. On the production package it is SKIPPED
        // with a reason, and nothing was deleted.
        DestructiveTestAssumption.requireIsolated(ctx)
            TestDataIsolation.clearDatabase(ctx)
            seed(n)
            // Warm the page cache so the measurement reflects decryption cost
            // rather than cold disk IO.
            db.recent(10)
            val samples = ArrayList<Double>()
            val started = System.currentTimeMillis()
            while (samples.size < 5) {
                val elapsed = measureNanoTime { db.recent(n) }
                samples += elapsed / 1_000_000.0
                // Stop early if the remaining budget cannot fit another
                // sample.
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
    }"""

assert old in s, 'the read benchmark was not found'
s = s.replace(old, new)
io.open(path, 'w', encoding='utf-8').write(s)
print("the read benchmark is now bounded by a time budget")
