import io

path = 'app/src/androidTest/java/tw/personal/privateinbox/LatencyBenchmarkInstrumentedTest.kt'
s = io.open(path, encoding='utf-8').read()

# 1. The setUp must NOT reset the shared metrics.
old = """        // The metrics live in the APP's process, so they are reset through the
        // provider rather than in this process.
        //
        // The reset happens in setUp, which runs BEFORE the measurement
        // window. But the instrumentation tests share the app's process, so
        // another test that resets the metrics while this one is measuring
        // would wipe its samples. The reset is therefore the LAST thing
        // before this test's own measurement, and the samples are read
        // immediately after it.
        BenchmarkSetup.resetMetrics()
    }"""
new = """        // The metrics are NOT reset here.
        //
        // They live in the APP's process, and every instrumentation test runs
        // against that same process, so a reset would wipe the samples of a
        // test that is measuring at the same time. The burst matrix hit this
        // exact problem and now takes a baseline instead.
        //
        // The latency test does the same: it reads the counters BEFORE its
        // measurement and reports the DIFFERENCE.
    }"""
assert old in s, 'the reset call was not found'
s = s.replace(old, new)

# 2. The report reads the DELTA against a baseline.
old2 = """    private fun latencyReport(): Pair<String, String> {
        val res = ctx.contentResolver.call(
            DebugSettingsProvider.contentUriFor(ctx), "latency", null, null
        )
        return (res?.getString("report") ?: "<no report>") to
            (res?.getString("targets") ?: "<no targets>")
    }"""
new2 = """    /**
     * The counters before this test's measurement window.
     *
     * The metrics are cumulative and shared, so the report is a DIFFERENCE.
     */
    private var baseline: Map<String, Long> = emptyMap()

    private fun snapshotCounters(): Map<String, Long> {
        val res = ctx.contentResolver.call(
            DebugSettingsProvider.contentUriFor(ctx), "latency", null, null
        )
        if (res == null) return emptyMap()
        val out = HashMap<String, Long>()
        for (stage in listOf("snapshot", "enqueue", "worker_start", "committed")) {
            out["${stage}_n"] = res.getLong("${stage}_n")
            out["${stage}_p50"] = res.getLong("${stage}_p50")
        }
        return out
    }

    private fun latencyReport(): Pair<String, String> {
        val res = ctx.contentResolver.call(
            DebugSettingsProvider.contentUriFor(ctx), "latency", null, null
        )
        val now = snapshotCounters()
        val lines = StringBuilder()
        for (stage in listOf("snapshot", "enqueue", "worker_start", "committed")) {
            val n = (now["${stage}_n"] ?: 0L) - (baseline["${stage}_n"] ?: 0L)
            lines.append(stage).append(" n=").append(n).append("  ")
        }
        return lines.toString() to
            (res?.getString("targets") ?: "<no targets>")
    }"""
assert old2 in s, 'the report function was not found'
s = s.replace(old2, new2)

# 3. The stage count is a DELTA too.
old3 = """    private fun stageCount(stage: String): Long {
        val res = ctx.contentResolver.call(
            DebugSettingsProvider.contentUriFor(ctx), "latency", null, null
        )
        return res?.getLong("${stage}_n") ?: 0L
    }"""
new3 = """    private fun stageCount(stage: String): Long {
        val now = snapshotCounters()
        return (now["${stage}_n"] ?: 0L) - (baseline["${stage}_n"] ?: 0L)
    }"""
assert old3 in s, 'the stage count was not found'
s = s.replace(old3, new3)

# 4. The baseline is taken at the start of each measurement.
old4 = """        val id = BurstNotifier.NOTIFICATION_ID_BASE + 1000
        notifier.postGroupUpdate(notificationId = id, messageCount = 1, firstIndex = 0)"""
new4 = """        baseline = snapshotCounters()
        val id = BurstNotifier.NOTIFICATION_ID_BASE + 1000
        notifier.postGroupUpdate(notificationId = id, messageCount = 1, firstIndex = 0)"""
assert old4 in s, 'the single measurement was not found'
s = s.replace(old4, new4)

old5 = """        val groups = 5
        val perGroup = 20"""
new5 = """        baseline = snapshotCounters()
        val groups = 5
        val perGroup = 20"""
assert old5 in s, 'the burst measurement was not found'
s = s.replace(old5, new5)

old6 = """        val id = BurstNotifier.NOTIFICATION_ID_BASE + 3000
        notifier.postGroupUpdate(notificationId = id, messageCount = 25, firstIndex = 0)"""
new6 = """        baseline = snapshotCounters()
        val id = BurstNotifier.NOTIFICATION_ID_BASE + 3000
        notifier.postGroupUpdate(notificationId = id, messageCount = 25, firstIndex = 0)"""
assert old6 in s, 'the callback measurement was not found'
s = s.replace(old6, new6)

io.open(path, 'w', encoding='utf-8').write(s)
print("the latency test now uses a baseline instead of a reset")
